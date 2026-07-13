package com.torxone.app.network

sealed class TorState {
    object Idle : TorState()
    data class Starting(val progress: Int, val message: String) : TorState()
    data class Reconnecting(val reason: String, val attempt: Int) : TorState()
    data class Connected(val onionAddress: String) : TorState()
    data class Failed(val error: String) : TorState()
    object Stopped : TorState()

    fun getDisplayText(): String {
        return when (this) {
            is Idle -> "Idle"
            is Starting -> message
            is Reconnecting -> "Reconnecting via Tor..."
            is Connected -> "Connected Successfully"
            is Failed -> "Tor Failed: $error"
            is Stopped -> "Stopped"
        }
    }
}
