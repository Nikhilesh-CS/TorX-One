package com.torxone.app.call

import com.torxone.app.data.ContactEntity
import com.torxone.app.network.Transport

class BluetoothWalkieTalkieEngine : CallEngine {
    override val capabilities = CallEngineCapabilities(
        type = CallEngineType.BLUETOOTH_WALKIE_TALKIE,
        supportsLiveAudio = false,
        supportsLiveVideo = false,
        supportsAsyncSegments = true,
        supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.NEARBY_RELAY)
    )

    override fun isAvailable(context: CallRouteContext): Boolean {
        return context.transport == Transport.NEARBY_DIRECT || context.transport == Transport.NEARBY_RELAY
    }

    override suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult {
        return CallStartResult.Fallback(
            reason = "Bluetooth walkie-talkie audio segments are routed through the voice-note engine.",
            preferredEngine = CallEngineType.VOICE_NOTE
        )
    }

    override suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult {
        return CallStartResult.Fallback(
            reason = "Bluetooth walkie-talkie audio segments are routed through the voice-note engine.",
            preferredEngine = CallEngineType.VOICE_NOTE
        )
    }

    override fun handleRemoteDescription(description: AstraSessionDescription) = Unit
    override fun handleIceCandidate(candidate: AstraIceCandidate) = Unit
    override fun end() = Unit
}
