package com.astramesh.app.call

import com.astramesh.app.data.ContactEntity
import com.astramesh.app.network.MessageRouter
import com.astramesh.app.network.Transport

class AdaptiveCallRouter(
    private val messageRouter: MessageRouter,
    private val engines: List<CallEngine>
) {
    fun buildContext(contact: ContactEntity): CallRouteContext {
        val transport = messageRouter.getBestTransport(contact)
        return CallRouteContext(
            peerKey = contact.signingPublicKey,
            peerName = contact.name,
            transport = transport,
            privacyRequiresRelay = transport == Transport.TOR
        )
    }

    fun selectAudioEngine(context: CallRouteContext): CallEngine? {
        val priority = when (context.transport) {
            Transport.NEARBY_DIRECT -> listOf(
                CallEngineType.LAN_AUDIO,
                CallEngineType.BLUETOOTH_WALKIE_TALKIE,
                CallEngineType.WEBRTC,
                CallEngineType.VOICE_NOTE
            )
            Transport.NEARBY_RELAY -> listOf(
                CallEngineType.BLUETOOTH_WALKIE_TALKIE,
                CallEngineType.VOICE_NOTE
            )
            Transport.TOR -> listOf(CallEngineType.VOICE_NOTE)
            Transport.FAILED -> emptyList()
        }

        return priority
            .asSequence()
            .mapNotNull { type -> engines.firstOrNull { it.capabilities.type == type } }
            .firstOrNull { it.isAvailable(context) }
    }
}
