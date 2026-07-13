package com.torxone.app.call

import com.torxone.app.data.ContactEntity
import com.torxone.app.network.Transport

class WebRtcCallEngine : CallEngine {
    override val capabilities = CallEngineCapabilities(
        type = CallEngineType.WEBRTC,
        supportsLiveAudio = true,
        supportsLiveVideo = true,
        supportsAsyncSegments = false,
        supportedTransports = setOf(Transport.NEARBY_DIRECT)
    )

    override fun isAvailable(context: CallRouteContext): Boolean {
        return context.transport == Transport.NEARBY_DIRECT && WebRtcRuntimeGuard.isRuntimeEnabled()
    }

    override suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult {
        if (!WebRtcRuntimeGuard.isRuntimeEnabled()) {
            return CallStartResult.Fallback(
                reason = WebRtcRuntimeGuard.disabledReason,
                preferredEngine = CallEngineType.VOICE_NOTE
            )
        }
        return CallStartResult.Fallback(
            reason = "WebRTC engine module is available, but media runtime binding is not installed in this build.",
            preferredEngine = CallEngineType.VOICE_NOTE
        )
    }

    override suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult {
        if (!WebRtcRuntimeGuard.isRuntimeEnabled()) {
            return CallStartResult.Fallback(
                reason = WebRtcRuntimeGuard.disabledReason,
                preferredEngine = CallEngineType.VOICE_NOTE
            )
        }
        return CallStartResult.Fallback(
            reason = "WebRTC media runtime binding is not installed in this build.",
            preferredEngine = CallEngineType.VOICE_NOTE
        )
    }

    override fun handleRemoteDescription(description: AstraSessionDescription) = Unit
    override fun handleIceCandidate(candidate: AstraIceCandidate) = Unit
    override fun end() = Unit
}

object WebRtcRuntimeGuard {
    val disabledReason: String =
        "WebRTC is replaceable and currently disabled because the previous native runtime crashed during load on this device."

    fun isRuntimeEnabled(): Boolean = false
}
