package com.torxone.app.agent

import android.util.Log
import com.torxone.app.protocol.OpaqueTransportEnvelope
import com.torxone.app.transport.TransportRouter
import com.torxone.app.transport.TransportResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * TorXAgent — the SINGLE delivery authority for TorX One.
 *
 * ALL outgoing messages flow through here. No feature module
 * ever calls TransportRouter, Tor, Nearby, or Relay directly.
 *
 * Responsibilities:
 * - Durable outbox with crash-safe persistence
 * - Retry with exponential backoff
 * - ACK/READ tracking
 * - Deduplication
 * - Ordering guarantees
 * - Offline recovery
 * - Transport failover
 *
 * Architecture rule: Feature → TorXAgent → TransportRouter
 * Never: Feature → Transport directly
 */
class TorXAgent(
    private val transportRouter: TransportRouter,
    private val outboxStore: OutboxStore,
    private val processedStore: ProcessedEnvelopeStore
) {
    companion object {
        private const val TAG = "TorXAgent"
        private const val MAX_RETRY_ATTEMPTS = 50
        private const val BASE_RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRY_DELAY_MS = 300_000L // 5 minutes
        private const val OUTBOX_POLL_INTERVAL_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendSignal = Channel<Unit>(Channel.CONFLATED)

    /** Observable delivery status updates */
    private val _deliveryUpdates = MutableSharedFlow<DeliveryUpdate>(extraBufferCapacity = 64)
    val deliveryUpdates: SharedFlow<DeliveryUpdate> = _deliveryUpdates

    /** Callback for incoming messages after decryption */
    var onMessageReceived: ((IncomingMessage) -> Unit)? = null

    private var processingJob: Job? = null

    /**
     * Start the agent. Resumes any pending deliveries from the outbox.
     */
    fun start() {
        Log.i(TAG, "Starting TorXAgent")
        processingJob = scope.launch {
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
     *
     * The delivery item must already be encrypted and persisted
     * atomically with the ratchet state update BEFORE calling this.
     */
    suspend fun enqueue(item: DeliveryItem) {
        Log.d(TAG, "Enqueuing delivery ${item.deliveryId} for message ${item.logicalMessageId}")
        outboxStore.insert(item)
        emitUpdate(item.logicalMessageId, DeliveryStatus.QUEUED)
        sendSignal.trySend(Unit)
    }

    /**
     * Handle an incoming transport envelope.
     *
     * Flow:
     * 1. Check for duplicate envelope ID
     * 2. Delegate decryption to caller
     * 3. Caller persists message
     * 4. Mark envelope as processed
     * 5. Only THEN send ACK
     */
    suspend fun handleIncoming(
        envelope: OpaqueTransportEnvelope,
        decrypt: suspend (OpaqueTransportEnvelope) -> IncomingMessage?
    ) {
        // Deduplicate
        if (processedStore.isProcessed(envelope.envelopeId)) {
            Log.d(TAG, "Duplicate envelope ${envelope.envelopeId}, re-sending ACK")
            sendAck(envelope)
            return
        }

        // Decrypt
        val message = try {
            decrypt(envelope)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt envelope ${envelope.envelopeId}", e)
            return
        }

        if (message == null) {
            Log.w(TAG, "Decryption returned null for envelope ${envelope.envelopeId}")
            return
        }

        // Check logical message deduplication
        if (processedStore.isMessageProcessed(message.logicalMessageId)) {
            Log.d(TAG, "Duplicate logical message ${message.logicalMessageId}, re-sending ACK")
            processedStore.markProcessed(ProcessedEnvelope(
                envelopeId = envelope.envelopeId,
                logicalMessageId = message.logicalMessageId
            ))
            sendAck(envelope)
            return
        }

        // Deliver to feature layer (caller must persist before returning)
        onMessageReceived?.invoke(message)

        // Mark as processed AFTER successful persistence
        processedStore.markProcessed(ProcessedEnvelope(
            envelopeId = envelope.envelopeId,
            logicalMessageId = message.logicalMessageId
        ))

        // NOW send ACK — only after DB commit
        sendAck(envelope)

        Log.d(TAG, "Processed incoming message ${message.logicalMessageId}")
    }

    /**
     * Handle a delivery ACK from remote.
     */
    suspend fun handleAck(logicalMessageId: String) {
        Log.d(TAG, "ACK received for message $logicalMessageId")
        outboxStore.removeByMessageId(logicalMessageId)
        emitUpdate(logicalMessageId, DeliveryStatus.DELIVERED)
    }

    /**
     * Handle a read receipt from remote.
     */
    suspend fun handleReadReceipt(logicalMessageId: String) {
        Log.d(TAG, "READ received for message $logicalMessageId")
        emitUpdate(logicalMessageId, DeliveryStatus.READ)
    }

    /**
     * Main outbox processing loop.
     * Wakes on signal or periodic interval.
     */
    private suspend fun processOutbox() {
        while (currentCoroutineContext().isActive) {
            try {
                val pending = outboxStore.getPendingItems()

                if (pending.isNotEmpty()) {
                    Log.d(TAG, "Processing ${pending.size} pending deliveries")

                    for (item in pending) {
                        if (!currentCoroutineContext().isActive) break
                        processDeliveryItem(item)
                    }
                }

                // Wait for signal or timeout
                withTimeoutOrNull(OUTBOX_POLL_INTERVAL_MS) {
                    sendSignal.receive()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in outbox processing loop", e)
                delay(OUTBOX_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Attempt to deliver a single outbox item.
     */
    private suspend fun processDeliveryItem(item: DeliveryItem) {
        if (item.attemptCount >= MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Delivery ${item.deliveryId} exceeded max retries, marking FAILED")
            outboxStore.updateStatus(item.deliveryId, DeliveryStatus.FAILED)
            emitUpdate(item.logicalMessageId, DeliveryStatus.FAILED)
            return
        }

        if (item.nextAttemptAt > System.currentTimeMillis()) {
            return // Not yet time to retry
        }

        // Update status
        outboxStore.updateStatus(item.deliveryId, DeliveryStatus.TRANSMITTING)
        emitUpdate(item.logicalMessageId, DeliveryStatus.TRANSMITTING)

        // Build transport envelope
        val envelope = OpaqueTransportEnvelope(
            queueAddress = item.queueAddress,
            envelopeId = item.deliveryId,
            opaqueCiphertext = item.ciphertext,
            queueAuthenticator = item.queueAuthenticator
        )

        // Send via transport router — it picks the best available route
        val result = transportRouter.send(envelope, item.connectionId)

        when (result) {
            is TransportResult.Success -> {
                Log.d(TAG, "Transport accepted delivery ${item.deliveryId}")
                outboxStore.updateStatus(item.deliveryId, DeliveryStatus.TRANSPORT_ACCEPTED)
                emitUpdate(item.logicalMessageId, DeliveryStatus.TRANSPORT_ACCEPTED)
            }

            is TransportResult.Failure -> {
                val nextDelay = calculateBackoff(item.attemptCount)
                Log.w(TAG, "Delivery ${item.deliveryId} failed (attempt ${item.attemptCount + 1}), " +
                        "retry in ${nextDelay}ms: ${result.reason}")
                outboxStore.updateRetry(
                    deliveryId = item.deliveryId,
                    attemptCount = item.attemptCount + 1,
                    nextAttemptAt = System.currentTimeMillis() + nextDelay
                )
                emitUpdate(item.logicalMessageId, DeliveryStatus.RETRY_WAIT)
            }
        }
    }

    private suspend fun sendAck(envelope: OpaqueTransportEnvelope) {
        // ACK is sent best-effort — if it fails, remote will retry
        // and we'll deduplicate
        try {
            transportRouter.sendAck(envelope.queueAddress, envelope.envelopeId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send ACK for ${envelope.envelopeId}", e)
        }
    }

    private fun calculateBackoff(attemptCount: Int): Long {
        val delay = BASE_RETRY_DELAY_MS * (1L shl minOf(attemptCount, 10))
        return minOf(delay, MAX_RETRY_DELAY_MS)
    }

    private suspend fun emitUpdate(messageId: String, status: DeliveryStatus) {
        _deliveryUpdates.emit(DeliveryUpdate(messageId, status))
    }
}

/**
 * Delivery status update event.
 */
data class DeliveryUpdate(
    val logicalMessageId: String,
    val status: DeliveryStatus
)

/**
 * Incoming message after decryption.
 */
data class IncomingMessage(
    val logicalMessageId: String,
    val conversationId: String,
    val senderIdentity: String,
    val messageType: String,
    val payload: ByteArray,
    val timestamp: Long,
    val replyToMessageId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingMessage) return false
        return logicalMessageId == other.logicalMessageId
    }

    override fun hashCode(): Int = logicalMessageId.hashCode()
}

/**
 * Interface for durable outbox storage.
 */
interface OutboxStore {
    suspend fun insert(item: DeliveryItem)
    suspend fun getPendingItems(): List<DeliveryItem>
    suspend fun updateStatus(deliveryId: String, status: DeliveryStatus)
    suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long)
    suspend fun removeByMessageId(logicalMessageId: String)
}

/**
 * Interface for processed envelope tracking (deduplication).
 */
interface ProcessedEnvelopeStore {
    suspend fun isProcessed(envelopeId: String): Boolean
    suspend fun isMessageProcessed(logicalMessageId: String): Boolean
    suspend fun markProcessed(record: ProcessedEnvelope)
}
