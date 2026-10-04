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
    private val localIdentityId: String = "",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val localIdentityIdProvider: () -> String? = { localIdentityId }
) {
    companion object {
        private const val TAG = "CallManager"
        private const val RINGING_TIMEOUT_MS = 45_000L
        private const val SIGNAL_DELIVERY_TIMEOUT_MS = 150_000L
        private const val RECONNECT_TIMEOUT_MS = 150_000L
    }

    private val _activeCall = MutableStateFlow<CallSession?>(null)
    val activeCall: StateFlow<CallSession?> = _activeCall.asStateFlow()

    private var ringingTimeoutJob: Job? = null
    private var reconnectTimeoutJob: Job? = null

    /** Listener for events that require WebRTC or system-level actions */
    var callEventListener: CallEventListener? = null

    init {
        callService.observeOfferAcceptance(::onOfferTransportAccepted)
    }

    fun onOfferTransportAccepted(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId == callId && session.state == CallState.OUTGOING_SENDING) {
            transition(callId, CallState.OUTGOING_CALLING)
        }
    }

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
        if (peerIdentityId.isBlank() || peerIdentityId == com.torxone.app.data.entity.ContactEntity.REMOTE_IDENTITY_UNKNOWN) {
            Log.e(TAG, "Cannot start call: Peer identity is unknown or legacy. Security information must be refreshed.")
            return null
        }
        if (_activeCall.value != null) {
            Log.w(TAG, "Cannot start call — already in call")
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
        Log.i(TAG, "[CALL] Outgoing")

        // Signal WebRTC to create offer
        callEventListener?.onCreateOffer(session)

        return session
    }

    /**
     * Called when WebRtcClient produces a local SDP offer.
     * Sends CALL_OFFER via secure signaling and remains in a local calling state
     * until the peer returns an authenticated CALL_RINGING signal.
     */
    suspend fun onLocalOfferReady(callId: String, sdpOffer: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId || session.state != CallState.OUTGOING_PREPARING) return

        transition(callId, CallState.OUTGOING_SENDING)
        try {
            callService.sendCallOffer(session, sdpOffer)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            onCallFailed(callId, "Could not queue the call request")
            return
        }
        startRingingTimeout(callId)
    }

    // ─── Incoming Call ───────────────────────────────────────────────────

    suspend fun onLocalRestartOfferReady(callId: String, sdp: String) {
        val session = _activeCall.value ?: return
        if (session.callId == callId && session.state == CallState.RECONNECTING) callService.sendCallOffer(session, sdp)
    }

    suspend fun onLocalRestartAnswerReady(callId: String, sdp: String) {
        val session = _activeCall.value ?: return
        if (session.callId == callId && session.state == CallState.RECONNECTING) callService.sendCallAnswer(session, sdp)
    }

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
        if (existing?.callId == callId && existing.relationshipId == relationshipId &&
            existing.peerIdentityId == peerIdentityId && existing.type == type &&
            existing.state in setOf(CallState.CONNECTED, CallState.RECONNECTING)) {
            transition(callId, CallState.RECONNECTING)
            startReconnectTimeout(callId)
            callEventListener?.onRestartOffer(existing, sdpOffer)
            return true
        }

        // Busy — already in a call with someone else or same person
        if (existing != null && existing.state != CallState.OUTGOING_PREPARING) {
            // Check for simultaneous-call collision
            if (existing.peerIdentityId == peerIdentityId &&
                (existing.state == CallState.OUTGOING_SENDING || existing.state == CallState.OUTGOING_CALLING || existing.state == CallState.OUTGOING_RINGING || existing.state == CallState.OUTGOING_PREPARING)
            ) {
                return handleCollision(existing, callId, conversationId, relationshipId, peerIdentityId, type, sdpOffer)
            }

            // Truly busy — different peer or already connected
            Log.i(TAG, "[BUSY] Existing call active; sending BUSY")
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

        Log.i(TAG, "[CALL] Incoming")
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
        if (session.callId != callId || session.state !in setOf(CallState.OUTGOING_SENDING, CallState.OUTGOING_CALLING)) return
        transition(callId, CallState.OUTGOING_RINGING)
        startRingingTimeout(callId)
        Log.d(TAG, "[CALL] Remote ringing")
    }

    suspend fun onRemoteAnswer(callId: String, sdpAnswer: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId || session.state !in setOf(CallState.OUTGOING_SENDING, CallState.OUTGOING_CALLING, CallState.OUTGOING_RINGING, CallState.RECONNECTING)) return

        cancelRingingTimeout()
        if (session.state != CallState.RECONNECTING) transition(callId, CallState.CONNECTING)
        callEventListener?.onRemoteAnswer(session, sdpAnswer)
    }

    suspend fun onRemoteIceCandidate(callId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) {
            Log.w(TAG, "[CALL] Ignored stale ICE candidate")
            return
        }
        callEventListener?.onRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    fun onRemoteConnected(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        // Peer confirms media is flowing
        Log.d(TAG, "[CALL] Remote confirmed connected")
    }

    suspend fun onRemoteEnd(callId: String, reason: CallEndReason) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) {
            Log.w(TAG, "[CALL] Ignored stale end signal")
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
            Log.i(TAG, "[CALL] Connected")
            callEventListener?.onCallConnected(session)
        }
    }

    suspend fun onIceDisconnected(callId: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        if (session.state == CallState.CONNECTED) {
            transition(callId, CallState.RECONNECTING)
            startReconnectTimeout(callId)
            if (session.direction == CallDirection.OUTGOING) callEventListener?.onRestartRequested(session)
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
     * User hangs up the active call. Verifies callId if provided to prevent stale intents from terminating new calls (M10).
     */
    suspend fun hangUp(callId: String? = null) {
        val session = _activeCall.value ?: return
        if (callId != null && session.callId != callId) {
            Log.w(TAG, "[HANGUP IGNORED] Stale call identifier")
            return
        }
        val reason = CallEndReason.LOCAL_HANGUP
        callService.sendEnd(session, reason)
        endCall(session.callId, reason)
    }

    /**
     * Called when media/SDP operations fail (M7).
     */
    suspend fun onCallFailed(callId: String, error: String) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        Log.e(TAG, "[CALL FAILED] Call failed")
        _activeCall.value = session.copy(failureMessage = error)
        cancelRingingTimeout()
        cancelReconnectTimeout()
        transition(callId, CallState.FAILED)
        endCall(callId, CallEndReason.CONNECTION_FAILED)
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
        _activeCall.value = session.copy(
            isSpeakerOn = newSpeaker,
            hasExplicitAudioRouteSelection = true
        )
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
     * Upgrade local call session to VIDEO and enable camera.
     */
    fun enableVideo() {
        val session = _activeCall.value ?: return
        if (session.type != CallType.VIDEO) return
        _activeCall.value = session.copy(isCameraOn = true)
        callEventListener?.onCameraChanged(true)
    }

    // ─── State Machine ───────────────────────────────────────────────────

    private fun isValidTransition(from: CallState, to: CallState): Boolean {
        if (from == to) return true
        return when (from) {
            CallState.IDLE -> to in setOf(CallState.OUTGOING_PREPARING, CallState.INCOMING_RINGING)
            CallState.OUTGOING_PREPARING -> to in setOf(CallState.OUTGOING_SENDING, CallState.ENDING, CallState.FAILED, CallState.ENDED)
            CallState.OUTGOING_SENDING -> to in setOf(CallState.OUTGOING_CALLING, CallState.OUTGOING_RINGING, CallState.CONNECTING, CallState.BUSY, CallState.DECLINED, CallState.ENDING, CallState.ENDED, CallState.FAILED)
            CallState.OUTGOING_CALLING -> to in setOf(CallState.OUTGOING_RINGING, CallState.CONNECTING, CallState.BUSY, CallState.DECLINED, CallState.MISSED, CallState.ENDING, CallState.ENDED, CallState.FAILED)
            CallState.OUTGOING_RINGING -> to in setOf(CallState.CONNECTING, CallState.BUSY, CallState.DECLINED, CallState.MISSED, CallState.ENDING, CallState.ENDED, CallState.FAILED)
            CallState.INCOMING_RINGING -> to in setOf(CallState.CONNECTING, CallState.DECLINED, CallState.MISSED, CallState.BUSY, CallState.ENDING, CallState.ENDED, CallState.FAILED)
            CallState.CONNECTING -> to in setOf(CallState.CONNECTED, CallState.RECONNECTING, CallState.ENDING, CallState.ENDED, CallState.FAILED)
            CallState.CONNECTED -> to in setOf(CallState.RECONNECTING, CallState.ENDING, CallState.ENDED, CallState.FAILED)
            CallState.RECONNECTING -> to in setOf(CallState.CONNECTED, CallState.ENDING, CallState.ENDED, CallState.FAILED)
            CallState.ENDING -> to in setOf(CallState.ENDED, CallState.FAILED)
            CallState.ENDED, CallState.DECLINED, CallState.BUSY, CallState.MISSED, CallState.FAILED -> false
        }
    }

    private fun transition(callId: String, newState: CallState) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return
        if (!isValidTransition(session.state, newState)) {
            Log.w(TAG, "[ILLEGAL TRANSITION REJECTED] Invalid call state transition")
            return
        }
        Log.d(TAG, "[STATE] Call state changed")
        _activeCall.value = session.copy(state = newState)
    }

    private suspend fun endCall(callId: String, reason: CallEndReason) {
        val session = _activeCall.value ?: return
        if (session.callId != callId) return

        cancelRingingTimeout()
        cancelReconnectTimeout()

        val now = System.currentTimeMillis()
        val durationMs = session.connectedAt?.let { now - it }

        val terminalState = if (session.state in setOf(CallState.DECLINED, CallState.BUSY, CallState.MISSED, CallState.FAILED)) {
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

        Log.i(TAG, "[CALL] Ended")

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
        val myId = localIdentityIdProvider()?.ifEmpty { null } ?: localIdentityId
        val weWin = myId < peerIdentityId
        return if (weWin) {
            // Our offer wins — ignore theirs, send BUSY back
            Log.i(TAG, "[COLLISION] We win (our identity < peer). Ignoring incoming offer")
            callService.sendBusy(theirCallId, conversationId, relationshipId, peerIdentityId)
            false
        } else {
            // Their offer wins — cancel ours and accept theirs
            Log.i(TAG, "[COLLISION] They win (peer identity < ours). Superseding our call")
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
            delay(if (_activeCall.value?.state in setOf(CallState.OUTGOING_SENDING, CallState.OUTGOING_CALLING)) SIGNAL_DELIVERY_TIMEOUT_MS else RINGING_TIMEOUT_MS)
            val session = _activeCall.value ?: return@launch
            if (session.callId != callId) return@launch

            // Timeout cleanup must not cancel the coroutine performing it.
            ringingTimeoutJob = null

            when (session.state) {
                CallState.OUTGOING_SENDING, CallState.OUTGOING_CALLING -> {
                    Log.i(TAG, "[TIMEOUT] Peer did not confirm incoming call presentation")
                    onCallFailed(callId, "Could not reach the peer")
                }
                CallState.OUTGOING_RINGING -> {
                    Log.i(TAG, "[TIMEOUT] Outgoing call unanswered")
                    callService.sendEnd(session, CallEndReason.NO_ANSWER)
                    transition(callId, CallState.MISSED)
                    endCall(callId, CallEndReason.NO_ANSWER)
                }
                CallState.INCOMING_RINGING -> {
                    Log.i(TAG, "[TIMEOUT] Incoming call missed")
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
                Log.i(TAG, "[TIMEOUT] Reconnect failed")
                reconnectTimeoutJob = null
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
    fun onRestartRequested(session: CallSession) {}
    fun onRestartOffer(session: CallSession, sdpOffer: String) {}
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
