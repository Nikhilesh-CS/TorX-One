package com.torxone.app.calls

import android.util.Log
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryPriority
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.OutboxDao
import com.torxone.app.data.entity.OutboxEntity
import com.torxone.app.protocol.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentHashMap

/**
 * CallService — Encrypts and sends call signaling through the existing
 * pairwise Double Ratchet sessions.
 *
 * Architecture:
 *   CallService → SecureEnvelope(CALL_*) → SessionCrypto → TorXAgent HIGH priority → TransportRouter
 *
 * All call signaling is:
 * - Sequence-exempt (ephemeral control messages)
 * - HIGH priority (beats media transfers and normal chat backlog)
 * - callId-scoped (old packets cannot affect new calls)
 * - Recipient-bound (via SecureEnvelope.recipientBinding)
 *
 * Call signaling is NEVER persisted as MessageEntity.
 */
open class CallService(
    private val sessionCrypto: SessionCrypto,
    private val connectionManager: ConnectionManager,
    private val agent: TorXAgent,
    private val conversationDao: ConversationDao,
    private val callHistoryDao: CallHistoryDao,
    private val localIdentityIdProvider: () -> String?,
    private val relationshipSendCoordinator: com.torxone.app.connection.RelationshipSendCoordinator,
    private val outboxDao: OutboxDao
) : CallSignaling {
    companion object {
        private const val TAG = "CallService"
    }

    private val offerIds = ConcurrentHashMap<String, String>()
    private val deliveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var acceptanceObserver: Job? = null

    override fun observeOfferAcceptance(listener: (String) -> Unit) {
        acceptanceObserver?.cancel()
        acceptanceObserver = deliveryScope.launch(start = CoroutineStart.UNDISPATCHED) {
            agent.deliveryUpdates.collect { update ->
                if (update.status in setOf(DeliveryStatus.TRANSPORT_ACCEPTED, DeliveryStatus.DELIVERED)) {
                    offerIds.remove(update.logicalMessageId)?.let(listener)
                } else if (update.status in setOf(DeliveryStatus.FAILED, DeliveryStatus.EXPIRED)) {
                    offerIds.remove(update.logicalMessageId)
                }
            }
        }
    }

    // ─── Outgoing Signaling ──────────────────────────────────────────────

    override suspend fun sendCallOffer(session: CallSession, sdpOffer: String) {
        val payload = CallOfferPayload(
            callId = session.callId,
            callType = session.type,
            sdpOffer = sdpOffer,
            createdAt = System.currentTimeMillis()
        )
        sendSignal(
            session = session,
            messageType = MessageType.CALL_OFFER,
            payload = CallProtocolCodec.encodeOffer(payload)
        )
        Log.d(TAG, "[SEND] CALL_OFFER")
    }

    override suspend fun sendRinging(session: CallSession) {
        val payload = CallRingingPayload(callId = session.callId)
        sendSignal(
            session = session,
            messageType = MessageType.CALL_RINGING,
            payload = CallProtocolCodec.encodeRinging(payload)
        )
        Log.d(TAG, "[SEND] CALL_RINGING")
    }

    override suspend fun sendCallAnswer(session: CallSession, sdpAnswer: String) {
        val payload = CallAnswerPayload(
            callId = session.callId,
            sdpAnswer = sdpAnswer
        )
        sendSignal(
            session = session,
            messageType = MessageType.CALL_ANSWER,
            payload = CallProtocolCodec.encodeAnswer(payload)
        )
        Log.d(TAG, "[SEND] CALL_ANSWER")
    }

    override suspend fun sendIceCandidate(session: CallSession, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val payload = IceCandidatePayload(
            callId = session.callId,
            sdpMid = sdpMid,
            sdpMLineIndex = sdpMLineIndex,
            candidate = candidate
        )
        sendSignal(
            session = session,
            messageType = MessageType.CALL_ICE_CANDIDATE,
            payload = CallProtocolCodec.encodeIceCandidate(payload)
        )
    }

    override suspend fun sendConnected(session: CallSession) {
        val payload = CallConnectedPayload(
            callId = session.callId,
            connectedAt = System.currentTimeMillis()
        )
        sendSignal(
            session = session,
            messageType = MessageType.CALL_CONNECTED,
            payload = CallProtocolCodec.encodeConnected(payload)
        )
        Log.d(TAG, "[SEND] CALL_CONNECTED")
    }

    override suspend fun sendEnd(session: CallSession, reason: CallEndReason) {
        val durationMs = session.connectedAt?.let { System.currentTimeMillis() - it } ?: 0L
        val payload = CallEndPayload(
            callId = session.callId,
            reason = reason,
            durationMs = durationMs
        )
        sendSignal(
            session = session,
            messageType = MessageType.CALL_END,
            payload = CallProtocolCodec.encodeEnd(payload)
        )
        Log.d(TAG, "[SEND] CALL_END")
    }

    override suspend fun sendDecline(session: CallSession) {
        val payload = CallDeclinePayload(callId = session.callId)
        sendSignal(
            session = session,
            messageType = MessageType.CALL_DECLINE,
            payload = CallProtocolCodec.encodeDecline(payload)
        )
        Log.d(TAG, "[SEND] CALL_DECLINE")
    }

    override suspend fun sendBusy(callId: String, conversationId: String, relationshipId: String, peerIdentityId: String) {
        val payload = CallBusyPayload(callId = callId)
        val session = CallSession(
            callId = callId,
            conversationId = conversationId,
            relationshipId = relationshipId,
            peerIdentityId = peerIdentityId,
            direction = CallDirection.INCOMING,
            type = CallType.VOICE,
            state = CallState.BUSY,
            startedAt = System.currentTimeMillis()
        )
        sendSignal(
            session = session,
            messageType = MessageType.CALL_BUSY,
            payload = CallProtocolCodec.encodeBusy(payload)
        )
        Log.d(TAG, "[SEND] CALL_BUSY")
    }

    // ─── Call History ────────────────────────────────────────────────────

    override suspend fun persistCallHistory(session: CallSession, outcome: CallOutcome, durationMs: Long?) {
        if (session.type == CallType.DATA) return
        callHistoryDao.upsert(
            CallHistoryEntity(
                callId = session.callId,
                conversationId = session.conversationId,
                peerIdentityId = session.peerIdentityId,
                direction = session.direction.name,
                type = session.type.name,
                outcome = outcome.name,
                startedAt = session.startedAt,
                connectedAt = session.connectedAt,
                endedAt = session.endedAt ?: System.currentTimeMillis(),
                durationMs = durationMs
            )
        )
        Log.d(TAG, "[HISTORY] Call record persisted")
    }

    // ─── Core Signaling Plumbing ─────────────────────────────────────────

    private suspend fun sendSignal(session: CallSession, messageType: MessageType, payload: ByteArray) {
        val localIdentityId = localIdentityIdProvider()
            ?: throw IllegalStateException("Local identity not initialized")

        val connection = connectionManager.getConnectionByRelationship(session.relationshipId)
            ?: throw IllegalStateException("No active connection for relationship ${session.relationshipId}")

        val envelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = UUID.randomUUID().toString(),
            conversationId = session.conversationId,
            senderIdentity = localIdentityId,
            recipientBinding = session.peerIdentityId,
            messageType = messageType,
            payload = payload
        )

        if (messageType == MessageType.CALL_OFFER) {
            offerIds.entries.removeIf { it.value == session.callId }
            // One live call; discard mappings from abandoned calls.
            offerIds.clear()
            offerIds[envelope.logicalMessageId] = session.callId
        }

        relationshipSendCoordinator.sendDurableUnsequenced(
            relationshipId = connection.relationshipId,
            connection = connection,
            envelope = envelope
        ) { committedEnvelope, ciphertext ->
            val now = System.currentTimeMillis()
            outboxDao.insert(OutboxEntity(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = committedEnvelope.logicalMessageId,
                conversationId = session.conversationId,
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = ciphertext,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED.name,
                priority = DeliveryPriority.HIGH,
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now,
                expectsAck = true,
                relationshipId = connection.relationshipId
            ))
        }
    }
}
