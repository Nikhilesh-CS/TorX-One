package com.torxone.app.call

import com.torxone.app.data.ContactEntity
import com.torxone.app.network.MessageRouter
import com.torxone.app.network.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class VoiceNoteCallEngine(
    private val messageRouter: MessageRouter
) : CallEngine {
    override val capabilities = CallEngineCapabilities(
        type = CallEngineType.VOICE_NOTE,
        supportsLiveAudio = false,
        supportsLiveVideo = false,
        supportsAsyncSegments = true,
        supportedTransports = setOf(
            Transport.NEARBY_DIRECT,
            Transport.NEARBY_RELAY,
            Transport.TOR
        )
    )

    override fun isAvailable(context: CallRouteContext): Boolean {
        return context.transport in capabilities.supportedTransports
    }

    override suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult {
        val session = JSONObject()
            .put("engine", "voice_note_segments")
            .put("segmentMs", 2500)
            .put("codec", "opus_or_aac")
            .put("transport", context.transport.name)
            .put("liveMedia", false)
            .toString()
        val payload = JSONObject()
            .put("callId", callId)
            .put("mode", CallMode.VOICE_NOTE.name)
            .put("sdpType", "voice_note")
            .put("sdp", session)
            .toString()

        val result = withContext(Dispatchers.IO) {
            messageRouter.sendRawPayload(contact.signingPublicKey, payload, com.torxone.app.network.MeshProtocol.TYPE_CALL_OFFER)
        }

        return if (result.success) {
            CallStartResult.Started(callId, CallMode.VOICE_NOTE, CallEngineType.VOICE_NOTE)
        } else {
            CallStartResult.Failed(result.error ?: "Unable to start encrypted voice-note call mode.")
        }
    }

    override suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult {
        return CallStartResult.Started(callId, CallMode.VOICE_NOTE, CallEngineType.VOICE_NOTE)
    }

    override fun handleRemoteDescription(description: AstraSessionDescription) = Unit
    override fun handleIceCandidate(candidate: AstraIceCandidate) = Unit
    override fun end() = Unit
}
