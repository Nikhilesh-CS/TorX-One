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
    private val outboxPollIntervalMs: Long = 1_000L
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
     */
    fun start() {
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
        val destination = TransportDestination(address = item.queueAddress)

        val result = transportRouter.send(destination, rawPayload)
        Log.d(TAG, "[EPHEMERAL SEND] item=${item.deliveryId.take(8)} result=$result")
        return result
    }

    /**
     * Trigger immediate retry (e.g. when Nearby connects).
     * Resets waiting retry items so they transmit immediately without waiting for backoff timers.
     */
    fun triggerImmediateRetry() {
        scope.launch {
            try {
                recoverStaleOutboxItems()
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
    }

    /**
     * Mark message read.
     */
    suspend fun markRead(logicalMessageId: String) {
        emitUpdate(logicalMessageId, DeliveryStatus.READ)
    }

    private suspend fun recoverStaleOutboxItems() {
        try {
            val pending = outboxStore.getPendingItems()
            val now = System.currentTimeMillis()
            for (item in pending) {
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
        while (currentCoroutineContext().isActive) {
            try {
                val pending = outboxStore.getPendingItems()

                if (pending.isNotEmpty()) {
                    for (item in pending) {
                        if (!currentCoroutineContext().isActive) break
                        if (inflightItems.add(item.deliveryId)) {
                            try {
                                processDeliveryItem(item)
                            } finally {
                                inflightItems.remove(item.deliveryId)
                            }
                        }
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

    private suspend fun processDeliveryItem(item: DeliveryItem) {
        if (item.attemptCount >= MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Delivery ${item.deliveryId.take(8)} exceeded max retries, marking FAILED")
            outboxStore.updateStatus(item.deliveryId, DeliveryStatus.FAILED)
            emitUpdate(item.logicalMessageId, DeliveryStatus.FAILED)
            return
        }

        if (item.nextAttemptAt > System.currentTimeMillis()) {
            return
        }

        outboxStore.updateStatus(item.deliveryId, DeliveryStatus.TRANSMITTING)
        emitUpdate(item.logicalMessageId, DeliveryStatus.TRANSMITTING)

        // Build transport envelope with cryptographic HMAC authenticator
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
        val destination = TransportDestination(address = item.queueAddress)

        val result = transportRouter.send(destination, rawPayload)

        when (result) {
            is TransportResult.Accepted -> {
                Log.i(TAG, "[ACCEPTED] Nearby accepted env=${item.deliveryId.take(8)}")
                if (!item.expectsAck) {
                    outboxStore.removeByMessageId(item.logicalMessageId)
                    emitUpdate(item.logicalMessageId, DeliveryStatus.DELIVERED)
                } else {
                    // Schedule next attempt with backoff in case ACK is lost on the wire
                    val ackTimeout = calculateBackoff(item.attemptCount)
                    outboxStore.updateRetry(
                        deliveryId = item.deliveryId,
                        attemptCount = item.attemptCount + 1,
                        nextAttemptAt = System.currentTimeMillis() + ackTimeout
                    )
                    outboxStore.updateStatus(item.deliveryId, DeliveryStatus.TRANSPORT_ACCEPTED)
                    emitUpdate(item.logicalMessageId, DeliveryStatus.TRANSPORT_ACCEPTED)
                }
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
}

interface ProcessedEnvelopeStore {
    suspend fun isProcessed(envelopeId: String): Boolean
    suspend fun isMessageProcessed(logicalMessageId: String): Boolean
    suspend fun markProcessed(record: ProcessedEnvelope)
}
