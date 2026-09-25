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
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "PresenceService"
        const val TYPING_SAFETY_TIMEOUT_MS = 7_000L
        const val PRESENCE_TTL_MS = 45_000L
        const val TYPING_TTL_MS = 10_000L
    }

    private val presenceStates = ConcurrentHashMap<String, MutableStateFlow<PeerPresenceState>>()
    private val typingTimeoutJobs = ConcurrentHashMap<String, Job>()

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
            val isReady = directRouteTable.isReady(relationshipId)
            val initialStatus = if (isReady) PresenceStatus.ONLINE else PresenceStatus.OFFLINE
            MutableStateFlow(
                PeerPresenceState(
                    relationshipId = relationshipId,
                    status = initialStatus,
                    lastSeenAt = if (!isReady) System.currentTimeMillis() else null
                )
            )
        }
    }

    /**
     * Route state changed in DirectRouteTable (Nearby pipe).
     */
    fun handleRouteStateChanged(relationshipId: String, state: RouteState, lastSeen: Long) {
        val stateFlow = getOrCreateState(relationshipId)
        val now = System.currentTimeMillis()
        when (state) {
            RouteState.READY -> {
                Log.d(TAG, "[PRESENCE] Peer $relationshipId is now ONLINE")
                stateFlow.update { it.copy(status = PresenceStatus.ONLINE, isTyping = false) }
            }
            RouteState.STALE, RouteState.DISCONNECTED -> {
                Log.d(TAG, "[PRESENCE] Peer $relationshipId is now OFFLINE (lastSeen=$now)")
                cancelTypingSafetyTimer(relationshipId)
                stateFlow.update {
                    it.copy(
                        status = PresenceStatus.OFFLINE,
                        lastSeenAt = now,
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
    fun onPresenceUpdateReceived(relationshipId: String, update: PresenceUpdate) {
        Log.d(TAG, "[RX PRESENCE] rel=${relationshipId.take(8)} state=${update.state}")
        val stateFlow = getOrCreateState(relationshipId)
        when (update.state) {
            PresenceState.ONLINE -> {
                stateFlow.update { it.copy(status = PresenceStatus.ONLINE) }
            }
            PresenceState.OFFLINE -> {
                cancelTypingSafetyTimer(relationshipId)
                stateFlow.update {
                    it.copy(
                        status = PresenceStatus.OFFLINE,
                        lastSeenAt = update.timestamp,
                        isTyping = false
                    )
                }
            }
        }
    }

    /**
     * Handle incoming decrypted TYPING_START protocol packet.
     */
    fun onTypingStartReceived(relationshipId: String) {
        Log.d(TAG, "[RX TYPING] Peer ${relationshipId.take(8)} started typing")
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
            Log.d(TAG, "[TYPING TIMEOUT] Auto-clearing typing state for $relationshipId")
            stateFlow.update { it.copy(isTyping = false, typingExpiresAt = null) }
        }
    }

    /**
     * Handle incoming decrypted TYPING_STOP protocol packet.
     */
    fun onTypingStopReceived(relationshipId: String) {
        Log.d(TAG, "[RX TYPING] Peer ${relationshipId.take(8)} stopped typing")
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
            Log.d(TAG, "Online status disabled in settings; suppressing ONLINE update to $relationshipId")
            return
        }
        if (state == PresenceState.OFFLINE && !lastSeenVisible) {
            Log.d(TAG, "Last seen status disabled in settings; suppressing OFFLINE update to $relationshipId")
            return
        }

        val payload = PresenceUpdate(state = state).toByteArray()
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
            val localId = localIdentityIdProvider() ?: ""

            val envelope = SecureEnvelope(
                protocolVersion = 1,
                logicalMessageId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderIdentity = localId,
                recipientBinding = "",
                messageType = messageType,
                payload = payload
            )
            val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
            val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

            val encrypted = sessionCrypto.encrypt(relationshipId, envelopeBytes, aad)
            val ciphertext = encrypted.serialize()

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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send ephemeral $messageType: ${e.message}")
        }
    }
}
