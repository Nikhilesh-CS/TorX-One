package com.torxone.app.agent

import android.util.Log
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.protocol.OpaqueTransportEnvelope
import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import com.torxone.app.transport.TransportRouter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * TorXAgent — The SINGLE delivery authority for TorX One.
 *
 * All outgoing network operations flow through here.
 * Owns delivery state transitions:
 *   QUEUED → TRANSMITTING → ACCEPTED → DELIVERED → READ
 *
 * Responsibilities:
 * - Durable outbox surviving app restarts
 * - Bounded exponential retry engine (3s, 6s, 12s... max 5m)
 * - Immediate retry on network recovery
 * - Recovery of stale TRANSMITTING items on restart
 * - Deduplication and delivery acknowledgment matching
 */
class TorXAgent(
    private val transportRouter: TransportRouter,
    private val outboxStore: OutboxStore,
    private val processedStore: ProcessedEnvelopeStore? = null,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val baseRetryDelayMs: Long = 3_000L,
    private val outboxPollIntervalMs: Long = 1_000L,
    private val relationshipForQueue: (String) -> String? = { null },
    private val isDeliveryPaused: suspend (DeliveryItem) -> Boolean = { false }
) {
    companion object {
        private const val TAG = "TorXAgent"
        private const val MAX_RETRY_ATTEMPTS = 50
        private const val MAX_RETRY_DELAY_MS = 300_000L // 5 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    private val sendSignal = Channel<Unit>(Channel.CONFLATED)

    /** Observable delivery status updates */
    private val _deliveryUpdates = MutableSharedFlow<DeliveryUpdate>(extraBufferCapacity = 64)
    val deliveryUpdates: SharedFlow<DeliveryUpdate> = _deliveryUpdates

    private var processingJob: Job? = null
    private val inflightItems = ConcurrentHashMap.newKeySet<String>()

    /**
     * Start the agent and recover stale items from persistent outbox.
     * Idempotent: safe to call multiple times without creating duplicate processing loops.
     */
    fun start() {
        if (processingJob?.isActive == true) return
        Log.i(TAG, "Starting TorXAgent")
        processingJob = scope.launch {
            recoverStaleOutboxItems()
            processOutbox()
        }
    }

    /**
     * Stop the agent gracefully.
     */
    fun stop() {
        Log.i(TAG, "Stopping TorXAgent")
        processingJob?.cancel()
    }

    /**
     * Enqueue a message for delivery.
     */
    suspend fun enqueue(item: DeliveryItem) {
        require(item.applicationSequence == null || item.expectsAck) {
            "Sequenced durable deliveries require receiver ACK"
        }
        Log.d(TAG, "[QUEUE] Enqueuing env=${item.deliveryId.take(8)} for msg=${item.logicalMessageId.take(8)}")
        outboxStore.insert(item)
        emitUpdate(item.logicalMessageId, DeliveryStatus.QUEUED)
        sendSignal.trySend(Unit)
    }

    /**
     * Wake the agent when an item was already persisted inside an outer database transaction.
     */
    fun wake(logicalMessageId: String? = null) {
        if (logicalMessageId != null) {
            scope.launch {
                emitUpdate(logicalMessageId, DeliveryStatus.QUEUED)
            }
        }
        sendSignal.trySend(Unit)
    }

    /**
     * Send an ephemeral message (e.g. TYPING_START, TYPING_STOP, PRESENCE_UPDATE).
     *
     * Semantics:
     * - Best-effort immediate delivery via TransportRouter
     * - Never stored in persistent OutboxStore (never retried after restart or reconnection)
     * - Drops immediately if expired by TTL
     * - Uses DeliveryPriority.LOW and doesn't pollute durable chat outbox
     */
    suspend fun sendEphemeral(item: DeliveryItem, ttlMs: Long = 15_000L): TransportResult {
        val now = System.currentTimeMillis()
        if (now - item.createdAt > ttlMs) {
            Log.d(TAG, "[EPHEMERAL EXPIRED] Dropping expired item ${item.deliveryId.take(8)}")
            return TransportResult.Failed(com.torxone.app.transport.TransportType.NEARBY, "Ephemeral message expired")
        }

        val authenticator = IdentityCrypto.computeQueueAuthenticator(
            queueAuthSecret = item.queueAuthenticator,
            envelopeId = item.deliveryId,
            queueAddress = item.queueAddress,
            ciphertext = item.ciphertext
        )
        val envelope = OpaqueTransportEnvelope(
            version = 1,
            envelopeId = item.deliveryId,
            queueAddress = item.queueAddress,
            opaqueCiphertext = item.ciphertext,
            queueAuthenticator = authenticator
        )
        val rawPayload = ProtocolCodec.encodeTransportEnvelope(envelope)
        val destination = TransportDestination(address = item.queueAddress,
            relationshipId = item.relationshipId.takeIf(String::isNotBlank) ?: relationshipForQueue(item.queueAddress))

        val result = transportRouter.send(destination, rawPayload)
        Log.d(TAG, "[EPHEMERAL SEND] item=${item.deliveryId.take(8)} result=$result")
        return result
    }

    /**
     * Trigger immediate retry (e.g. when Nearby connects).
     * Resets waiting retry items so they transmit immediately without waiting for backoff timers.
     */
    fun triggerImmediateRetry(conversationId: String? = null) {
        scope.launch {
            try {
                recoverStaleOutboxItems(conversationId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to recover stale outbox items during immediate retry: ${e.message}", e)
            }
            sendSignal.trySend(Unit)
        }
    }

    /**
     * Mark message delivered when valid authenticated ACK arrives.
     */
    suspend fun markDelivered(logicalMessageId: String) {
        Log.i(TAG, "[DELIVERED] ACK confirmed msg=${logicalMessageId.take(8)}")
        outboxStore.removeByMessageId(logicalMessageId)
        emitUpdate(logicalMessageId, DeliveryStatus.DELIVERED)
        wake()
    }

    /**
     * Exact delivery-level acknowledgment (Phase 13).
     * Removes the exact delivery item without accidentally removing pending deliveries for other group recipients.
     */
    suspend fun markDeliveryAcknowledged(deliveryId: String, logicalMessageId: String? = null) {
        Log.i(TAG, "[DELIVERED] Delivery ACK confirmed delivery=${deliveryId.take(8)}")
        outboxStore.removeByDeliveryId(deliveryId)
        if (logicalMessageId != null) {
            emitUpdate(logicalMessageId, DeliveryStatus.DELIVERED)
        }
        wake()
    }

    /**
     * Mark message read.
     */
    suspend fun markRead(logicalMessageId: String) {
        emitUpdate(logicalMessageId, DeliveryStatus.READ)
    }

    private suspend fun recoverStaleOutboxItems(conversationId: String? = null) {
        try {
            val pending = outboxStore.getPendingItems()
            val now = System.currentTimeMillis()
            for (item in pending) {
                if (item.deliveryId in inflightItems || (conversationId != null && item.conversationId != conversationId)) continue
                if (item.status == DeliveryStatus.TRANSMITTING ||
                    item.status == DeliveryStatus.RETRY_WAIT ||
                    (item.status == DeliveryStatus.TRANSPORT_ACCEPTED && now - item.updatedAt > baseRetryDelayMs)
                ) {
                    outboxStore.updateRetry(item.deliveryId, item.attemptCount, now)
                    outboxStore.updateStatus(item.deliveryId, DeliveryStatus.QUEUED)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recovering stale outbox items", e)
        }
    }

    private suspend fun processOutbox() {
        val destinationJobs = mutableMapOf<String, Job>()
        while (currentCoroutineContext().isActive) {
            try {
                val pending = outboxStore.getPendingItems()

                if (pending.isNotEmpty()) {
                    // Group by queueAddress so deliveries to the same peer/destination maintain strict FIFO ordering,
                    // while deliveries to independent destinations execute concurrently without head-of-line blocking.
                    val groupedByDestination = pending.groupBy { item ->
                        if (item.applicationSequence != null && item.relationshipId.isNotBlank()) {
                            "relationship:${item.relationshipId}"
                        } else {
                            "queue:${item.queueAddress}"
                        }
                    }
                    destinationJobs.entries.removeAll { !it.value.isActive }
                        for ((destinationKey, destinationItems) in groupedByDestination) {
                            if (destinationJobs[destinationKey]?.isActive == true) continue
                            val worker = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.LAZY) {
                                // P0-4: Strict application sequence scheduling
                                // 1. High-priority non-sequenced traffic can jump ahead where protocol-safe
                                val highPriorityNonSequenced = destinationItems
                                    .filter { it.applicationSequence == null && it.priority > DeliveryPriority.NORMAL }
                                    .filter { it.nextAttemptAt <= System.currentTimeMillis() }
                                    .sortedWith(compareByDescending<DeliveryItem> { it.priority }.thenBy { it.createdAt })
                                    // Bound each sweep so old control backlogs cannot keep
                                    // supervisorScope waiting before it sees newly queued work.
                                    .take(1)

                                for (item in highPriorityNonSequenced) {
                                    if (!isActive) break
                                    if (inflightItems.add(item.deliveryId)) {
                                        try {
                                            processDeliveryItem(item)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error delivering high-priority item ${item.deliveryId} to ${item.queueAddress}", e)
                                        } finally {
                                            inflightItems.remove(item.deliveryId)
                                        }
                                    }
                                }

                                // 2. Sequenced durable items MUST transmit strictly by applicationSequence ASC.
                                // An item with sequence N+1 must not overtake N, and ACK timeout
                                // retries must remain outstanding until authenticated delivery ACK.
                                // Retry state must not allow sequence overtaking: if sequence N is in retry backoff, halt.
                                val sequencedItems = destinationItems
                                    .filter { it.applicationSequence != null }
                                    .sortedWith(compareBy<DeliveryItem> { it.applicationSequence }.thenBy { it.createdAt })

                                val now = System.currentTimeMillis()
                                for (item in sequencedItems) {
                                    if (!isActive) break
                                    if (item.nextAttemptAt > now) {
                                        // Sequence N is still waiting for its retry backoff window.
                                        // Invariant: Sequence N+1 cannot transmit until N succeeds or expires.
                                        break
                                    }
                                    if (item.status == DeliveryStatus.TRANSPORT_ACCEPTED) {
                                        // Transport acceptance is not receiver commit. Re-send the
                                        // same ciphertext only after ACK timeout, never transmit N+1.
                                        if (inflightItems.add(item.deliveryId)) {
                                            try {
                                                val transportAccepted = processDeliveryItem(item)
                                                if (!transportAccepted) break
                                            } finally {
                                                inflightItems.remove(item.deliveryId)
                                            }
                                        }
                                        break
                                    }
                                    if (inflightItems.add(item.deliveryId)) {
                                        try {
                                            val transportAccepted = processDeliveryItem(item)
                                            if (!transportAccepted) break
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error delivering sequenced item ${item.deliveryId} to ${item.queueAddress}", e)
                                            break
                                        } finally {
                                            inflightItems.remove(item.deliveryId)
                                        }
                                    }
                                }

                                // 3. Remaining normal or low-priority non-sequenced items
                                val remainingNonSequenced = destinationItems
                                    .filter { it.applicationSequence == null && it.priority <= DeliveryPriority.NORMAL }
                                    .filter { it.nextAttemptAt <= System.currentTimeMillis() }
                                    .sortedWith(compareByDescending<DeliveryItem> { it.priority }.thenBy { it.createdAt })
                                    .take(1)

                                for (item in remainingNonSequenced) {
                                    if (!isActive) break
                                    if (inflightItems.add(item.deliveryId)) {
                                        try {
                                            processDeliveryItem(item)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error delivering item ${item.deliveryId} to ${item.queueAddress}", e)
                                        } finally {
                                            inflightItems.remove(item.deliveryId)
                                        }
                                    }
                                }
                            }
                            destinationJobs[destinationKey] = worker
                            worker.start()
                        }
                }

                withTimeoutOrNull(outboxPollIntervalMs) {
                    sendSignal.receive()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in outbox processing loop", e)
                delay(outboxPollIntervalMs)
            }
        }
    }

    private suspend fun processDeliveryItem(item: DeliveryItem): Boolean {
        // Keep ciphertext and sequence intact. Removing a sequenced item would strand later sends.
        if (isDeliveryPaused(item)) return false
        // Allocated sequences must still attempt transport after the retry limit.
        // Rescheduling them without sending permanently stalls the entire lane.
        if (item.attemptCount >= MAX_RETRY_ATTEMPTS && item.applicationSequence == null) {
            Log.w(TAG, "Delivery ${item.deliveryId.take(8)} exceeded max retries, marking FAILED")
            outboxStore.updateStatus(item.deliveryId, DeliveryStatus.FAILED)
            emitUpdate(item.logicalMessageId, DeliveryStatus.FAILED)
            return true
        }

        if (item.nextAttemptAt > System.currentTimeMillis()) {
            return false
        }

        outboxStore.updateStatus(item.deliveryId, DeliveryStatus.TRANSMITTING)
        emitUpdate(item.logicalMessageId, DeliveryStatus.TRANSMITTING)

        // Build transport envelope with cryptographic HMAC authenticator derived from the shared sendAuth secret
        val authenticator = IdentityCrypto.computeQueueAuthenticator(
            queueAuthSecret = item.queueAuthSecret,
            envelopeId = item.deliveryId,
            queueAddress = item.queueAddress,
            ciphertext = item.ciphertext
        )
        val envelope = OpaqueTransportEnvelope(
            version = 1,
            envelopeId = item.deliveryId,
            queueAddress = item.queueAddress,
            opaqueCiphertext = item.ciphertext,
            queueAuthenticator = authenticator
        )
        val rawPayload = ProtocolCodec.encodeTransportEnvelope(envelope)
        val destination = TransportDestination(address = item.queueAddress,
            relationshipId = item.relationshipId.takeIf(String::isNotBlank) ?: relationshipForQueue(item.queueAddress))

        val result = transportRouter.send(destination, rawPayload)

        when (result) {
            is TransportResult.Accepted -> {
                Log.i(TAG, "[ACCEPTED] ${result.transportType} accepted env=${item.deliveryId.take(8)}")
                if (!item.expectsAck) {
                    outboxStore.removeByMessageId(item.logicalMessageId)
                    emitUpdate(item.logicalMessageId, DeliveryStatus.DELIVERED)
                } else {
                    // Schedule next attempt with backoff in case ACK is lost on the wire
                    val ackTimeout = calculateBackoff(item.attemptCount)
                    val attempts = if (item.status == DeliveryStatus.TRANSPORT_ACCEPTED) item.attemptCount else item.attemptCount + 1
                    outboxStore.updateRetry(
                        deliveryId = item.deliveryId,
                        attemptCount = attempts,
                        nextAttemptAt = System.currentTimeMillis() + ackTimeout
                    )
                    outboxStore.updateStatus(item.deliveryId, DeliveryStatus.TRANSPORT_ACCEPTED)
                    emitUpdate(item.logicalMessageId, DeliveryStatus.TRANSPORT_ACCEPTED)
                }
                return true
            }

            is TransportResult.Failed -> {
                val nextDelay = calculateBackoff(item.attemptCount)
                Log.w(TAG, "[FAILED] Delivery ${item.deliveryId.take(8)} attempt ${item.attemptCount + 1} err=${result.error}, retry in ${nextDelay}ms")
                outboxStore.updateRetry(
                    deliveryId = item.deliveryId,
                    attemptCount = item.attemptCount + 1,
                    nextAttemptAt = System.currentTimeMillis() + nextDelay
                )
                emitUpdate(item.logicalMessageId, DeliveryStatus.RETRY_WAIT)
                return false
            }
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val multiplier = 1L shl minOf(attempt, 6) // 1, 2, 4, 8, 16, 32, 64
        return minOf(baseRetryDelayMs * multiplier, MAX_RETRY_DELAY_MS)
    }

    private suspend fun emitUpdate(logicalMessageId: String, status: DeliveryStatus) {
        _deliveryUpdates.emit(DeliveryUpdate(logicalMessageId, status))
    }
}

data class DeliveryUpdate(
    val logicalMessageId: String,
    val status: DeliveryStatus,
    val timestamp: Long = System.currentTimeMillis()
)

interface OutboxStore {
    suspend fun insert(item: DeliveryItem)
    suspend fun getPendingItems(): List<DeliveryItem>
    suspend fun updateStatus(deliveryId: String, status: DeliveryStatus)
    suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long)
    suspend fun removeByMessageId(logicalMessageId: String)
    suspend fun removeByDeliveryId(deliveryId: String) {}
}

interface ProcessedEnvelopeStore {
    suspend fun isProcessed(envelopeId: String): Boolean
    suspend fun isMessageProcessed(logicalMessageId: String): Boolean
    suspend fun markProcessed(record: ProcessedEnvelope)
}
