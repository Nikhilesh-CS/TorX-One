package com.torxone.app.call

import com.torxone.app.data.ContactEntity
import com.torxone.app.network.Transport

class DisabledCallEngine(
    private val reason: String
) : CallEngine {
    override val capabilities = CallEngineCapabilities(
        type = CallEngineType.DISABLED,
        supportsLiveAudio = false,
        supportsLiveVideo = false,
        supportsAsyncSegments = false,
        supportedTransports = setOf(Transport.FAILED)
    )

    override fun isAvailable(context: CallRouteContext): Boolean = true

    override suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult {
        return CallStartResult.Failed(reason)
    }

    override suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult {
        return CallStartResult.Failed(reason)
    }

    override fun handleRemoteDescription(description: AstraSessionDescription) = Unit
    override fun handleIceCandidate(candidate: AstraIceCandidate) = Unit
    override fun end() = Unit
}
