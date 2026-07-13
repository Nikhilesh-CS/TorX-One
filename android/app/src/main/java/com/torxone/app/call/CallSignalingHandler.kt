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
    val sdpMLineIndex: Int? = null
)

class CallSignalingHandler(
    private val messageRouter: MessageRouter
) {
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
        withContext(Dispatchers.IO) {
            messageRouter.sendRawPayload(peerKey, payload, MeshProtocol.TYPE_ICE_CANDIDATE)
        }
    }

    fun parse(raw: String): CallSignal {
        val json = JSONObject(raw)
        return CallSignal(
            callId = json.getString("callId"),
            mode = runCatching { CallMode.valueOf(json.optString("mode", CallMode.AUDIO.name)) }.getOrDefault(CallMode.AUDIO),
            sdp = json.optString("sdp").takeIf { it.isNotBlank() },
            sdpType = json.optString("sdpType").takeIf { it.isNotBlank() },
            candidate = json.optString("candidate").takeIf { it.isNotBlank() },
            sdpMid = json.optString("sdpMid").takeIf { it.isNotBlank() },
            sdpMLineIndex = if (json.has("sdpMLineIndex")) json.optInt("sdpMLineIndex") else null
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
        withContext(Dispatchers.IO) {
            messageRouter.sendRawPayload(peerKey, payload, type)
        }
    }
}
