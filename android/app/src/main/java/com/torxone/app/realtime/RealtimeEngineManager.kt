package com.torxone.app.realtime

import android.content.Context
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.MessageRouter
import com.torxone.app.network.Transport

class RealtimeEngineManager(
    private val context: Context,
    private val messageRouter: MessageRouter,
    engines: List<RealtimeEngine>? = null
) {
    private val engines: List<RealtimeEngine> = engines ?: listOf(
        LibWebRtcEngine(context.applicationContext),
        DisabledRealtimeEngine()
    )

    fun routeFor(contact: ContactEntity): RealtimeRoute {
        val transport = messageRouter.getBestTransport(contact)
        return RealtimeRoute(
            peerKey = contact.signingPublicKey,
            transport = transport,
            privacyOnly = transport == Transport.TOR
        )
    }

    fun select(route: RealtimeRoute): RealtimeEngine {
        return engines.firstOrNull { it.isAvailable(route) } ?: DisabledRealtimeEngine()
    }

    fun isWebRtcRuntimeCompatible(): Boolean {
        return WebRtcProbeStore.isCompatible(context)
    }
}
