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

enum class CallQuality {
    UNKNOWN,
    GOOD,
    FAIR,
    POOR
}

data class CallStats(
    val bitrateKbps: Int = 0,
    val packetLossPercent: Float = 0f,
    val roundTripMs: Int = 0,
    val jitterMs: Int = 0,
    val audioLevel: Float = 0f
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
        val stats: CallStats = CallStats(),
        val quality: CallQuality = CallQuality.GOOD
    ) : CallUiState()
    data class Reconnecting(
        val callId: String,
        val peerKey: String,
        val peerName: String,
        val mode: CallMode,
        val isMuted: Boolean = false,
        val isSpeaker: Boolean = false,
        val callDurationSeconds: Int = 0
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

    /** Update mute/speaker/duration/stats/quality on the currently Connected state without replacing the whole state. */
    fun updateConnectedState(
        isMuted: Boolean? = null,
        isSpeaker: Boolean? = null,
        durationSeconds: Int? = null,
        stats: CallStats? = null,
        quality: CallQuality? = null
    ) {
        val current = _state.value as? CallUiState.Connected ?: return
        _state.value = current.copy(
            isMuted = isMuted ?: current.isMuted,
            isSpeaker = isSpeaker ?: current.isSpeaker,
            callDurationSeconds = durationSeconds ?: current.callDurationSeconds,
            stats = stats ?: current.stats,
            quality = quality ?: current.quality
        )
    }
}

val CallUiState.isActiveCall: Boolean
    get() = when (this) {
        is CallUiState.Outgoing,
        is CallUiState.Ringing,
        is CallUiState.Accepted,
        is CallUiState.Negotiating,
        is CallUiState.IceConnecting,
        is CallUiState.MediaConnecting,
        is CallUiState.Connected,
        is CallUiState.Reconnecting -> true

        is CallUiState.Idle,
        is CallUiState.Ended,
        is CallUiState.Unavailable -> false
    }

val CallUiState.activeCallId: String?
    get() = when (this) {
        is CallUiState.Outgoing -> callId
        is CallUiState.Ringing -> callId
        is CallUiState.Accepted -> callId
        is CallUiState.Negotiating -> callId
        is CallUiState.IceConnecting -> callId
        is CallUiState.MediaConnecting -> callId
        is CallUiState.Connected -> callId
        is CallUiState.Reconnecting -> callId
        else -> null
    }

val CallUiState.peerNameOrEmpty: String
    get() = when (this) {
        is CallUiState.Outgoing -> peerName
        is CallUiState.Ringing -> peerName
        is CallUiState.Accepted -> peerName
        is CallUiState.Negotiating -> peerName
        is CallUiState.IceConnecting -> peerName
        is CallUiState.MediaConnecting -> peerName
        is CallUiState.Connected -> peerName
        is CallUiState.Reconnecting -> peerName
        else -> ""
    }

fun CallUiState.bannerStatusText(): String = when (this) {
    is CallUiState.Outgoing -> "Calling…"
    is CallUiState.Ringing -> if (direction == CallDirection.INCOMING) "Incoming call" else "Ringing…"
    is CallUiState.Accepted -> "Connecting…"
    is CallUiState.Negotiating -> "Connecting…"
    is CallUiState.IceConnecting -> "Connecting to peer…"
    is CallUiState.MediaConnecting -> "Starting audio…"
    is CallUiState.Connected -> {
        val m = callDurationSeconds / 60
        val s = callDurationSeconds % 60
        if (quality == CallQuality.POOR) {
            java.lang.String.format(java.util.Locale.US, "⚠ Poor connection • %02d:%02d", m, s)
        } else {
            java.lang.String.format(java.util.Locale.US, "Connected • %02d:%02d", m, s)
        }
    }
    is CallUiState.Reconnecting -> "Reconnecting…"
    is CallUiState.Idle,
    is CallUiState.Ended,
    is CallUiState.Unavailable -> ""
}

