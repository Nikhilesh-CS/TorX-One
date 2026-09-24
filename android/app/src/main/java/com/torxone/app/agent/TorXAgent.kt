package com.torxone.app.agent

import android.util.Log
import androidx.room.withTransaction
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.crypto.Identity
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactEntity
import com.torxone.app.protocol.ProtocolEnvelope
import com.torxone.app.transport.TransportMetadata
import com.torxone.app.transport.TransportRouter
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
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
    /** Optional provider for local identity (signing secret key). */
    var identityProvider: (() -> Identity?)? = null
    companion object {
        private const val TAG = "TorXAgent"
        private const val RETRY_BASE_MS = 3_000L
        private const val RETRY_MAX_MS = 300_000L  // 5 minutes max backoff
        private const val DELIVERY_LOOP_INTERVAL_MS = 2_000L
        private const val RECIPIENT_ACK_TIMEOUT_MS = 30_000L
        private const val PRUNE_INTERVAL_MS = 60 * 60 * 1000L  // 1 hour
    }

    private var deliveryJob: Job? = null
    private var pruneJob: Job? = null

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    /** Best currently usable signaling route for higher-level realtime features. */
    fun preferredSignalingTransport(contact: ContactEntity): com.torxone.app.network.Transport {
        if (contact.endpointId.isNotBlank() && transportRouter.getConnectedEndpoints().contains(contact.endpointId)) {
            return com.torxone.app.network.Transport.NEARBY_DIRECT
        }
        if (contact.onionAddress.isNotBlank() && transportRouter.isTorReady()) {
            return com.torxone.app.network.Transport.TOR
        }
        if (transportRouter.isRelayAvailable()) {
            // Relay is only the signaling path; WebRTC still negotiates its media path.
            return com.torxone.app.network.Transport.PENDING
        }
        return com.torxone.app.network.Transport.FAILED
    }

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

    /**
     * Trigger immediate processing of pending delivery queues (e.g. when network connects or retry is requested).
     */
    fun triggerProcessing() {
        scope.launch {
            try {
                processDeliveryQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[TRIGGER] Error: ${e.message}")
            }
        }
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

        // Sequence allocation and outbox insertion must commit or roll back together.
        val entity = db.withTransaction {
            val localKey = identityProvider?.invoke()?.signingPublicKey?.let(CryptoManager::toHex).orEmpty()
            val connection = connectionManager.getOrCreateConnection(normalizedKey, localKey)
            val seq = connection.lastSendSeq + 1
            val now = System.currentTimeMillis()
            val prevHash = connection.lastSentHash
            val wireType = EnvelopeType.toWireType(messageType)
            val currentHash = com.torxone.app.protocol.ProtocolEnvelope.computeEnvelopeHash(
                previousHash = prevHash,
                connectionId = connection.connectionId,
                queueId = connection.sendQueueId,
                sequenceNumber = seq,
                messageType = wireType,
                ciphertext = encryptedPayload
            )
            db.connectionQueueDao().updateSendCursor(connection.connectionId, seq, currentHash, now)
            DeliveryQueueEntity(
                envelopeId = UUID.randomUUID().toString(),
                connectionId = connection.connectionId,
                messageId = messageId,
                queueId = connection.sendQueueId,
                sequenceNumber = seq,
                previousMessageHash = prevHash,
                envelopeHash = currentHash,
                messageType = wireType,
                encryptedPayload = encryptedPayload,
                state = "QUEUED",
                createdAt = now,
                nextRetryAt = now,
                expiresAt = now + MessageEnvelope.DEFAULT_TTL_MS,
                recipientKey = normalizedKey
            ).also { deliveryQueueDao.insert(it) }
        }

        Log.i(TAG, "[QUEUE] envelopeId=${entity.envelopeId} msgId=$messageId type=${messageType.name} seq=${entity.sequenceNumber}")

        // Attempt immediate delivery
        scope.launch { attemptDelivery(entity) }

        return entity.envelopeId
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

    // ──────────────────────── ACK/READ / DEVICE_RECEIVED ────────────────────────

    /**
     * Handle an authenticated ACK receipt — marks envelope as DELIVERED and updates message status.
     * Recipient successfully decrypted + persisted message and emitted authenticated delivery ACK.
     */
    suspend fun handleAck(messageId: String) {
        deliveryQueueDao.markDelivered(messageId)
        val msg = db.messageDao().getMessageById(messageId)
        if (msg != null && msg.direction == "sent") {
            db.messageDao().updateSentMessageStatus(messageId, msg.contactKey, "delivered")
        }
        Log.i(TAG, "[ACK] msgId=$messageId marked DELIVERED")
    }

    /**
     * Handle an authenticated READ receipt — marks envelope as READ and updates message status.
     * Recipient UI/application layer displayed message and emitted authenticated READ receipt.
     */
    suspend fun handleRead(messageId: String) {
        deliveryQueueDao.markRead(messageId)
        val msg = db.messageDao().getMessageById(messageId)
        if (msg != null && msg.direction == "sent") {
            db.messageDao().updateSentMessageStatus(messageId, msg.contactKey, "read")
        }
        Log.i(TAG, "[READ] msgId=$messageId marked READ")
    }

    /**
     * Handle DEVICE_RECEIVED event — recipient device confirmed receipt and wire validation.
     */
    suspend fun handleDeviceReceived(messageId: String) {
        deliveryQueueDao.markDeviceReceived(messageId)
        Log.d(TAG, "[DEVICE_RECEIVED] msgId=$messageId marked DEVICE_RECEIVED")
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

        val recovered = deliveryQueueDao.recoverUnacknowledged(
            staleBefore = now - RECIPIENT_ACK_TIMEOUT_MS,
            now = now
        )
        if (recovered > 0) {
            Log.w(TAG, "[DELIVERY_LOOP] Re-queued $recovered unacknowledged or interrupted deliveries")
        }

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
        // Transition to TRANSMITTING (with regression guard)
        deliveryQueueDao.markTransmitting(entity.envelopeId)

        // Resolve peer addresses for transport router
        val connection = connectionManager.getConnectionById(entity.connectionId)
        val identity = identityProvider?.invoke()
        if (connection == null || identity == null) {
            val backoff = calculateBackoff(entity.retryCount)
            deliveryQueueDao.scheduleRetry(entity.envelopeId, System.currentTimeMillis() + backoff)
            Log.w(TAG, "[DELIVER] Missing connection or unlocked signing identity for ${entity.envelopeId}")
            return
        }

        val senderKey = CryptoManager.toHex(identity.signingPublicKey).lowercase()
        if (connection.localPartyKey != senderKey || connection.remotePartyKey != entity.recipientKey) {
            deliveryQueueDao.markFailed(entity.envelopeId)
            Log.e(TAG, "[DELIVER] Connection identity mismatch for ${entity.envelopeId}")
            return
        }

        val unsignedEnvelope = ProtocolEnvelope(
            envelopeId = entity.envelopeId,
            connectionId = entity.connectionId,
            queueId = entity.queueId,
            replyQueueId = connection.recvQueueId,
            sequenceNumber = entity.sequenceNumber,
            previousHash = entity.previousMessageHash,
            timestamp = entity.createdAt,
            messageType = entity.messageType,
            ciphertext = entity.encryptedPayload,
            senderKey = senderKey,
            recipientKey = entity.recipientKey,
            signature = ""
        )
        val wireEnvelope = ProtocolEnvelope.toJson(
            ProtocolEnvelope.sign(unsignedEnvelope, identity.signingSecretKey)
        )

        val peerAddresses = resolvePeerAddresses(entity)

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
            queueCapability = connectionManager.deriveDirectionalQueueCapability(
                connection.localPartyKey,
                connection.remotePartyKey
            ),
            isRetry = entity.retryCount > 0,
            attemptNumber = entity.retryCount + 1
        )

        val result = transportRouter.deliver(peerAddresses, wireEnvelope, metadata)

        if (result.success) {
            val isRelay = result.transportType == TransportType.OFFLINE_RELAY
            if (isRelay) {
                // Relay accepted ciphertext into its durable queue; Bob has NOT received or decrypted it yet
                deliveryQueueDao.markRelayAccepted(
                    entity.envelopeId,
                    result.transportType.name
                )
                db.messageDao().updateSentMessageStatus(
                    entity.messageId,
                    entity.recipientKey,
                    "sent",
                    result.transportType.name
                )
                Log.i(TAG, "[DELIVER] envelopeId=${entity.envelopeId} RELAY_ACCEPTED via ${result.transportType} (${result.latencyMs}ms)")
            } else {
                // Direct transport socket transmitted the envelope
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
                Log.i(TAG, "[DELIVER] envelopeId=${entity.envelopeId} TRANSMITTED via direct ${result.transportType} (${result.latencyMs}ms)")
            }
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
     * Deadlock-free two-way handshake:
     * 1. Proposes new queue Q2, leaving active queue Q1 intact
     * 2. Cryptographically signs ROTATE_PROPOSE(Q2) with local signing secret key
     * 3. Sends proposal through current active queue Q1 (which peer is listening to)
     * 4. Activation of Q2 occurs ONLY upon receiving authenticated ROTATE_ACK from peer!
     */
    suspend fun rotateQueues(
        remoteKey: String,
        mySigningSecretKey: ByteArray? = null
    ): Boolean {
        val conn = connectionManager.getConnectionByRemoteKey(remoteKey) ?: run {
            Log.w(TAG, "[ROTATE] Connection not found for $remoteKey")
            return false
        }
        val secretKey = mySigningSecretKey ?: identityProvider?.invoke()?.signingSecretKey
        if (secretKey == null) {
            Log.w(TAG, "[ROTATE] Cannot rotate queues without signing secret key")
            return false
        }

        // Propose new send queue (Q2) — stays staged in pendingSendQueueId
        val proposal = connectionManager.proposeQueueRotation(conn.connectionId)

        // Cryptographically sign proposal
        val proposalWire = connectionManager.createSignedRotationProposal(
            connectionId = conn.connectionId,
            oldQueueId = conn.sendQueueId,
            newQueueId = proposal.proposedSendQueueId,
            generation = System.currentTimeMillis(),
            signingSecretKey = secretKey
        )

        // Queue proposal for delivery through CURRENT active queue Q1 (deadlock prevention!)
        queueForDelivery(
            recipientKey = remoteKey,
            messageId = "rotate_${System.currentTimeMillis()}",
            messageType = EnvelopeType.QUEUE_ROTATE_PROPOSE,
            encryptedPayload = proposalWire
        )
        Log.i(TAG, "[ROTATION] Queued cryptographically signed ROTATE_PROPOSE for $remoteKey via active queue ${conn.sendQueueId}")
        return true
    }

    /** Legacy overload taking hex string signing key for backward compatibility. */
    suspend fun rotateQueues(remoteKey: String, mySigningKey: String): Boolean {
        val secretBytes = CryptoManager.fromHexOrNull(mySigningKey, 64)
            ?: CryptoManager.fromHexOrNull(mySigningKey, 32)
        return rotateQueues(remoteKey, secretBytes)
    }

    /**
     * Handle queue rotation payloads (ROTATE_PROPOSE, ROTATE_ACK, or legacy notice).
     */
    suspend fun handleQueueRotationPayload(json: JSONObject) {
        val type = json.optString("type", "")
        when (type) {
            "QUEUE_ROTATE_PROPOSE", "ROTATE_PROPOSE" -> {
                val secretKey = identityProvider?.invoke()?.signingSecretKey
                val result = connectionManager.handleSignedRotationProposal(json, secretKey)
                if (result.success && result.ackWireJson != null && result.remotePartyKey != null) {
                    // Send back cryptographically signed ROTATE_ACK to peer
                    queueForDelivery(
                        recipientKey = result.remotePartyKey,
                        messageId = "rotate_ack_${System.currentTimeMillis()}",
                        messageType = EnvelopeType.QUEUE_ROTATE_ACK,
                        encryptedPayload = result.ackWireJson
                    )
                    Log.i(TAG, "[ROTATION] Sent signed ROTATE_ACK to ${result.remotePartyKey}")
                }
            }
            "QUEUE_ROTATE_ACK", "ROTATE_ACK" -> {
                val success = connectionManager.handleSignedRotationAck(json)
                Log.i(TAG, "[ROTATION] Handled ROTATE_ACK: success=$success")
            }
            else -> {
                // Fallback for legacy notice format
                connectionManager.handleQueueRotationNotice(json)
            }
        }
    }

    private suspend fun resolvePeerAddresses(entity: DeliveryQueueEntity): Map<TransportType, String> {
        val addresses = mutableMapOf<TransportType, String>()
        val recipientKey = entity.recipientKey

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

            // Offline relay store-and-forward fallback if relay is available (strictly opaque queue-addressed)
            if (transportRouter.isRelayAvailable()) {
                addresses[TransportType.OFFLINE_RELAY] = entity.queueId
            }
        }

        return addresses
    }

    /** Authenticate the outer v2 envelope before any routing or cursor mutation. */
    suspend fun authenticateEnvelope(envelope: ProtocolEnvelope): ConnectionQueueEntity? {
        val identity = identityProvider?.invoke() ?: return null
        val localKey = CryptoManager.toHex(identity.signingPublicKey).lowercase()
        if (envelope.recipientKey != localKey) {
            Log.w(TAG, "[AUTH] Envelope ${envelope.envelopeId} addressed to another identity")
            return null
        }
        if (!ProtocolEnvelope.verifySignature(envelope)) {
            Log.w(TAG, "[AUTH] Invalid v2 envelope signature ${envelope.envelopeId}")
            return null
        }
        if (db.contactDao().getContact(envelope.senderKey) == null) {
            Log.w(TAG, "[AUTH] Envelope sender is not an accepted contact: ${envelope.senderKey}")
            return null
        }

        val existing = connectionManager.getConnectionById(envelope.connectionId)
        val connection = existing ?: connectionManager.acceptAuthenticatedConnection(
            connectionId = envelope.connectionId,
            localPartyKey = localKey,
            remotePartyKey = envelope.senderKey,
            remoteSendQueueId = envelope.queueId,
            remoteReplyQueueId = envelope.replyQueueId
        ) ?: return null

        if (connection.localPartyKey != localKey ||
            connection.remotePartyKey != envelope.senderKey ||
            connection.sendQueueId != envelope.replyQueueId
        ) {
            Log.w(TAG, "[AUTH] Envelope connection parties/return queue do not match persisted state")
            return null
        }
        return connection
    }

    private fun calculateBackoff(retryCount: Int): Long {
        val backoff = RETRY_BASE_MS * (1L shl retryCount.coerceAtMost(10))
        return backoff.coerceAtMost(RETRY_MAX_MS)
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
