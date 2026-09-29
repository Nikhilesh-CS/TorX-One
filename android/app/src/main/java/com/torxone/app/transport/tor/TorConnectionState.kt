package com.torxone.app.transport.tor

sealed class TorConnectionState {
    data object Stopped : TorConnectionState()
    data object Starting : TorConnectionState()
    data class Bootstrapping(val percent: Int) : TorConnectionState()
    data class Ready(val socksPort: Int, val onionAddress: String?) : TorConnectionState()
    data class Failed(val reason: String) : TorConnectionState()
}
