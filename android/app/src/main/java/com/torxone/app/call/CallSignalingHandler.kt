package com.torxone.app.call

import com.torxone.app.network.MeshProtocol
import com.torxone.app.network.MessageRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CallSignal(
    val callId: String,
    val mode: CallMode,
    val sdp: String? = null,
    val sdpType: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val reason: String? = null
)

class CallSignalingHandler(
    private val messageRouter: MessageRouter
) {
    var activeSession: CallTransportSession? = null

    companion object {
        /** Maximum raw signal payload size (bytes). Prevents memory exhaustion from malicious signals. */
        const val MAX_SIGNAL_SIZE = 65_536       // 64 KB
        /** Maximum SDP content size (bytes). */
        const val MAX_SDP_SIZE = 65_536           // 64 KB
        /** Maximum ICE candidate string size (bytes). */
        const val MAX_ICE_CANDIDATE_SIZE = 4_096  // 4 KB
    }

    suspend fun sendOffer(peerKey: String, callId: String, mode: CallMode, description: AstraSessionDescription) {
        sendSdp(peerKey, callId, mode, description, MeshProtocol.TYPE_CALL_OFFER)
    }

    suspend fun sendAnswer(peerKey: String, callId: String, mode: CallMode, description: AstraSessionDescription) {
        sendSdp(peerKey, callId, mode, description, MeshProtocol.TYPE_CALL_ANSWER)
    }

    suspend fun sendIceCandidate(peerKey: String, callId: String, mode: CallMode, candidate: AstraIceCandidate) {
        val payload = JSONObject()
            .put("callId", callId)
            .put("mode", mode.name)
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
            .toString()
        sendRawCallSignal(peerKey, payload, MeshProtocol.TYPE_ICE_CANDIDATE)
    }

    suspend fun sendEnd(peerKey: String, callId: String, mode: CallMode, reason: String) {
        val payload = JSONObject()
            .put("callId", callId)
            .put("mode", mode.name)
            .put("reason", reason.take(160))
            .toString()
        sendRawCallSignal(peerKey, payload, MeshProtocol.TYPE_CALL_END)
    }

    /**
     * Parses and validates an incoming call signal.
     * Enforces payload size limits and schema requirements.
     * @throws SecurityException if the signal is malformed, oversized, or missing required fields.
     */
    fun parse(raw: String): CallSignal {
        // Enforce total payload size limit
        if (raw.length > MAX_SIGNAL_SIZE) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_OVERSIZED_PAYLOAD,
                callId = null,
                peerKey = null,
                detail = "size=${raw.length} max=$MAX_SIGNAL_SIZE"
            )
            throw SecurityException("Signal payload exceeds maximum size: ${raw.length} > $MAX_SIGNAL_SIZE")
        }

        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_MALFORMED_SIGNAL,
                callId = null,
                peerKey = null,
                detail = "invalid JSON"
            )
            throw SecurityException("Malformed signal: invalid JSON")
        }

        // callId is mandatory
        val callId = json.optString("callId").takeIf { it.isNotBlank() }
            ?: throw SecurityException("Missing required field: callId").also {
                CallSecurityLogger.logSecurityEvent(
                    CallSecurityLogger.EVENT_MALFORMED_SIGNAL, null, null, "missing callId"
                )
            }

        // Validate SDP size if present
        val sdp = json.optString("sdp").takeIf { it.isNotBlank() }
        if (sdp != null && sdp.length > MAX_SDP_SIZE) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_OVERSIZED_PAYLOAD,
                callId = callId,
                peerKey = null,
                detail = "SDP size=${sdp.length} max=$MAX_SDP_SIZE"
            )
            throw SecurityException("SDP exceeds maximum size: ${sdp.length} > $MAX_SDP_SIZE")
        }

        // Validate ICE candidate size if present
        val candidate = json.optString("candidate").takeIf { it.isNotBlank() }
        if (candidate != null && candidate.length > MAX_ICE_CANDIDATE_SIZE) {
            CallSecurityLogger.logSecurityEvent(
                CallSecurityLogger.EVENT_OVERSIZED_PAYLOAD,
                callId = callId,
                peerKey = null,
                detail = "ICE candidate size=${candidate.length} max=$MAX_ICE_CANDIDATE_SIZE"
            )
            throw SecurityException("ICE candidate exceeds maximum size: ${candidate.length} > $MAX_ICE_CANDIDATE_SIZE")
        }

        return CallSignal(
            callId = callId,
            mode = runCatching { CallMode.valueOf(json.optString("mode", CallMode.AUDIO.name)) }.getOrDefault(CallMode.AUDIO),
            sdp = sdp,
            sdpType = json.optString("sdpType").takeIf { it.isNotBlank() },
            candidate = candidate,
            sdpMid = json.optString("sdpMid").takeIf { it.isNotBlank() },
            sdpMLineIndex = if (json.has("sdpMLineIndex")) json.optInt("sdpMLineIndex") else null,
            reason = json.optString("reason").takeIf { it.isNotBlank() }
        )
    }

    private suspend fun sendSdp(
        peerKey: String,
        callId: String,
        mode: CallMode,
        description: AstraSessionDescription,
        type: String
    ) {
        val payload = JSONObject()
            .put("callId", callId)
            .put("mode", mode.name)
            .put("sdpType", description.type)
            .put("sdp", description.description)
            .toString()
        sendRawCallSignal(peerKey, payload, type)
    }

    private suspend fun sendRawCallSignal(peerKey: String, rawText: String, messageType: String) {
        withContext(Dispatchers.IO) {
            val session = activeSession
            if (session != null && session.isActive && session.peerKey == peerKey) {
                val wire = messageRouter.buildEncryptedWireFrame(peerKey, rawText, messageType)
                if (wire != null && session.sendFrame(wire)) {
                    return@withContext
                }
            }
            messageRouter.sendRawPayload(peerKey, rawText, messageType)
        }
    }
}
