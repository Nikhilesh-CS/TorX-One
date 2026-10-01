package com.torxone.app.ui.connection

data class ConnectionPathInfo(val name: String, val status: String, val routeReady: Boolean = false)

data class ConnectionUxSnapshot(
    val headline: String = "Checking connection",
    val detail: String = "Messages stay queued while a secure path becomes available.",
    val internet: String = "Checking Internet availability",
    val paths: List<ConnectionPathInfo> = emptyList(),
    val identity: String = "Identity verification not checked",
    val session: String = "Session not checked",
    val pendingDeliveries: Int = 0,
    val error: String? = null,
    val sendingPaused: Boolean = false,
    val pendingControlDeliveries: Int = 0
)

/** Presentation deliberately separates local transport readiness from remote delivery proof. */
object ConnectionUxPresentation {
    fun create(
        internet: String, internetValidated: Boolean?, paths: List<ConnectionPathInfo>,
        verified: Boolean?, sessionReady: Boolean?, pending: Int, nearbySearching: Boolean,
        sendingPaused: Boolean = false, controls: Int = 0
    ): ConnectionUxSnapshot {
        val tor = paths.firstOrNull { it.name == "Tor" }?.routeReady == true
        val nearby = paths.firstOrNull { it.name == "Nearby" }?.routeReady == true
        val fallback = paths.firstOrNull { it.name != "Tor" && it.routeReady }
        val headline = when {
            sendingPaused -> "Sending paused"
            tor && pending > 0 -> "Waiting for peer delivery"
            tor -> "Tor connected · peer unconfirmed"
            nearby -> "Connected directly via Nearby"
            fallback != null -> "${fallback.name} path ready"
            internetValidated == false && nearbySearching -> "Internet unavailable · searching nearby"
            internetValidated == false -> "Internet unavailable"
            else -> "Waiting for a connection"
        }
        val detail = when {
            sendingPaused -> "Queued messages stay saved. Resume sending when you are ready. An already-started send may still finish."
            tor -> "Tor is ready on this phone and a peer route is configured. Delivery requires a peer ACK."
            nearby -> "An authenticated Nearby route is available for this peer. Delivery requires a peer ACK."
            fallback != null -> "A configured fallback route is available. Delivery requires a peer ACK."
            pending > 0 -> "Messages are saved in the outbox and retry automatically when a path is available."
            else -> "Messages will queue safely until a path is available."
        }
        return ConnectionUxSnapshot(headline, detail, internet, paths,
            when (verified) {
                true -> "Identity marked verified locally; manual safety-number comparison not recorded"
                false -> "Identity not marked verified"
                null -> "No direct-peer verification record"
            },
            when (sessionReady) { true -> "Active Double Ratchet session"; false -> "No active session"; null -> "Session information unavailable" },
            pending.coerceAtLeast(0), sendingPaused = sendingPaused, pendingControlDeliveries = controls.coerceAtLeast(0))
    }
}
