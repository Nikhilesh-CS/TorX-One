package com.torxone.app.calls

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torxone.app.data.dao.ContactDao
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * CallViewModel — Presentation layer for call UI screens.
 *
 * OBSERVES CallManager. Does NOT own call state.
 * CallManager lives at Application/Service scope and survives Activity recreation.
 * This ViewModel merely transforms CallSession into UI state.
 *
 * Handles:
 * - Call duration timer
 * - Peer display name resolution
 * - UI state derivation from CallSession
 * - Forwarding user actions to CallManager
 */
class CallViewModel(
    private val callManager: CallManager,
    private val contactDao: ContactDao
) : ViewModel() {

    companion object {
        private const val TAG = "CallViewModel"
    }

    /**
     * UI state derived from CallSession.
     */
    data class CallUiState(
        val callId: String = "",
        val peerName: String = "",
        val peerAvatarHash: String? = null,
        val callType: CallType = CallType.VOICE,
        val direction: CallDirection = CallDirection.OUTGOING,
        val state: CallState = CallState.IDLE,
        val statusText: String = "",
        val durationText: String = "",
        val durationMs: Long = 0L,
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = false,
        val isCameraOn: Boolean = false,
        val isRemoteCameraOn: Boolean = true,
        val isActive: Boolean = false
    )

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    init {
        // Observe CallManager's active call
        viewModelScope.launch {
            callManager.activeCall.collect { session ->
                if (session != null) {
                    updateFromSession(session)
                } else {
                    _uiState.value = CallUiState()
                }
            }
        }

        // Duration timer — ticks every second while connected
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val current = _uiState.value
                if (current.state == CallState.CONNECTED || current.state == CallState.RECONNECTING) {
                    val session = callManager.activeCall.value ?: continue
                    val elapsed = System.currentTimeMillis() - (session.connectedAt ?: session.startedAt)
                    _uiState.value = current.copy(
                        durationText = formatDuration(elapsed),
                        durationMs = elapsed
                    )
                }
            }
        }
    }

    private suspend fun updateFromSession(session: CallSession) {
        // Resolve peer display name
        val contacts = contactDao.getByRelationshipId(session.relationshipId)
        val peerName = contacts?.displayName ?: session.peerIdentityId.take(8)
        val avatarHash = contacts?.avatarHash

        _uiState.value = CallUiState(
            callId = session.callId,
            peerName = peerName,
            peerAvatarHash = avatarHash,
            callType = session.type,
            direction = session.direction,
            state = session.state,
            statusText = deriveStatusText(session),
            durationText = if (session.connectedAt != null) {
                formatDuration(System.currentTimeMillis() - session.connectedAt)
            } else "",
            durationMs = session.connectedAt?.let { System.currentTimeMillis() - it } ?: 0L,
            isMuted = session.isMuted,
            isSpeakerOn = session.isSpeakerOn,
            isCameraOn = session.isCameraOn,
            isRemoteCameraOn = session.isRemoteCameraOn,
            isActive = session.state in setOf(
                CallState.OUTGOING_PREPARING,
                CallState.OUTGOING_RINGING,
                CallState.INCOMING_RINGING,
                CallState.CONNECTING,
                CallState.CONNECTED,
                CallState.RECONNECTING
            )
        )
    }

    private fun deriveStatusText(session: CallSession): String {
        return when (session.state) {
            CallState.IDLE -> ""
            CallState.OUTGOING_PREPARING -> "Calling…"
            CallState.OUTGOING_RINGING -> "Ringing…"
            CallState.INCOMING_RINGING -> {
                val typeLabel = if (session.type == CallType.VIDEO) "video" else "voice"
                "Incoming $typeLabel call"
            }
            CallState.CONNECTING -> "Connecting…"
            CallState.CONNECTED -> "" // Duration shown instead
            CallState.RECONNECTING -> "Reconnecting…"
            CallState.ENDING -> "Ending…"
            CallState.ENDED -> "Call ended"
            CallState.DECLINED -> "Call declined"
            CallState.BUSY -> "User is busy"
            CallState.MISSED -> "No answer"
            CallState.FAILED -> "Couldn't connect call"
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    // ─── User Actions (forwarded to CallManager) ─────────────────────────

    fun hangUp() {
        viewModelScope.launch { callManager.hangUp() }
    }

    fun acceptCall() {
        val callId = _uiState.value.callId
        if (callId.isNotEmpty()) {
            viewModelScope.launch { callManager.acceptCall(callId) }
        }
    }

    fun declineCall() {
        val callId = _uiState.value.callId
        if (callId.isNotEmpty()) {
            viewModelScope.launch { callManager.declineCall(callId) }
        }
    }

    fun toggleMute() = callManager.toggleMute()
    fun toggleSpeaker() = callManager.toggleSpeaker()
    fun toggleCamera() = callManager.toggleCamera()
    fun switchCamera() = callManager.switchCamera()
    fun enableVideo() = callManager.enableVideo()
}
