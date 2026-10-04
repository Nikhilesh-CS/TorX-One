package com.torxone.app.calls

import android.util.Log
import com.torxone.app.connection.Connection
import com.torxone.app.protocol.SecureEnvelope
import com.torxone.app.protocol.MessageType

/**
 * CallHandler — Routes incoming call signaling from IncomingDispatcher
 * to CallManager.
 *
 * Architecture:
 *   IncomingDispatcher → CallHandler → CallManager
 *
 * This handler:
 * - Decodes call protocol payloads
 * - Associates signals with the correct callId
 * - Delegates ALL state-machine decisions to CallManager
 * - Contains ZERO call state logic itself
 *
 * Rejects signals for wrong/unknown callIds (stale packets from old calls).
 */
class CallHandler(
    private val callManager: CallManager,
    private val contactDao: com.torxone.app.data.dao.ContactDao,
    private val dataHandler: CallHandler? = null,
    private val authorizeDataOffer: suspend (String) -> Boolean = { true }
) {
    companion object {
        private const val TAG = "CallHandler"
    }

    suspend fun handleCallSignal(connection: Connection, envelope: SecureEnvelope) {
        if (dataHandler != null) {
            val isDataOffer = envelope.messageType == MessageType.CALL_OFFER &&
                CallProtocolCodec.decodeOffer(envelope.payload).callType == CallType.DATA
            val dataCallId = dataHandler.callManager.activeCall.value?.callId
            val targetsDataSession = dataCallId != null && java.io.DataInputStream(
                java.io.ByteArrayInputStream(envelope.payload)).use { input -> input.readInt(); input.readUTF() == dataCallId }
            if (isDataOffer || targetsDataSession) {
                dataHandler.handleCallSignal(connection, envelope)
                return
            }
        }
        if (envelope.messageType != MessageType.CALL_OFFER) {
            val session = callManager.activeCall.value ?: return
            if (session.relationshipId != connection.relationshipId || session.peerIdentityId != envelope.senderIdentity) return
        }
        try {
            when (envelope.messageType) {
                MessageType.CALL_OFFER -> handleOffer(connection, envelope)
                MessageType.CALL_RINGING -> handleRinging(envelope)
                MessageType.CALL_ANSWER -> handleAnswer(envelope)
                MessageType.CALL_ICE_CANDIDATE -> handleIceCandidate(envelope)
                MessageType.CALL_CONNECTED -> handleConnected(envelope)
                MessageType.CALL_END -> handleEnd(envelope)
                MessageType.CALL_DECLINE -> handleDecline(envelope)
                MessageType.CALL_BUSY -> handleBusy(envelope)
                else -> Log.w(TAG, "Unexpected message type for CallHandler")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle call signal")
            throw e
        }
    }

    private suspend fun handleOffer(connection: Connection, envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeOffer(envelope.payload)
        if (payload.callType == CallType.DATA && !authorizeDataOffer(connection.relationshipId)) return
        if (payload.callType == CallType.DATA && Regex("^m=(audio|video) ", RegexOption.MULTILINE).containsMatchIn(payload.sdpOffer)) {
            throw IllegalArgumentException("File sessions must not negotiate microphone or camera media")
        }
        val age = System.currentTimeMillis() - payload.createdAt
        if (age > 150_000 || age < -30_000) {
            Log.i(TAG, "Ignored expired call offer")
            return
        }
        val conversationId = contactDao.getByRelationshipId(connection.relationshipId)?.conversationId
            ?: throw IllegalStateException("No authenticated conversation for incoming call")
        Log.d(TAG, "[RX] CALL_OFFER")

        callManager.onIncomingOffer(
            callId = payload.callId,
            conversationId = conversationId,
            relationshipId = connection.relationshipId,
            peerIdentityId = envelope.senderIdentity,
            type = payload.callType,
            sdpOffer = payload.sdpOffer
        )
    }

    private fun handleRinging(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeRinging(envelope.payload)
        Log.d(TAG, "[RX] CALL_RINGING")
        callManager.onRemoteRinging(payload.callId)
    }

    private suspend fun handleAnswer(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeAnswer(envelope.payload)
        if (callManager.activeCall.value?.type == CallType.DATA &&
            Regex("^m=(audio|video) ", RegexOption.MULTILINE).containsMatchIn(payload.sdpAnswer)) {
            throw IllegalArgumentException("File sessions must not negotiate audio or video")
        }
        Log.d(TAG, "[RX] CALL_ANSWER")
        callManager.onRemoteAnswer(payload.callId, payload.sdpAnswer)
    }

    private suspend fun handleIceCandidate(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeIceCandidate(envelope.payload)
        callManager.onRemoteIceCandidate(
            callId = payload.callId,
            sdpMid = payload.sdpMid,
            sdpMLineIndex = payload.sdpMLineIndex,
            candidate = payload.candidate
        )
    }

    private fun handleConnected(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeConnected(envelope.payload)
        Log.d(TAG, "[RX] CALL_CONNECTED")
        callManager.onRemoteConnected(payload.callId)
    }

    private suspend fun handleEnd(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeEnd(envelope.payload)
        Log.d(TAG, "[RX] CALL_END")
        callManager.onRemoteEnd(payload.callId, payload.reason)
    }

    private suspend fun handleDecline(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeDecline(envelope.payload)
        Log.d(TAG, "[RX] CALL_DECLINE")
        callManager.onRemoteDecline(payload.callId)
    }

    private fun handleBusy(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeBusy(envelope.payload)
        Log.d(TAG, "[RX] CALL_BUSY")
        callManager.onRemoteBusy(payload.callId)
    }
}
