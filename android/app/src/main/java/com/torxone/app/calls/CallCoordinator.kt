package com.torxone.app.calls

import android.content.Context
import android.util.Log
import com.torxone.app.data.dao.ContactDao
import kotlinx.coroutines.*
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
    private val contactDao: ContactDao
) : CallEventListener {

    companion object {
        private const val TAG = "CallCoordinator"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val remoteOffers = ConcurrentHashMap<String, String>()

    init {
        callManager.callEventListener = this
    }

    override fun onCreateOffer(session: CallSession) {
        Log.d(TAG, "onCreateOffer: callId=${session.callId}")
        TorXCallService.start(context)
        webRtcClient.createPeerConnection(session.callId)
        webRtcClient.createAudioTrack()
        if (session.type == CallType.VIDEO) {
            webRtcClient.createVideoTrack()
        }
        webRtcClient.createOffer { sdp ->
            scope.launch {
                callManager.onLocalOfferReady(session.callId, sdp)
            }
        }
        scope.launch {
            val contact = contactDao.getByRelationshipId(session.relationshipId)
            val peerName = contact?.displayName ?: session.peerIdentityId.take(8)
            callNotificationManager.showActiveCallNotification(session.callId, peerName, session.type, "Calling…")
        }
    }

    override fun onIncomingCall(session: CallSession, sdpOffer: String) {
        Log.d(TAG, "onIncomingCall: callId=${session.callId}")
        remoteOffers[session.callId] = sdpOffer
        scope.launch {
            val contact = contactDao.getByRelationshipId(session.relationshipId)
            val peerName = contact?.displayName ?: session.peerIdentityId.take(8)
            callNotificationManager.showIncomingCallNotification(session.callId, peerName, session.type)
        }
    }

    override fun onCreateAnswer(session: CallSession) {
        Log.d(TAG, "onCreateAnswer: callId=${session.callId}")
        callNotificationManager.cancelIncomingNotification()
        TorXCallService.start(context)
        webRtcClient.createPeerConnection(session.callId)
        webRtcClient.createAudioTrack()
        if (session.type == CallType.VIDEO) {
            webRtcClient.createVideoTrack()
        }
        val sdpOffer = remoteOffers.remove(session.callId)
        if (sdpOffer != null) {
            webRtcClient.createAnswer(sdpOffer) { sdp ->
                scope.launch {
                    callManager.onLocalAnswerReady(session.callId, sdp)
                }
            }
        } else {
            Log.e(TAG, "Cannot create answer: missing remote SDP offer for ${session.callId}")
        }
        scope.launch {
            val contact = contactDao.getByRelationshipId(session.relationshipId)
            val peerName = contact?.displayName ?: session.peerIdentityId.take(8)
            callNotificationManager.showActiveCallNotification(session.callId, peerName, session.type, "Connecting…")
        }
    }

    override fun onRemoteAnswer(session: CallSession, sdpAnswer: String) {
        Log.d(TAG, "onRemoteAnswer: callId=${session.callId}")
        webRtcClient.setRemoteAnswer(sdpAnswer)
    }

    override fun onRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        webRtcClient.addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    override fun onCallConnected(session: CallSession) {
        Log.d(TAG, "onCallConnected: callId=${session.callId}")
        callNotificationManager.cancelIncomingNotification()
        audioRouteManager.startCallAudio(isSpeaker = session.type == CallType.VIDEO)
        scope.launch {
            val contact = contactDao.getByRelationshipId(session.relationshipId)
            val peerName = contact?.displayName ?: session.peerIdentityId.take(8)
            callNotificationManager.showActiveCallNotification(session.callId, peerName, session.type)
        }
    }

    override fun onCallEnded(session: CallSession) {
        Log.d(TAG, "onCallEnded: callId=${session.callId}")
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
