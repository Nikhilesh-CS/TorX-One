package com.torxone.app.agent

import android.util.Log
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.protocol.OpaqueTransportEnvelope
import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import com.torxone.app.transport.TransportRouter
import com.torxone.app.transport.DeliveryDiagnostics
import com.torxone.app.transport.DeliveryTimeouts
import com.torxone.app.transport.TransportType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val isDeliveryPaused: suspend (DeliveryItem) -> Boolean = { false },
    private val maxLiveAttempts: Int = 8,
    private val receiverAckTimeoutMs: Long = DeliveryTimeouts.RECEIVER_ACK_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    // Production reads Room's committed message status here. No receipt snapshot
    // or process-local cache can safely replace this after callbacks are delayed.
    private val committedMessageStatus: suspend (String) -> DeliveryStatus? = { null }
) {
    companion object {
        private const val TAG = "TorXAgent"
        private const val MAX_RETRY_DELAY_MS = 300_000L // 5 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    private val sendSignal = Channel<Unit>(Channel.CONFLATED)

    /** Observable delivery status updates */
    private val _deliveryUpdates = MutableSharedFlow<DeliveryUpdate>(extraBufferCapacity = 64)
    val deliveryUpdates: SharedFlow<DeliveryUpdate> = _deliveryUpdates

    private var processingJob: Job? = null
    private val inflightItems = ConcurrentHashMap.newKeySet<String>()
    // Breaker health and streams are process-local. Unknown acceptance after a
    // restart must never be attributed to Tor without evidence.
    private val acceptedTransports = ConcurrentHashMap<String, TransportType>()
    private class DeliveryAttemptState {
        val mutex = Mutex()
        var acknowledged = false
    }
    // An attempt or recovery owns this identity until finally; IO never holds its lock.
    private val activeDeliveryStates = ConcurrentHashMap<String, DeliveryAttemptState>()
    private class MessagePublicationState {
        val mutex = Mutex()
        var users = 0
    }
    // References include waiters: removing a mutex while another publisher waits
    // would split identity and allow a delayed ACK to overtake READ.
    private val messagePublications = mutableMapOf<String, MessagePublicationState>()
    init { require(maxLiveAttempts > 0 && receiverAckTimeoutMs > 0 && baseRetryDelayMs > 0) }

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
        Log.d(TAG, "[QUEUE]")
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
        val now = clock()
        if (now - item.createdAt > ttlMs) {
            Log.d(TAG, "[EPHEMERAL EXPIRED]")
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
        val destination = destination(item)

        val result = transportRouter.send(destination, rawPayload)
        Log.d(TAG, "[EPHEMERAL SEND]")
        return result
    }

    /**
     * Trigger immediate retry (e.g. when Nearby connects).
     * Resets waiting retry items so they transmit immediately without waiting for backoff timers.
     */
    fun triggerImmediateRetry(conversationId: String? = null, relationshipId: String? = null) {
        scope.launch {
            try {
                recoverStaleOutboxItems(conversationId, relationshipId, resumeWaiting = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Outbox immediate recovery failed")
            }
            sendSignal.trySend(Unit)
        }
    }

    /** A committed authenticated frame is a recovery signal; unauthenticated headers aren't. */
    fun onAuthenticatedPeerActivity(relationshipId: String) {
        scope.launch {
            recoverStaleOutboxItems(relationshipId = relationshipId, resumeWaiting = true, waitingOnly = true)
            wake()
        }
    }

    /**
     * Mark message delivered when valid authenticated ACK arrives.
     */
    suspend fun markDelivered(logicalMessageId: String) {
        Log.i(TAG, "[DELIVERED]")
        outboxStore.getPendingItems().filter { it.logicalMessageId == logicalMessageId }
            .forEach { acceptedTransports.remove(it.deliveryId) }
        outboxStore.removeByMessageId(logicalMessageId)
        emitUpdate(logicalMessageId, DeliveryStatus.DELIVERED)
        wake()
    }

    /**
     * Exact delivery-level acknowledgment (Phase 13).
     * Removes the exact delivery item without accidentally removing pending deliveries for other group recipients.
     */
    suspend fun markDeliveryAcknowledged(deliveryId: String, logicalMessageId: String? = null) {
        val pending = outboxStore.getByDeliveryId(deliveryId)
        outboxStore.removeByDeliveryId(deliveryId)
        onAcknowledgmentCommitted(deliveryId, logicalMessageId, pending?.let(::destination),
            pending?.conversationId, pending?.applicationSequence)
    }

    /** Called only after the receipt/ratchet/outbox transaction committed. No DB mutation. */
    suspend fun onAcknowledgmentCommitted(deliveryId: String, logicalMessageId: String?,
                                         destination: TransportDestination?, conversationId: String? = null,
                                         applicationSequence: Long? = null,
                                         visibleStatus: DeliveryStatus = DeliveryStatus.DELIVERED) {
        val state = activeDeliveryStates[deliveryId]
        if (state != null) {
            state.mutex.withLock {
                state.acknowledged = true
                publishAcknowledgment(deliveryId, logicalMessageId, destination, conversationId, applicationSequence, visibleStatus)
            }
        } else {
            publishAcknowledgment(deliveryId, logicalMessageId, destination, conversationId, applicationSequence, visibleStatus)
        }
    }

    private suspend fun publishAcknowledgment(deliveryId: String, logicalMessageId: String?,
                                             destination: TransportDestination?, conversationId: String?,
                                             applicationSequence: Long?, visibleStatus: DeliveryStatus) {
        acceptedTransports.remove(deliveryId)
        destination?.let {
            transportRouter.onAuthenticatedAck(it)
            DeliveryDiagnostics.event("ack_received", it.relationshipId, conversationId, deliveryId, applicationSequence)
        }
        if (logicalMessageId != null) {
            emitUpdate(logicalMessageId, visibleStatus)
        }
        wake()
    }

    /**
     * Mark message read.
     */
    suspend fun markRead(logicalMessageId: String) {
        emitUpdate(logicalMessageId, DeliveryStatus.READ)
    }

    private suspend fun recoverStaleOutboxItems(conversationId: String? = null, relationshipId: String? = null,
                                              resumeWaiting: Boolean = false, waitingOnly: Boolean = false) {
        try {
            val pending = outboxStore.getPendingItems()
            val now = clock()
            for (item in pending) {
                // A restored terminal sequence must remain a blocker. Network recovery
                // only resumes live/waiting deliveries, never repairs terminal state.
                if (isTerminal(item.status)) continue
                if (item.deliveryId in inflightItems || (conversationId != null && item.conversationId != conversationId)) continue
                if (relationshipId != null && item.relationshipId != relationshipId && relationshipForQueue(item.queueAddress) != relationshipId) continue
                if (waitingOnly && item.status != DeliveryStatus.WAITING_FOR_PEER) continue
                val state = DeliveryAttemptState()
                if (activeDeliveryStates.putIfAbsent(item.deliveryId, state) != null) continue
                try {
                    state.mutex.withLock {
                        val current = outboxStore.getByDeliveryId(item.deliveryId)
                        if (state.acknowledged || current == null || isTerminal(current.status)) return@withLock
                        if (resumeWaiting && (item.status == DeliveryStatus.WAITING_FOR_PEER || item.attemptCount >= maxLiveAttempts)) {
                            transportRouter.resetRelationship(destination(item).relationshipId ?: "bootstrap:${item.queueAddress}")
                            outboxStore.updateRetry(item.deliveryId, 0, now)
                            outboxStore.updateStatus(item.deliveryId, DeliveryStatus.QUEUED)
                            emitUpdate(item.logicalMessageId, DeliveryStatus.QUEUED)
                            return@withLock
                        }
                        if (item.status == DeliveryStatus.TRANSMITTING ||
                            item.status == DeliveryStatus.RETRY_WAIT ||
                            (item.status == DeliveryStatus.TRANSPORT_ACCEPTED && now >= item.nextAttemptAt)
                        ) {
                            if (item.status == DeliveryStatus.TRANSPORT_ACCEPTED)
                                transportRouter.onAckTimeout(destination(item), acceptedTransports.remove(item.deliveryId))
                            outboxStore.updateRetry(item.deliveryId, item.attemptCount, now)
                            outboxStore.updateStatus(item.deliveryId, DeliveryStatus.QUEUED)
                        }
                    }
                } finally {
                    activeDeliveryStates.remove(item.deliveryId, state)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Outbox recovery failed")
        }
    }

    private suspend fun processOutbox() {
        val destinationJobs = mutableMapOf<String, Job>()
        while (currentCoroutineContext().isActive) {
            try {
                val pending = outboxStore.getPendingItems()

                if (pending.isNotEmpty()) {
                    // Group by relationship across rotated queues to preserve protocol ordering,
                    // while deliveries to independent destinations execute concurrently without head-of-line blocking.
                    val groupedByDestination = pending.groupBy { item ->
                        val relationship = item.relationshipId.takeIf(String::isNotBlank) ?: relationshipForQueue(item.queueAddress)
                        if (relationship != null) {
                            "relationship:$relationship"
                        } else {
                            "queue:${item.queueAddress}"
                        }
                    }
                    destinationJobs.entries.removeAll { !it.value.isActive }
                        for ((destinationKey, destinationItems) in groupedByDestination) {
                            if (destinationJobs[destinationKey]?.isActive == true) continue
                            val orderedHead = destinationItems.filter { it.applicationSequence != null }.minByOrNull { it.applicationSequence!! }
                            val eligibleControl = destinationItems.any { it.applicationSequence == null && !isSchedulingBlocked(it.status) && it.nextAttemptAt <= clock() && !isDeliveryPaused(it) }
                            if (!eligibleControl && (orderedHead == null || isSchedulingBlocked(orderedHead.status) || orderedHead.nextAttemptAt > clock() || isDeliveryPaused(orderedHead))) continue
                            val worker = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.LAZY) {
                                DeliveryDiagnostics.event("lane_worker_start", destinationItems.firstOrNull()?.relationshipId)
                                // P0-4: Strict application sequence scheduling
                                // 1. High-priority non-sequenced traffic can jump ahead where protocol-safe
                                val highPriorityNonSequenced = destinationItems
                                    .filter { it.applicationSequence == null && it.priority > DeliveryPriority.NORMAL }
                                    .filter { !isSchedulingBlocked(it.status) && it.nextAttemptAt <= clock() }
                                    .filter { !isDeliveryPaused(it) }
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
                                            scheduleFailure(item)
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

                                val head = sequencedItems.firstOrNull()
                                if (head != null && isActive && !isSchedulingBlocked(head.status) &&
                                    head.nextAttemptAt <= clock() && inflightItems.add(head.deliveryId)) {
                                    try { processDeliveryItem(head) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { scheduleFailure(head) }
                                    finally { inflightItems.remove(head.deliveryId) }
                                }
                                // Never send N+1 from an old snapshot, even when N's socket accepted bytes.
                                // Only exact authenticated ACK removal permits a fresh sweep to select N+1.

                                // 3. Remaining normal or low-priority non-sequenced items
                                val remainingNonSequenced = destinationItems
                                    .filter { it.applicationSequence == null && it.priority <= DeliveryPriority.NORMAL }
                                    .filter { !isSchedulingBlocked(it.status) && it.nextAttemptAt <= clock() }
                                    .filter { !isDeliveryPaused(it) }
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
                                            scheduleFailure(item)
                                        } finally {
                                            inflightItems.remove(item.deliveryId)
                                        }
                                    }
                                }
                            }
                            destinationJobs[destinationKey] = worker
                            // ACK/enqueue signals can arrive while this worker is still active.
                            // Recheck after completion rather than adding a polling interval per message.
                            worker.invokeOnCompletion { error -> if (error == null) sendSignal.trySend(Unit) }
                            worker.start()
                        }
                }

                withTimeoutOrNull(outboxPollIntervalMs) {
                    sendSignal.receive()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Outbox processing failed")
                delay(outboxPollIntervalMs)
            }
        }
    }

    private suspend fun processDeliveryItem(item: DeliveryItem): Boolean {
        val state = DeliveryAttemptState()
        if (activeDeliveryStates.putIfAbsent(item.deliveryId, state) != null) return false
        try {
            return processDeliveryAttempt(item, state)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            scheduleFailure(item, state)
            return false
        } finally {
            activeDeliveryStates.remove(item.deliveryId, state)
        }
    }

    private suspend fun processDeliveryAttempt(item: DeliveryItem, state: DeliveryAttemptState): Boolean {
        state.mutex.withLock {
            val current = outboxStore.getByDeliveryId(item.deliveryId)
            if (state.acknowledged || current == null || isSchedulingBlocked(current.status) || isDeliveryPaused(item) ||
                isSchedulingBlocked(item.status) || item.nextAttemptAt > clock()) return false
            if (item.attemptCount >= maxLiveAttempts) {
                transportRouter.invalidateRelationship(destination(item))
                outboxStore.updateStatus(item.deliveryId, DeliveryStatus.WAITING_FOR_PEER)
                emitUpdate(item.logicalMessageId, DeliveryStatus.WAITING_FOR_PEER)
                DeliveryDiagnostics.event("relationship_degraded", item.relationshipId, item.conversationId,
                    item.deliveryId, item.applicationSequence, state = "WAITING_FOR_PEER", attempt = item.attemptCount)
                return false
            }
            DeliveryDiagnostics.event("outbox_selected", item.relationshipId, item.conversationId, item.deliveryId,
                item.applicationSequence, state = item.status.name, attempt = item.attemptCount + 1)
            if (item.status == DeliveryStatus.TRANSPORT_ACCEPTED)
                transportRouter.onAckTimeout(destination(item), acceptedTransports.remove(item.deliveryId))
            // Persist before IO: process death cannot reset the retry budget or erase this attempt.
            outboxStore.updateRetry(item.deliveryId, item.attemptCount + 1, clock())
            outboxStore.updateStatus(item.deliveryId, DeliveryStatus.TRANSMITTING)
            emitUpdate(item.logicalMessageId, DeliveryStatus.TRANSMITTING)
        }

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
        val destination = destination(item)

        val result = transportRouter.send(destination, rawPayload)

        when (result) {
            is TransportResult.Accepted -> {
                state.mutex.withLock {
                    // ACK side effects share this mutex. Persistence can suspend, but
                    // acceptance can never follow a committed delivery event.
                    val current = outboxStore.getByDeliveryId(item.deliveryId)
                    if (state.acknowledged || current == null || isSchedulingBlocked(current.status)) return true
                    acceptedTransports[item.deliveryId] = result.transportType
                    DeliveryDiagnostics.event("transport_accepted", item.relationshipId, item.conversationId, item.deliveryId,
                        item.applicationSequence, transport = result.transportType, attempt = item.attemptCount + 1)
                    Log.i(TAG, "[ACCEPTED]")
                    if (!item.expectsAck) {
                        acceptedTransports.remove(item.deliveryId)
                        outboxStore.removeByDeliveryId(item.deliveryId)
                        state.acknowledged = true
                        emitUpdate(item.logicalMessageId, DeliveryStatus.DELIVERED)
                    } else {
                        // Schedule next attempt with backoff in case ACK is lost on the wire.
                        val ackTimeout = minOf(receiverAckTimeoutMs * (1L shl item.attemptCount.coerceIn(0, 4)), MAX_RETRY_DELAY_MS)
                        val attempts = item.attemptCount + 1
                        outboxStore.updateRetry(
                            deliveryId = item.deliveryId,
                            attemptCount = attempts,
                            nextAttemptAt = clock() + ackTimeout
                        )
                        outboxStore.updateStatus(item.deliveryId, DeliveryStatus.TRANSPORT_ACCEPTED)
                        emitUpdate(item.logicalMessageId, DeliveryStatus.TRANSPORT_ACCEPTED)
                        DeliveryDiagnostics.event("ack_wait", item.relationshipId, item.conversationId, item.deliveryId,
                            item.applicationSequence, transport = result.transportType, state = "TRANSPORT_ACCEPTED", attempt = attempts)
                    }
                }
                return true
            }

            is TransportResult.Failed -> {
                scheduleFailure(item, state)
                return false
            }
        }
    }

    private fun destination(item: DeliveryItem) = TransportDestination(item.queueAddress,
        relationshipId = item.relationshipId.takeIf(String::isNotBlank) ?: relationshipForQueue(item.queueAddress),
        deliveryId = item.deliveryId, conversationId = item.conversationId, applicationSequence = item.applicationSequence)

    private fun isTerminal(status: DeliveryStatus) =
        status == DeliveryStatus.FAILED || status == DeliveryStatus.EXPIRED

    private fun isSchedulingBlocked(status: DeliveryStatus) =
        status == DeliveryStatus.WAITING_FOR_PEER || isTerminal(status)

    private suspend fun scheduleFailure(item: DeliveryItem, state: DeliveryAttemptState? = activeDeliveryStates[item.deliveryId]) {
        suspend fun persistFailure() {
            val current = outboxStore.getByDeliveryId(item.deliveryId)
            if (state?.acknowledged == true || current == null || isSchedulingBlocked(current.status)) return
            outboxStore.updateRetry(item.deliveryId, item.attemptCount + 1, clock() + calculateBackoff(item.attemptCount))
            emitUpdate(item.logicalMessageId, DeliveryStatus.RETRY_WAIT)
            DeliveryDiagnostics.event("retry_scheduled", item.relationshipId, item.conversationId, item.deliveryId,
                item.applicationSequence, state = "RETRY_WAIT", attempt = item.attemptCount + 1)
        }
        if (state != null) state.mutex.withLock { persistFailure() } else persistFailure()
    }

    private fun calculateBackoff(attempt: Int): Long {
        val multiplier = 1L shl minOf(attempt, 6) // 1, 2, 4, 8, 16, 32, 64
        return minOf(baseRetryDelayMs * multiplier, MAX_RETRY_DELAY_MS)
    }

    private suspend fun emitUpdate(logicalMessageId: String, status: DeliveryStatus) {
        val publication = synchronized(messagePublications) {
            messagePublications.getOrPut(logicalMessageId, ::MessagePublicationState).also { it.users++ }
        }
        try {
            publication.mutex.withLock {
                // All callers publish after their durable transaction. Query under
                // the same publication lock used by READ and ACK so either order
                // finishes at the newest committed receipt state.
                val committed = committedMessageStatus(logicalMessageId)
                val visible = when {
                    committed == DeliveryStatus.READ -> {
                        if (status !in setOf(DeliveryStatus.DELIVERED, DeliveryStatus.READ)) return@withLock
                        DeliveryStatus.READ
                    }
                    committed == DeliveryStatus.DELIVERED && status !in setOf(DeliveryStatus.DELIVERED, DeliveryStatus.READ) -> return@withLock
                    // Group READ is aggregated in Room. A single recipient receipt
                    // must not publish whole-message READ before that aggregation.
                    status == DeliveryStatus.READ && committed != null && committed != DeliveryStatus.READ -> return@withLock
                    else -> status
                }
                _deliveryUpdates.emit(DeliveryUpdate(logicalMessageId, visible))
            }
        } finally {
            synchronized(messagePublications) {
                publication.users--
                if (publication.users == 0) messagePublications.remove(logicalMessageId)
            }
        }
    }

    internal fun activeMessagePublicationCount(): Int = synchronized(messagePublications) { messagePublications.size }
}

data class DeliveryUpdate(
    val logicalMessageId: String,
    val status: DeliveryStatus,
    val timestamp: Long = System.currentTimeMillis()
)

interface OutboxStore {
    suspend fun insert(item: DeliveryItem)
    suspend fun getPendingItems(): List<DeliveryItem>
    suspend fun getByDeliveryId(deliveryId: String): DeliveryItem? =
        getPendingItems().firstOrNull { it.deliveryId == deliveryId }
    suspend fun updateStatus(deliveryId: String, status: DeliveryStatus)
    suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long)
    suspend fun removeByMessageId(logicalMessageId: String)
    suspend fun removeByDeliveryId(deliveryId: String)
}

interface ProcessedEnvelopeStore {
    suspend fun isProcessed(envelopeId: String): Boolean
    suspend fun isMessageProcessed(logicalMessageId: String): Boolean
    suspend fun markProcessed(record: ProcessedEnvelope)
}
