package com.astramesh.app.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallDirection {
    INCOMING,
    OUTGOING
}

enum class CallMode {
    AUDIO,
    VIDEO,
    WALKIE_TALKIE,
    VOICE_NOTE
}

sealed class CallUiState {
    data object Idle : CallUiState()
    data class Ringing(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val direction: CallDirection,
        val mode: CallMode
    ) : CallUiState()
    data class Connecting(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val mode: CallMode
    ) : CallUiState()
    data class Connected(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val mode: CallMode
    ) : CallUiState()
    data class Ended(val reason: String) : CallUiState()
    data class Unavailable(val reason: String) : CallUiState()
}

class CallStateStore {
    private val _state = MutableStateFlow<CallUiState>(CallUiState.Idle)
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    fun update(state: CallUiState) {
        _state.value = state
    }

    fun reset() {
        _state.value = CallUiState.Idle
    }
}
