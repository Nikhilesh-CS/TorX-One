package com.torxone.app.call

import com.torxone.app.data.ContactEntity
import com.torxone.app.network.MessageRouter
import com.torxone.app.network.Transport

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
        // WebRTC handles all transports — it has its own NAT traversal
        val priority = when (context.transport) {
            Transport.NEARBY_DIRECT -> listOf(
                CallEngineType.WEBRTC,
                CallEngineType.VOICE_NOTE
            )
            Transport.NEARBY_RELAY -> listOf(
                CallEngineType.WEBRTC,
                CallEngineType.VOICE_NOTE
            )
            Transport.TOR -> listOf(
                CallEngineType.WEBRTC,
                CallEngineType.VOICE_NOTE
            )
            Transport.FAILED,
            Transport.PENDING -> emptyList()
        }

        return priority
            .asSequence()
            .mapNotNull { type -> engines.firstOrNull { it.capabilities.type == type } }
            .firstOrNull { it.isAvailable(context) }
    }
}
