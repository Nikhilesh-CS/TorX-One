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
    private val callManager: CallManager
) {
    companion object {
        private const val TAG = "CallHandler"
    }

    suspend fun handleCallSignal(connection: Connection, envelope: SecureEnvelope) {
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
                else -> Log.w(TAG, "Unexpected message type for CallHandler: ${envelope.messageType}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle call signal ${envelope.messageType}: ${e.message}", e)
            throw e
        }
    }

    private suspend fun handleOffer(connection: Connection, envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeOffer(envelope.payload)
        Log.d(TAG, "[RX] CALL_OFFER call=${payload.callId.take(8)} type=${payload.callType} from=${envelope.senderIdentity.take(8)}")

        callManager.onIncomingOffer(
            callId = payload.callId,
            conversationId = envelope.conversationId,
            relationshipId = connection.relationshipId,
            peerIdentityId = envelope.senderIdentity,
            type = payload.callType,
            sdpOffer = payload.sdpOffer
        )
    }

    private fun handleRinging(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeRinging(envelope.payload)
        Log.d(TAG, "[RX] CALL_RINGING call=${payload.callId.take(8)}")
        callManager.onRemoteRinging(payload.callId)
    }

    private suspend fun handleAnswer(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeAnswer(envelope.payload)
        Log.d(TAG, "[RX] CALL_ANSWER call=${payload.callId.take(8)}")
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
        Log.d(TAG, "[RX] CALL_CONNECTED call=${payload.callId.take(8)}")
        callManager.onRemoteConnected(payload.callId)
    }

    private suspend fun handleEnd(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeEnd(envelope.payload)
        Log.d(TAG, "[RX] CALL_END call=${payload.callId.take(8)} reason=${payload.reason}")
        callManager.onRemoteEnd(payload.callId, payload.reason)
    }

    private suspend fun handleDecline(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeDecline(envelope.payload)
        Log.d(TAG, "[RX] CALL_DECLINE call=${payload.callId.take(8)}")
        callManager.onRemoteDecline(payload.callId)
    }

    private fun handleBusy(envelope: SecureEnvelope) {
        val payload = CallProtocolCodec.decodeBusy(envelope.payload)
        Log.d(TAG, "[RX] CALL_BUSY call=${payload.callId.take(8)}")
        callManager.onRemoteBusy(payload.callId)
    }
}
