package com.torxone.app.call

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

data class CallStats(
    val bitrateKbps: Int = 0,
    val packetLossPercent: Float = 0f,
    val roundTripMs: Int = 0
)

sealed class CallUiState {
    data object Idle : CallUiState()
    data class Outgoing(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val mode: CallMode
    ) : CallUiState()
    data class Ringing(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val direction: CallDirection,
        val mode: CallMode
    ) : CallUiState()
    data class Accepted(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val mode: CallMode
    ) : CallUiState()
    data class Negotiating(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val mode: CallMode
    ) : CallUiState()
    data class IceConnecting(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val mode: CallMode
    ) : CallUiState()
    data class MediaConnecting(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val mode: CallMode
    ) : CallUiState()
    data class Connected(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val mode: CallMode,
        val isMuted: Boolean = false,
        val isSpeaker: Boolean = false,
        val callDurationSeconds: Int = 0,
        val stats: CallStats = CallStats()
    ) : CallUiState()
    data class Reconnecting(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val mode: CallMode
    ) : CallUiState()
    data class Ended(val reason: String, val durationSeconds: Int = 0) : CallUiState()
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

    /** Update mute/speaker/duration on the currently Connected state without replacing the whole state. */
    fun updateConnectedState(
        isMuted: Boolean? = null,
        isSpeaker: Boolean? = null,
        durationSeconds: Int? = null,
        stats: CallStats? = null
    ) {
        val current = _state.value as? CallUiState.Connected ?: return
        _state.value = current.copy(
            isMuted = isMuted ?: current.isMuted,
            isSpeaker = isSpeaker ?: current.isSpeaker,
            callDurationSeconds = durationSeconds ?: current.callDurationSeconds,
            stats = stats ?: current.stats
        )
    }
}
