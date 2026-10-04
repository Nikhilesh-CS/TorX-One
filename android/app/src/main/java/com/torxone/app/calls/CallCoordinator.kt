package com.torxone.app.calls

import android.content.Context
import android.util.Log
import com.torxone.app.data.dao.ContactDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentHashMap

/**
 * CallCoordinator — Bridges CallManager state events to WebRTC media engine,
 * AudioRouteManager, CallNotificationManager, and TorXCallService foreground service.
 *
 * Implements CallEventListener.
 */
class CallCoordinator(
    private val context: Context,
    private val callManager: CallManager,
    private val webRtcClient: WebRtcClient,
    private val audioRouteManager: AudioRouteManager,
    private val callNotificationManager: CallNotificationManager,
    private val contactDao: ContactDao,
    private val relayOnlyProvider: suspend () -> Boolean = { false }
) : CallEventListener {

    companion object {
        private const val TAG = "CallCoordinator"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val remoteOffers = ConcurrentHashMap<String, String>()

    init {
        callManager.callEventListener = this
        scope.launch {
            callManager.activeCall.collect { session ->
                if (session == null || session.direction != CallDirection.OUTGOING) return@collect
                val status = when (session.state) {
                    CallState.OUTGOING_PREPARING -> "Preparing call…"
                    CallState.OUTGOING_SENDING -> "Sending call request…"
                    CallState.OUTGOING_CALLING -> "Calling…"
                    CallState.OUTGOING_RINGING -> "Ringing…"
                    CallState.CONNECTING -> "Connecting…"
                    CallState.RECONNECTING -> "Reconnecting…"
                    else -> return@collect
                }
                val contact = contactDao.getByRelationshipId(session.relationshipId)
                if (callManager.activeCall.value?.callId == session.callId && callManager.activeCall.value?.state == session.state)
                    callNotificationManager.showActiveCallNotification(session.callId,
                        contact?.displayName ?: session.peerIdentityId.take(8), session.type, status)
            }
        }
    }

    private suspend fun prepareMedia(session: CallSession): Boolean {
        return try {
            val relayOnly = relayOnlyProvider()
            if (callManager.activeCall.value?.callId != session.callId) return false
            webRtcClient.createPeerConnection(session.callId, relayOnly)
            TorXCallService.start(context)
            webRtcClient.createAudioTrack()
            if (session.type == CallType.VIDEO) webRtcClient.createVideoTrack()
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            scope.launch {
                callManager.onCallFailed(session.callId,
                    if (error.message == "Maximum Call Privacy requires a configured TURN relay")
                        error.message!! else "Could not prepare call media")
            }
            false
        }
    }

    override fun onCreateOffer(session: CallSession) {
        scope.launch {
        Log.d(TAG, "onCreateOffer")
        if (!prepareMedia(session)) return@launch
        webRtcClient.createOffer { sdp ->
            scope.launch {
                callManager.onLocalOfferReady(session.callId, sdp)
            }
        }
        scope.launch {
            val contact = contactDao.getByRelationshipId(session.relationshipId)
            val peerName = contact?.displayName ?: session.peerIdentityId.take(8)
            callNotificationManager.showActiveCallNotification(session.callId, peerName, session.type, "Preparing call…")
        }
        }
    }

    override fun onIncomingCall(session: CallSession, sdpOffer: String) {
        Log.d(TAG, "onIncomingCall")
        remoteOffers[session.callId] = sdpOffer
        scope.launch {
            val contact = contactDao.getByRelationshipId(session.relationshipId)
            val peerName = contact?.displayName ?: session.peerIdentityId.take(8)
            callNotificationManager.showIncomingCallNotification(session.callId, peerName, session.type)
        }
    }

    override fun onCreateAnswer(session: CallSession) {
        scope.launch {
        Log.d(TAG, "onCreateAnswer")
        callNotificationManager.cancelIncomingNotification()
        if (!prepareMedia(session)) return@launch
        val sdpOffer = remoteOffers.remove(session.callId)
        if (sdpOffer != null) {
            webRtcClient.createAnswer(sdpOffer) { sdp ->
                scope.launch {
                    callManager.onLocalAnswerReady(session.callId, sdp)
                }
            }
        } else {
            Log.e(TAG, "Cannot create answer: missing remote SDP offer")
            scope.launch { callManager.onCallFailed(session.callId, "Remote SDP offer is unavailable") }
            webRtcClient.closePeerConnection(session.callId)
            audioRouteManager.stopCallAudio()
            callNotificationManager.cancelIncomingNotification()
            TorXCallService.stop(context)
            return@launch
        }
        scope.launch {
            val contact = contactDao.getByRelationshipId(session.relationshipId)
            val peerName = contact?.displayName ?: session.peerIdentityId.take(8)
            callNotificationManager.showActiveCallNotification(session.callId, peerName, session.type, "Connecting…")
        }
        }
    }

    override fun onRemoteAnswer(session: CallSession, sdpAnswer: String) {
        Log.d(TAG, "onRemoteAnswer")
        webRtcClient.setRemoteAnswer(sdpAnswer)
    }

    override fun onRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        webRtcClient.addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    override fun onRestartRequested(session: CallSession) {
        webRtcClient.createOffer(iceRestart = true) { sdp ->
            scope.launch { callManager.onLocalRestartOfferReady(session.callId, sdp) }
        }
    }

    override fun onRestartOffer(session: CallSession, sdpOffer: String) {
        webRtcClient.createAnswer(sdpOffer) { sdp ->
            scope.launch { callManager.onLocalRestartAnswerReady(session.callId, sdp) }
        }
    }

    override fun onCallConnected(session: CallSession) {
        Log.d(TAG, "onCallConnected")
        callNotificationManager.cancelIncomingNotification()
        val selectedSpeaker = if (session.hasExplicitAudioRouteSelection) {
            session.isSpeakerOn
        } else {
            session.type == CallType.VIDEO
        }
        audioRouteManager.startCallAudio(isSpeaker = selectedSpeaker)
        scope.launch {
            val contact = contactDao.getByRelationshipId(session.relationshipId)
            val peerName = contact?.displayName ?: session.peerIdentityId.take(8)
            callNotificationManager.showActiveCallNotification(session.callId, peerName, session.type)
        }
    }

    override fun onCallEnded(session: CallSession) {
        Log.d(TAG, "onCallEnded")
        remoteOffers.remove(session.callId)
        webRtcClient.release()
        audioRouteManager.stopCallAudio()
        callNotificationManager.cancelAll()
        TorXCallService.stop(context)
    }

    override fun onMuteChanged(muted: Boolean) {
        webRtcClient.setMicrophoneEnabled(!muted)
    }

    override fun onSpeakerChanged(speaker: Boolean) {
        audioRouteManager.setRoute(if (speaker) AudioRouteManager.AudioRoute.SPEAKER else AudioRouteManager.AudioRoute.EARPIECE)
    }

    override fun onCameraChanged(enabled: Boolean) {
        webRtcClient.setCameraEnabled(enabled)
    }

    override fun onSwitchCamera() {
        webRtcClient.switchCamera()
    }
}
