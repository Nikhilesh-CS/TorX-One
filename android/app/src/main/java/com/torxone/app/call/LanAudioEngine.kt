package com.torxone.app.call

import android.content.Context
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.Transport

class LanAudioEngine(
    private val context: Context
) : CallEngine {
    override val capabilities = CallEngineCapabilities(
        type = CallEngineType.LAN_AUDIO,
        supportsLiveAudio = true,
        supportsLiveVideo = false,
        supportsAsyncSegments = false,
        supportedTransports = setOf(Transport.NEARBY_DIRECT)
    )

    override fun isAvailable(context: CallRouteContext): Boolean {
        return context.transport == Transport.NEARBY_DIRECT && !context.privacyRequiresRelay
    }

    override suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult {
        return CallStartResult.Fallback(
            reason = "LAN live audio engine is not enabled yet. Falling back to encrypted voice-note mode.",
            preferredEngine = CallEngineType.VOICE_NOTE
        )
    }

    override suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult {
        return CallStartResult.Fallback(
            reason = "LAN live audio engine is not enabled yet.",
            preferredEngine = CallEngineType.VOICE_NOTE
        )
    }

    override fun handleRemoteDescription(description: AstraSessionDescription) = Unit
    override fun handleIceCandidate(candidate: AstraIceCandidate) = Unit
    override fun end() = Unit
}
