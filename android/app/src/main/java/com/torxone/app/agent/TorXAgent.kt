package com.torxone.app.agent

import android.util.Log
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactEntity
import com.torxone.app.transport.TransportMetadata
import com.torxone.app.transport.TransportRouter
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.UUID

/**
 * TorX One 2.0 — TorX Agent
 *
 * The central delivery engine, inspired by SimpleX's Agent layer.
 *
 * Responsibilities:
 * 1. Outbox management — persist-before-send, guaranteed delivery
 * 2. Retry with exponential backoff
 * 3. ACK/READ receipt tracking
 * 4. Deduplication of incoming messages
 * 5. Sequence ordering within connections
 * 6. Transport-agnostic delivery via TransportRouter
 *
 * **Key invariant**: Every outgoing envelope is persisted to the
 * delivery queue BEFORE any transport attempt. If the app crashes
 * mid-send, the retry loop picks it up on restart.
 *
 * **Offline is not an error state.** If no transport is available,
 * the envelope stays QUEUED and is retried when connectivity returns.
 */
class TorXAgent(
    private val db: AppDatabase,
    private val transportRouter: TransportRouter,
    private val deliveryQueueDao: DeliveryQueueDao,
    val connectionManager: com.torxone.app.connection.ConnectionManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "TorXAgent"
        private const val RETRY_BASE_MS = 3_000L
        private const val RETRY_MAX_MS = 300_000L  // 5 minutes max backoff
        private const val DELIVERY_LOOP_INTERVAL_MS = 2_000L
        private const val PRUNE_INTERVAL_MS = 60 * 60 * 1000L  // 1 hour
    }

    private var deliveryJob: Job? = null
    private var pruneJob: Job? = null

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    /** Track processed incoming message IDs for deduplication. */
    private val processedIncoming = java.util.Collections.synchronizedSet(
        object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (size >= 5000) {
                    val iter = iterator()
                    if (iter.hasNext()) { iter.next(); iter.remove() }
                }
                return super.add(element)
            }
        }
    )

    // ──────────────────────── LIFECYCLE ────────────────────────

    /** Start the agent's delivery and maintenance loops. */
    fun start() {
        startDeliveryLoop()
        startPruneLoop()
        Log.i(TAG, "[START] TorX Agent started")
    }

    /** Stop the agent gracefully. */
    fun stop() {
        deliveryJob?.cancel()
        pruneJob?.cancel()
        Log.i(TAG, "[STOP] TorX Agent stopped")
    }

    // ──────────────────────── SEND API ────────────────────────

    /**
     * Queue an envelope for delivery.
     *
     * This is the primary send API. The envelope is:
     * 1. Persisted to the delivery queue (crash-safe)
     * 2. Attempted immediately if transports are available
     * 3. Retried automatically with exponential backoff
     *
     * @param recipientKey Recipient's signing public key hex.
     * @param messageId Application-level message ID.
     * @param messageType Type of message being sent.
     * @param encryptedPayload Already-encrypted payload string.
     * @return The envelope ID for tracking.
     */
    suspend fun queueForDelivery(
        recipientKey: String,
        messageId: String,
        messageType: EnvelopeType,
        encryptedPayload: String
    ): String {
        val normalizedKey = recipientKey.trim().lowercase()

        // Ensure connection exists via ConnectionManager
        val connection = connectionManager.getOrCreateConnection(normalizedKey)
        val seq = connectionManager.nextSendSequence(connection.connectionId)

        // Generate envelope
        val envelopeId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Compute hash chain
        val prevHash = computeEnvelopeHash(connection.connectionId, seq - 1)

        val entity = DeliveryQueueEntity(
            envelopeId = envelopeId,
            connectionId = connection.connectionId,
            messageId = messageId,
            queueId = connection.sendQueueId,
            sequenceNumber = seq,
            previousMessageHash = prevHash,
            messageType = EnvelopeType.toWireType(messageType),
            encryptedPayload = encryptedPayload,
            state = "QUEUED",
            createdAt = now,
            nextRetryAt = now, // Immediate first attempt
            expiresAt = now + MessageEnvelope.DEFAULT_TTL_MS,
            recipientKey = normalizedKey
        )

        // Persist BEFORE any send attempt (crash-safe invariant)
        deliveryQueueDao.insert(entity)

        Log.i(TAG, "[QUEUE] envelopeId=$envelopeId msgId=$messageId type=${messageType.name} seq=$seq → $normalizedKey")

        // Attempt immediate delivery
        scope.launch { attemptDelivery(entity) }

        return envelopeId
    }

    // ──────────────────────── INCOMING ────────────────────────

    /**
     * Check if a message has already been processed (deduplication).
     */
    fun isAlreadyProcessed(messageId: String): Boolean {
        return processedIncoming.contains(messageId)
    }

    /**
     * Mark a message as processed for deduplication.
     */
    fun markProcessed(messageId: String) {
        processedIncoming.add(messageId)
    }

    // ──────────────────────── ACK/READ ────────────────────────

    /**
     * Handle an ACK receipt — mark the envelope as DELIVERED.
     */
    suspend fun handleAck(messageId: String) {
        deliveryQueueDao.markDelivered(messageId)
        Log.d(TAG, "[ACK] msgId=$messageId → DELIVERED")
    }

    /**
     * Handle a READ receipt — mark the envelope as READ.
     */
    suspend fun handleRead(messageId: String) {
        deliveryQueueDao.markRead(messageId)
        Log.d(TAG, "[READ] msgId=$messageId → READ")
    }

    // ──────────────────────── DELIVERY ENGINE ────────────────────────

    private fun startDeliveryLoop() {
        deliveryJob?.cancel()
        deliveryJob = scope.launch {
            while (isActive) {
                try {
                    processDeliveryQueue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[DELIVERY_LOOP] Error: ${e.message}")
                }
                delay(DELIVERY_LOOP_INTERVAL_MS)
            }
        }
    }

    private suspend fun processDeliveryQueue() {
        val now = System.currentTimeMillis()

        // Get pending envelopes ready for delivery
        val pending = deliveryQueueDao.getPendingDeliveries(now, limit = 30)
        _pendingCount.value = pending.size

        if (pending.isEmpty()) return

        Log.d(TAG, "[DELIVERY_LOOP] Processing ${pending.size} pending envelopes")

        for (entity in pending) {
            if (entity.retryCount >= MessageEnvelope.MAX_RETRIES) {
                deliveryQueueDao.markFailed(entity.envelopeId)
                db.messageDao().updateSentMessageStatus(entity.messageId, entity.recipientKey, "failed")
                Log.w(TAG, "[DELIVERY_LOOP] envelopeId=${entity.envelopeId} FAILED after ${entity.retryCount} retries")
                continue
            }

            attemptDelivery(entity)
        }
    }

    private suspend fun attemptDelivery(entity: DeliveryQueueEntity) {
        // Transition to TRANSMITTING
        deliveryQueueDao.updateState(entity.envelopeId, "TRANSMITTING")

        // Resolve peer addresses for transport router
        val peerAddresses = resolvePeerAddresses(entity.recipientKey)

        if (peerAddresses.isEmpty()) {
            // No transports available — schedule retry (offline is normal)
            val backoff = calculateBackoff(entity.retryCount)
            deliveryQueueDao.scheduleRetry(
                entity.envelopeId,
                System.currentTimeMillis() + backoff
            )
            Log.d(TAG, "[DELIVER] envelopeId=${entity.envelopeId} no transports, retry in ${backoff}ms")
            return
        }

        val metadata = TransportMetadata(
            messageId = entity.messageId,
            envelopeId = entity.envelopeId,
            isRetry = entity.retryCount > 0,
            attemptNumber = entity.retryCount + 1
        )

        val result = transportRouter.deliver(peerAddresses, entity.encryptedPayload, metadata)

        if (result.success) {
            deliveryQueueDao.markAccepted(
                entity.envelopeId,
                result.transportType.name
            )
            db.messageDao().updateSentMessageStatus(
                entity.messageId,
                entity.recipientKey,
                "sent",
                result.transportType.name
            )
            Log.i(TAG, "[DELIVER] envelopeId=${entity.envelopeId} ACCEPTED via ${result.transportType} (${result.latencyMs}ms)")
        } else {
            val backoff = calculateBackoff(entity.retryCount)
            deliveryQueueDao.scheduleRetry(
                entity.envelopeId,
                System.currentTimeMillis() + backoff
            )
            Log.w(TAG, "[DELIVER] envelopeId=${entity.envelopeId} failed: ${result.error}, retry in ${backoff}ms (attempt=${entity.retryCount + 1})")
        }
    }

    // ──────────────────────── HELPERS ────────────────────────

    /**
     * Rotate send queue for forward secrecy / traffic decoupling.
     */
    suspend fun rotateQueues(remoteKey: String, mySigningKey: String): Boolean {
        val conn = connectionManager.getConnectionByRemoteKey(remoteKey) ?: return false
        val newSendQueueId = connectionManager.rotateSendQueue(conn.connectionId)
        val notice = connectionManager.encodeQueueRotationNotice(
            connectionId = conn.connectionId,
            isSendQueue = true,
            newQueueId = newSendQueueId,
            fromKey = mySigningKey,
            toKey = remoteKey
        )
        queueForDelivery(
            recipientKey = remoteKey,
            messageId = "rotate_${System.currentTimeMillis()}",
            messageType = EnvelopeType.MSG,
            encryptedPayload = notice
        )
        return true
    }

    private suspend fun resolvePeerAddresses(recipientKey: String): Map<TransportType, String> {
        val addresses = mutableMapOf<TransportType, String>()

        // Check Nearby direct connection
        val contact = db.contactDao().getContact(recipientKey)
        if (contact != null) {
            val endpointId = contact.endpointId
            if (endpointId.isNotBlank() && transportRouter.getConnectedEndpoints().contains(endpointId)) {
                addresses[TransportType.NEARBY_DIRECT] = endpointId
            }

            // Check Tor availability
            val onion = contact.onionAddress
            if (onion.isNotBlank() && transportRouter.isTorReady()) {
                addresses[TransportType.TOR] = onion
            }

            // Nearby relay is always a possibility if we have connected endpoints
            if (transportRouter.getConnectedEndpoints().isNotEmpty()) {
                // For relay, we use the recipientKey as the "address" — 
                // the relay encoding happens at the protocol layer
                addresses[TransportType.NEARBY_RELAY] = recipientKey
            }
        }

        return addresses
    }

    private fun calculateBackoff(retryCount: Int): Long {
        val backoff = RETRY_BASE_MS * (1L shl retryCount.coerceAtMost(10))
        return backoff.coerceAtMost(RETRY_MAX_MS)
    }

    private fun computeEnvelopeHash(connectionId: String, seq: Long): String? {
        if (seq <= 0) return null
        val input = "$connectionId:$seq"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ──────────────────────── MAINTENANCE ────────────────────────

    private fun startPruneLoop() {
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                delay(PRUNE_INTERVAL_MS)
                try {
                    val now = System.currentTimeMillis()
                    deliveryQueueDao.pruneExpired(now)
                    deliveryQueueDao.pruneCompleted(now - 24 * 60 * 60 * 1000L) // Keep completed for 24h
                    Log.d(TAG, "[PRUNE] Cleaned expired and old envelopes")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "[PRUNE] Error: ${e.message}")
                }
            }
        }
    }
}
