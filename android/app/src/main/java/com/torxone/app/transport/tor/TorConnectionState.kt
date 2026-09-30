package com.torxone.app.transport.tor

sealed class TorConnectionState {
    data object Stopped : TorConnectionState()
    data object Starting : TorConnectionState()
    data class Bootstrapping(val percent: Int) : TorConnectionState()
    data object OnionPublishing : TorConnectionState()
    data class Ready(val socksPort: Int, val onionAddress: String) : TorConnectionState() {
        init {
            require(socksPort in 1..65535)
            require(onionAddress.matches(Regex("[a-z2-7]{56}\\.onion")))
        }
    }
    data class Failed(val reason: String) : TorConnectionState()
}
