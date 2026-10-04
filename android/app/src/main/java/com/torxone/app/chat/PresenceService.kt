package com.torxone.app.chat

import android.util.Log
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryPriority
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.PresenceState
import com.torxone.app.protocol.PresenceUpdate
import com.torxone.app.protocol.ProtocolCodec
import com.torxone.app.protocol.SecureEnvelope
import com.torxone.app.profile.AppSettingsRepository
import com.torxone.app.transport.nearby.DirectRouteTable
import com.torxone.app.transport.nearby.RouteState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * PresenceService — Pairwise Presence, Last Seen, and Typing Coordinator.
 *
 * Invariants:
 * - Pairwise presence only (zero central presence server or global broadcast)
 * - Ephemeral delivery for typing and presence (never clogs durable outbox)
 * - Typing indicators auto-expire via safety timers if remote disconnects
 * - Seamless integration with DirectRouteTable for immediate Nearby liveness
 */
class PresenceService(
    private val connectionManager: ConnectionManager,
    private val sessionCrypto: SessionCrypto,
    private val agent: TorXAgent,
    private val directRouteTable: DirectRouteTable,
    private val localIdentityIdProvider: () -> String?,
    private val appSettingsRepository: AppSettingsRepository? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val contactDao: com.torxone.app.data.dao.ContactDao? = null,
    private val remoteIdentityProvider: suspend (String) -> String? = { null },
    private val loadLastSeen: suspend (String) -> Long? = { null },
    private val saveLastSeen: suspend (String, Long?) -> Unit = { _, _ -> },
    private val relationshipsProvider: suspend () -> List<String> = {
        contactDao?.getAll()?.map { it.relationshipId }?.distinct().orEmpty()
    },
    private val presenceSender: (suspend (String, PresenceState) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PresenceService"
        const val TYPING_SAFETY_TIMEOUT_MS = 7_000L
        const val PRESENCE_TTL_MS = 45_000L
        const val TYPING_TTL_MS = 10_000L
    }

    private val presenceStates = ConcurrentHashMap<String, MutableStateFlow<PeerPresenceState>>()
    private val typingTimeoutJobs = ConcurrentHashMap<String, Job>()
    private val lastPresenceUpdates = ConcurrentHashMap<String, Long>()

    init {
        // Register as a listener to route transitions in DirectRouteTable
        directRouteTable.addRouteListener { relationshipId, state, lastSeen ->
            handleRouteStateChanged(relationshipId, state, lastSeen)
        }
    }

    fun observePresence(relationshipId: String): StateFlow<PeerPresenceState> {
        return getOrCreateState(relationshipId).asStateFlow()
    }

    fun getPresence(relationshipId: String): PeerPresenceState {
        return getOrCreateState(relationshipId).value
    }

    private fun getOrCreateState(relationshipId: String): MutableStateFlow<PeerPresenceState> {
        return presenceStates.computeIfAbsent(relationshipId) {
            val flow = MutableStateFlow(
                PeerPresenceState(
                    relationshipId = relationshipId,
                    status = PresenceStatus.UNKNOWN,
                    lastSeenAt = null
                )
            )
            coroutineScope.launch {
                runCatching { loadLastSeen(relationshipId) }.getOrNull()?.takeIf {
                    it > 0 && it <= System.currentTimeMillis()
                }?.let { saved -> flow.update { state ->
                    if (lastPresenceUpdates.containsKey(relationshipId) || (state.lastSeenAt ?: 0) >= saved) state else state.copy(lastSeenAt = saved,
                        status = if (state.status == PresenceStatus.UNKNOWN) PresenceStatus.OFFLINE else state.status)
                } }
            }
            flow
        }
    }

    /**
     * Route state changed in DirectRouteTable (Nearby pipe).
     */
    fun handleRouteStateChanged(relationshipId: String, state: RouteState, lastSeen: Long) {
        val stateFlow = getOrCreateState(relationshipId)
        when (state) {
            RouteState.READY -> {
                // A background transport connection does not prove foreground app activity.
            }
            RouteState.STALE, RouteState.DISCONNECTED -> {
                cancelTypingSafetyTimer(relationshipId)
                stateFlow.update {
                    it.copy(
                        status = PresenceStatus.OFFLINE,
                        isTyping = false,
                        typingExpiresAt = null
                    )
                }
            }
            else -> {}
        }
    }

    /**
     * Handle incoming decrypted PRESENCE_UPDATE protocol packet.
     */
    @Synchronized fun onPresenceUpdateReceived(relationshipId: String, update: PresenceUpdate) {
        Log.d(TAG, "[RX PRESENCE]")
        val stateFlow = getOrCreateState(relationshipId)
        val receivedAt = System.currentTimeMillis()
        // Do not let a delayed/offline replay or a future peer clock fabricate activity.
        if (update.timestamp <= 0L || update.timestamp > receivedAt + 60_000L ||
            update.timestamp < (lastPresenceUpdates[relationshipId] ?: stateFlow.value.lastSeenAt ?: 0L)) return
        lastPresenceUpdates[relationshipId] = update.timestamp
        val confirmedSeen = minOf(update.timestamp, receivedAt).takeIf { update.lastSeenVisible }
        presenceTimeoutJobs.remove(relationshipId)?.cancel()
        when (update.state) {
            PresenceState.ONLINE -> {
                stateFlow.update { it.copy(status = PresenceStatus.ONLINE, lastSeenAt = confirmedSeen) }
                presenceTimeoutJobs[relationshipId] = coroutineScope.launch {
                    delay(PRESENCE_TTL_MS)
                    stateFlow.update { it.copy(status = PresenceStatus.OFFLINE, isTyping = false, typingExpiresAt = null) }
                }
            }
            PresenceState.OFFLINE -> {
                cancelTypingSafetyTimer(relationshipId)
                stateFlow.update {
                    it.copy(
                        status = PresenceStatus.OFFLINE,
                        lastSeenAt = confirmedSeen,
                        isTyping = false,
                        typingExpiresAt = null
                    )
                }
            }
        }
        persistConfirmedSeen(relationshipId, stateFlow)
    }

    private fun persistConfirmedSeen(relationshipId: String, stateFlow: MutableStateFlow<PeerPresenceState>) {
        coroutineScope.launch {
            persistenceMutex.withLock {
                runCatching { saveLastSeen(relationshipId, stateFlow.value.lastSeenAt) }
            }
        }
    }

    private val presenceTimeoutJobs = ConcurrentHashMap<String, Job>()
    private val persistenceMutex = kotlinx.coroutines.sync.Mutex()
    private var foregroundJob: Job? = null
    private var foreground = false

    /** Announces actual unlocked foreground use, independently of opening a chat. */
    @Synchronized fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        foregroundJob?.cancel()
        foregroundJob = coroutineScope.launch {
            val sending = mutableMapOf<String, Job>()
            do {
                relationshipsProvider().distinct().forEach { relationship ->
                    if (sending[relationship]?.isActive != true) {
                        val worker = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.LAZY) {
                            try {
                                val state = if (active) PresenceState.ONLINE else PresenceState.OFFLINE
                                presenceSender?.invoke(relationship, state) ?: sendPresenceUpdate(relationship, state)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { Log.w(TAG, "Presence update could not be sent") }
                        }
                        sending[relationship] = worker
                        worker.start()
                    }
                }
                if (active) delay(20_000L)
            } while (active && isActive)
        }
    }

    /**
     * Handle incoming decrypted TYPING_START protocol packet.
     */
    fun onTypingStartReceived(relationshipId: String) {
        Log.d(TAG, "[RX TYPING] Peer started typing")
        val stateFlow = getOrCreateState(relationshipId)
        val now = System.currentTimeMillis()
        val expiresAt = now + TYPING_SAFETY_TIMEOUT_MS

        stateFlow.update {
            it.copy(
                status = PresenceStatus.ONLINE,
                isTyping = true,
                typingExpiresAt = expiresAt
            )
        }

        // Reset safety timeout job
        cancelTypingSafetyTimer(relationshipId)
        typingTimeoutJobs[relationshipId] = coroutineScope.launch {
            delay(TYPING_SAFETY_TIMEOUT_MS)
            Log.d(TAG, "[TYPING TIMEOUT] Auto-clearing typing state")
            stateFlow.update { it.copy(isTyping = false, typingExpiresAt = null) }
        }
    }

    /**
     * Handle incoming decrypted TYPING_STOP protocol packet.
     */
    fun onTypingStopReceived(relationshipId: String) {
        Log.d(TAG, "[RX TYPING] Peer stopped typing")
        cancelTypingSafetyTimer(relationshipId)
        val stateFlow = getOrCreateState(relationshipId)
        stateFlow.update { it.copy(isTyping = false, typingExpiresAt = null) }
    }

    private fun cancelTypingSafetyTimer(relationshipId: String) {
        typingTimeoutJobs.remove(relationshipId)?.cancel()
    }

    /**
     * Send encrypted ephemeral PRESENCE_UPDATE.
     */
    suspend fun sendPresenceUpdate(relationshipId: String, state: PresenceState) {
        val onlineVisible = appSettingsRepository?.onlineVisible?.first() ?: true
        val lastSeenVisible = appSettingsRepository?.lastSeenVisible?.first() ?: true
        if (state == PresenceState.ONLINE && !onlineVisible) {
            Log.d(TAG, "Online status disabled in settings; suppressing ONLINE update")
            return
        }
        val payload = PresenceUpdate(state = state, lastSeenVisible = lastSeenVisible).toByteArray()
        sendEphemeralEnvelope(
            relationshipId = relationshipId,
            conversationId = "",
            messageType = MessageType.PRESENCE_UPDATE,
            payload = payload,
            ttlMs = PRESENCE_TTL_MS
        )
    }

    /**
     * Send encrypted ephemeral TYPING_START.
     */
    suspend fun sendTypingStart(relationshipId: String, conversationId: String) {
        sendEphemeralEnvelope(
            relationshipId = relationshipId,
            conversationId = conversationId,
            messageType = MessageType.TYPING_START,
            payload = ByteArray(0),
            ttlMs = TYPING_TTL_MS
        )
    }

    /**
     * Send encrypted ephemeral TYPING_STOP.
     */
    suspend fun sendTypingStop(relationshipId: String, conversationId: String) {
        sendEphemeralEnvelope(
            relationshipId = relationshipId,
            conversationId = conversationId,
            messageType = MessageType.TYPING_STOP,
            payload = ByteArray(0),
            ttlMs = TYPING_TTL_MS
        )
    }

    private suspend fun sendEphemeralEnvelope(
        relationshipId: String,
        conversationId: String,
        messageType: MessageType,
        payload: ByteArray,
        ttlMs: Long
    ) {
        try {
            val connection = connectionManager.getConnectionByRelationship(relationshipId) ?: return
            val localId = localIdentityIdProvider() ?: return
            val recipient = contactDao?.getByRelationshipId(relationshipId)?.remoteIdentityId
                ?: remoteIdentityProvider(relationshipId)
                ?: return
            if (recipient.isBlank() || recipient == com.torxone.app.data.entity.ContactEntity.REMOTE_IDENTITY_UNKNOWN) return

            val envelope = SecureEnvelope(
                protocolVersion = 1,
                logicalMessageId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderIdentity = localId,
                recipientBinding = recipient,
                messageType = messageType,
                payload = payload
            )
            val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
            val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

            val ciphertext = com.torxone.app.crypto.EphemeralCipher.encrypt(connection.sendAuth, envelopeBytes, aad)

            val deliveryItem = DeliveryItem(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = envelope.logicalMessageId,
                conversationId = conversationId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = ciphertext,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED,
                priority = DeliveryPriority.LOW
            )

            agent.sendEphemeral(deliveryItem, ttlMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send ephemeral")
        }
    }
}
