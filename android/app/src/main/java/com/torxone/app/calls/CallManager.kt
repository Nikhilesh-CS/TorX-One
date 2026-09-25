package com.torxone.app.calls

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CallManager — THE single state-machine authority for all call state.
 *
 * Owns:
 * - Active CallSession lifecycle
 * - State transitions (only legal transitions are allowed)
 * - Ringing timeout (NO_ANSWER after 45s)
 * - Simultaneous-call collision resolution (deterministic tie-breaker)
 * - Resource teardown signals
 *
 * Scope: Application/Service level — survives Activity recreation.
 * CallViewModel observes it. UI never mutates PeerConnection or call state directly.
 *
 * Invariant: At most ONE active call at any time.
 */
class CallManager(
    private val callService: CallSignaling,
    private val localIdentityId: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private const val TAG = "CallManager"
        private const val RINGING_TIMEOUT_MS = 45_000L
        private const val RECONNECT_TIMEOUT_MS = 15_000L
    }

    private val _activeCall = MutableStateFlow<CallSession?>(null)
    val activeCall: StateFlow<CallSession?> = _activeCall.asStateFlow()

    private var ringingTimeoutJob: Job? = null
    private var reconnectTimeoutJob: Job? = null

    /** Listener for events that require WebRTC or system-level actions */
    var callEventListener: CallEventListener? = null

    // ─── Outgoing Call ───────────────────────────────────────────────────

    /**
     * Initiate an outgoing call. CallManager sets OUTGOING_PREPARING,
     * then the caller must provide the SDP offer via [onLocalOfferReady].
     */
    suspend fun startOutgoingCall(
        conversationId: String,
        relationshipId: String,
        peerIdentityId: String,
        type: CallType
    ): CallSession? {
        if (_activeCall.value != null) {
            Log.w(TAG, "Cannot start call — already in call ${_activeCall.value?.callId}")
            return null
        }

        val callId = java.util.UUID.randomUUID().toString()
        val session = CallSession(
            callId = callId,
            conversationId = conversationId,
            relationshipId = relationshipId,
            peerIdentityId = peerIdentityId,
            direction = CallDirection.OUTGOING,
            type = type,
            state = CallState.OUTGOING_PREPARING,
            startedAt = System.currentTimeMillis()
        )
        _activeCall.value = session
        Log.i(TAG, "[CALL] Outgoing $type call=$callId to peer=${peerIdentityId.take(8)}")

        // Signal WebRTC to create offer
        callEventListener?.onCreateOffer(session)

        return session
    }

    /**
     * Called when WebRtcClient produces a local SDP offer.
     * Sends CALL_OFFER via secure signaling and transitions to OUTGOING_RINGING.
     */
    suspend fun onLocalOfferReady(callId: String, sdpOffer: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId || session.state != CallState.OUTGOING_PREPARING) return

        callService.sendCallOffer(session, sdpOffer)
        transition(callId, CallState.OUTGOING_RINGING)
        startRingingTimeout(callId)
    }

    // ─── Incoming Call ───────────────────────────────────────────────────

    /**
     * Called by CallHandler when a CALL_OFFER is received from the peer.
     * Returns true if the call was accepted for ringing, false if busy/collision.
     */
    suspend fun onIncomingOffer(
        callId: String,
        conversationId: String,
        relationshipId: String,
        peerIdentityId: String,
        type: CallType,
        sdpOffer: String
    ): Boolean {
        val existing = _activeCall.value

        // Busy — already in a call with someone else or same person
        if (existing != null && existing.state != CallState.OUTGOING_PREPARING) {
            // Check for simultaneous-call collision
            if (existing.peerIdentityId == peerIdentityId &&
                (existing.state == CallState.OUTGOING_RINGING || existing.state == CallState.OUTGOING_PREPARING)
            ) {
                return handleCollision(existing, callId, conversationId, relationshipId, peerIdentityId, type, sdpOffer)
            }

            // Truly busy — different peer or already connected
            Log.i(TAG, "[BUSY] Already in call ${existing.callId}, sending BUSY to $callId")
            callService.sendBusy(callId, conversationId, relationshipId, peerIdentityId)
            return false
        }

        // Also handle collision if we're in OUTGOING_PREPARING
        if (existing != null && existing.state == CallState.OUTGOING_PREPARING &&
            existing.peerIdentityId == peerIdentityId
        ) {
            return handleCollision(existing, callId, conversationId, relationshipId, peerIdentityId, type, sdpOffer)
        }

        val session = CallSession(
            callId = callId,
            conversationId = conversationId,
            relationshipId = relationshipId,
            peerIdentityId = peerIdentityId,
            direction = CallDirection.INCOMING,
            type = type,
            state = CallState.INCOMING_RINGING,
            startedAt = System.currentTimeMillis()
        )
        _activeCall.value = session

        // Send RINGING to let caller know we're presenting the call
        callService.sendRinging(session)
        startRingingTimeout(callId)

        Log.i(TAG, "[CALL] Incoming $type call=$callId from peer=${peerIdentityId.take(8)}")
        callEventListener?.onIncomingCall(session, sdpOffer)
        return true
    }

    /**
     * User accepts incoming call.
     */
    suspend fun acceptCall(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId || session.state != CallState.INCOMING_RINGING) return

        cancelRingingTimeout()
        // WebRTC should create answer — UI triggers this
        callEventListener?.onCreateAnswer(session)
    }

    /**
     * Called when WebRtcClient produces a local SDP answer.
     */
    suspend fun onLocalAnswerReady(callId: String, sdpAnswer: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return

        callService.sendCallAnswer(session, sdpAnswer)
        transition(callId, CallState.CONNECTING)
    }

    /**
     * User declines incoming call.
     */
    suspend fun declineCall(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId || session.state != CallState.INCOMING_RINGING) return

        cancelRingingTimeout()
        callService.sendDecline(session)
        transition(callId, CallState.DECLINED)
        endCall(callId, CallEndReason.DECLINED)
    }

    // ─── Signaling Callbacks (from CallHandler) ──────────────────────────

    fun onRemoteRinging(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId || session.state != CallState.OUTGOING_RINGING) return
        // Stay in OUTGOING_RINGING — this just confirms the peer is presenting
        Log.d(TAG, "[CALL] Remote ringing for call=$callId")
    }

    suspend fun onRemoteAnswer(callId: String, sdpAnswer: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId || session.state != CallState.OUTGOING_RINGING) return

        cancelRingingTimeout()
        transition(callId, CallState.CONNECTING)
        callEventListener?.onRemoteAnswer(session, sdpAnswer)
    }

    suspend fun onRemoteIceCandidate(callId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) {
            Log.w(TAG, "[CALL] ICE candidate for stale call=$callId, active=${session.callId}")
            return
        }
        callEventListener?.onRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    fun onRemoteConnected(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        // Peer confirms media is flowing
        Log.d(TAG, "[CALL] Remote confirmed connected for call=$callId")
    }

    suspend fun onRemoteEnd(callId: String, reason: CallEndReason) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) {
            Log.w(TAG, "[CALL] End for stale call=$callId, active=${session.callId}")
            return
        }
        endCall(callId, reason)
    }

    suspend fun onRemoteDecline(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        cancelRingingTimeout()
        transition(callId, CallState.DECLINED)
        endCall(callId, CallEndReason.DECLINED)
    }

    fun onRemoteBusy(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        cancelRingingTimeout()
        _activeCall.value = session.copy(state = CallState.BUSY, endReason = CallEndReason.BUSY)
        scope.launch { endCall(callId, CallEndReason.BUSY) }
    }

    // ─── ICE State Callbacks (from WebRtcClient) ─────────────────────────

    suspend fun onIceConnected(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        if (session.state == CallState.CONNECTING || session.state == CallState.RECONNECTING) {
            cancelReconnectTimeout()
            val now = System.currentTimeMillis()
            _activeCall.value = session.copy(
                state = CallState.CONNECTED,
                connectedAt = session.connectedAt ?: now
            )
            callService.sendConnected(session)
            Log.i(TAG, "[CALL] Connected! call=$callId")
            callEventListener?.onCallConnected(session)
        }
    }

    suspend fun onIceDisconnected(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        if (session.state == CallState.CONNECTED) {
            transition(callId, CallState.RECONNECTING)
            startReconnectTimeout(callId)
        }
    }

    suspend fun onIceFailed(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        cancelReconnectTimeout()
        endCall(callId, CallEndReason.CONNECTION_FAILED)
    }

    /**
     * Called when WebRTC gathers a local ICE candidate.
     */
    suspend fun onLocalIceCandidate(callId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        callService.sendIceCandidate(session, sdpMid, sdpMLineIndex, candidate)
    }

    // ─── User Actions ────────────────────────────────────────────────────

    /**
     * User hangs up the active call.
     */
    suspend fun hangUp() {
        val session = _activeCall.value ?: return
        val reason = when (session.direction) {
            CallDirection.OUTGOING -> CallEndReason.LOCAL_HANGUP
            CallDirection.INCOMING -> CallEndReason.LOCAL_HANGUP
        }
        callService.sendEnd(session, reason)
        endCall(session.callId, reason)
    }

    fun toggleMute() {
        val session = _activeCall.value ?: return
        val newMuted = !session.isMuted
        _activeCall.value = session.copy(isMuted = newMuted)
        callEventListener?.onMuteChanged(newMuted)
    }

    fun toggleSpeaker() {
        val session = _activeCall.value ?: return
        val newSpeaker = !session.isSpeakerOn
        _activeCall.value = session.copy(isSpeakerOn = newSpeaker)
        callEventListener?.onSpeakerChanged(newSpeaker)
    }

    fun toggleCamera() {
        val session = _activeCall.value ?: return
        val newCamera = !session.isCameraOn
        _activeCall.value = session.copy(isCameraOn = newCamera)
        callEventListener?.onCameraChanged(newCamera)
    }

    fun switchCamera() {
        callEventListener?.onSwitchCamera()
    }

    /**
     * Upgrade a voice call to include video.
     */
    fun enableVideo() {
        val session = _activeCall.value ?: return
        _activeCall.value = session.copy(type = CallType.VIDEO, isCameraOn = true)
        callEventListener?.onCameraChanged(true)
    }

    // ─── State Machine ───────────────────────────────────────────────────

    private fun transition(callId: String, newState: CallState) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        Log.d(TAG, "[STATE] ${session.state} → $newState for call=$callId")
        _activeCall.value = session.copy(state = newState)
    }

    private suspend fun endCall(callId: String, reason: CallEndReason) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return

        cancelRingingTimeout()
        cancelReconnectTimeout()

        val now = System.currentTimeMillis()
        val durationMs = session.connectedAt?.let { now - it }

        val terminalState = if (session.state in setOf(CallState.DECLINED, CallState.BUSY, CallState.MISSED)) {
            session.state
        } else {
            CallState.ENDED
        }
        val endedSession = session.copy(
            state = terminalState,
            endedAt = now,
            endReason = reason
        )
        _activeCall.value = endedSession

        // Persist call history
        val outcome = when (reason) {
            CallEndReason.LOCAL_HANGUP, CallEndReason.REMOTE_HANGUP -> CallOutcome.COMPLETED
            CallEndReason.DECLINED -> CallOutcome.DECLINED
            CallEndReason.BUSY -> CallOutcome.BUSY
            CallEndReason.NO_ANSWER -> CallOutcome.NO_ANSWER
            CallEndReason.CONNECTION_FAILED, CallEndReason.NETWORK_LOST -> CallOutcome.FAILED
            CallEndReason.PERMISSION_DENIED -> CallOutcome.FAILED
            CallEndReason.COLLISION_SUPERSEDED -> CallOutcome.FAILED
        }
        callService.persistCallHistory(endedSession, outcome, durationMs)

        // Signal teardown
        callEventListener?.onCallEnded(endedSession)

        Log.i(TAG, "[CALL] Ended call=$callId reason=$reason duration=${durationMs}ms")

        // Clear after a brief delay so UI can show "Call ended"
        scope.launch {
            delay(2000)
            if (_activeCall.value?.callId == callId) {
                _activeCall.value = null
            }
        }
    }

    // ─── Collision Resolution ────────────────────────────────────────────

    /**
     * Deterministic tie-breaker: the lexicographically smaller identity "wins"
     * (their offer supersedes). Same approach as Nearby collision handling.
     */
    private suspend fun handleCollision(
        ourSession: CallSession,
        theirCallId: String,
        conversationId: String,
        relationshipId: String,
        peerIdentityId: String,
        type: CallType,
        sdpOffer: String
    ): Boolean {
        val weWin = localIdentityId < peerIdentityId
        return if (weWin) {
            // Our offer wins — ignore theirs, send BUSY back
            Log.i(TAG, "[COLLISION] We win (our identity < peer). Ignoring incoming offer $theirCallId")
            callService.sendBusy(theirCallId, conversationId, relationshipId, peerIdentityId)
            false
        } else {
            // Their offer wins — cancel ours and accept theirs
            Log.i(TAG, "[COLLISION] They win (peer identity < ours). Superseding our call ${ourSession.callId}")
            callEventListener?.onCallEnded(ourSession)

            val session = CallSession(
                callId = theirCallId,
                conversationId = conversationId,
                relationshipId = relationshipId,
                peerIdentityId = peerIdentityId,
                direction = CallDirection.INCOMING,
                type = type,
                state = CallState.INCOMING_RINGING,
                startedAt = System.currentTimeMillis()
            )
            _activeCall.value = session
            callService.sendRinging(session)
            startRingingTimeout(theirCallId)
            callEventListener?.onIncomingCall(session, sdpOffer)
            true
        }
    }

    // ─── Timeouts ────────────────────────────────────────────────────────

    private fun startRingingTimeout(callId: String) {
        cancelRingingTimeout()
        ringingTimeoutJob = scope.launch {
            delay(RINGING_TIMEOUT_MS)
            val session = _activeCall.value ?: return@launch
            if (session.callId != callId) return@launch

            when (session.state) {
                CallState.OUTGOING_RINGING -> {
                    Log.i(TAG, "[TIMEOUT] No answer for outgoing call=$callId")
                    callService.sendEnd(session, CallEndReason.NO_ANSWER)
                    transition(callId, CallState.MISSED)
                    endCall(callId, CallEndReason.NO_ANSWER)
                }
                CallState.INCOMING_RINGING -> {
                    Log.i(TAG, "[TIMEOUT] Incoming call=$callId unanswered, marking MISSED")
                    transition(callId, CallState.MISSED)
                    endCall(callId, CallEndReason.NO_ANSWER)
                }
                else -> {}
            }
        }
    }

    private fun cancelRingingTimeout() {
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = null
    }

    private fun startReconnectTimeout(callId: String) {
        cancelReconnectTimeout()
        reconnectTimeoutJob = scope.launch {
            delay(RECONNECT_TIMEOUT_MS)
            val session = _activeCall.value ?: return@launch
            if (session.callId == callId && session.state == CallState.RECONNECTING) {
                Log.i(TAG, "[TIMEOUT] Reconnect failed for call=$callId")
                endCall(callId, CallEndReason.NETWORK_LOST)
            }
        }
    }

    private fun cancelReconnectTimeout() {
        reconnectTimeoutJob?.cancel()
        reconnectTimeoutJob = null
    }

    /**
     * Clean up when application is destroyed.
     */
    fun destroy() {
        scope.cancel()
    }
}

/**
 * Event listener for CallManager → WebRtcClient / System bridging.
 * Implemented by the component owning the WebRTC peer connection and audio routing.
 */
interface CallEventListener {
    fun onCreateOffer(session: CallSession)
    fun onCreateAnswer(session: CallSession)
    fun onRemoteAnswer(session: CallSession, sdpAnswer: String)
    fun onRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
    fun onCallConnected(session: CallSession)
    fun onCallEnded(session: CallSession)
    fun onIncomingCall(session: CallSession, sdpOffer: String)
    fun onMuteChanged(muted: Boolean)
    fun onSpeakerChanged(speaker: Boolean)
    fun onCameraChanged(enabled: Boolean)
    fun onSwitchCamera()
}
