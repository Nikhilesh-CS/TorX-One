package com.torxone.app.call

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Production WebRTC client wrapping Google's native PeerConnection.
 * Handles: PeerConnectionFactory init, audio track creation, offer/answer SDP,
 * ICE candidate trickle, and connection state monitoring.
 */
class WebRtcClient(
    private val context: Context,
    private val iceServerProvider: IceServerProvider,
    private val onIceCandidate: (AstraIceCandidate) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onReconnecting: () -> Unit,
    private val onRemoteTrackReceived: () -> Unit,
    private val onIceChecking: (() -> Unit)? = null,
    private val onIceGatheringChange: ((PeerConnection.IceGatheringState) -> Unit)? = null,
    private val privacyPolicy: CallPrivacyPolicy = CallPrivacyPolicy.DEFAULT
) {
    companion object {
        private const val TAG = "WebRtcClient"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var eglBase: EglBase? = null
    private var started = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    fun initialize() {
        Log.d(TAG, "Fetching WebRTC PeerConnectionFactory from WebRtcFactoryProvider")
        eglBase = WebRtcFactoryProvider.getEglBase()
        peerConnectionFactory = WebRtcFactoryProvider.getOrCreateFactory(context)
        Log.d(TAG, "PeerConnectionFactory retrieved successfully")
    }

    suspend fun createPeerConnection() {
        val iceServers = iceServerProvider.getIceServers()
        val factory = checkNotNull(peerConnectionFactory) { "PeerConnectionFactory is not initialized" }
        // Set ICE transport type based on privacy policy
        val transportType = if (!privacyPolicy.allowDirectP2P && !privacyPolicy.allowSrflxCandidates) {
            // Strict mode: relay only
            PeerConnection.IceTransportsType.RELAY
        } else {
            PeerConnection.IceTransportsType.ALL
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = transportType
        }

        peerConnection = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        val candidateType = CallPrivacyPolicy.getCandidateType(it.sdp) ?: "unknown"
                        // Filter candidates based on privacy policy
                        if (!privacyPolicy.shouldSignalCandidate(it.sdp)) {
                            Log.d(TAG, "ICE candidate filtered by privacy policy: type=$candidateType mid=${it.sdpMid}")
                            CallSecurityLogger.logSecurityEvent(
                                CallSecurityLogger.EVENT_PRIVATE_IP_CANDIDATE_STRIPPED,
                                callId = null,
                                peerKey = null,
                                detail = "type=$candidateType"
                            )
                            return
                        }
                        Log.d(TAG, "ICE candidate signaled: type=$candidateType mid=${it.sdpMid}")
                        onIceCandidate(AstraIceCandidate(it.sdpMid, it.sdpMLineIndex, it.sdp))
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CHECKING -> onIceChecking?.invoke()
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> onConnected()
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> onReconnecting()
                        PeerConnection.IceConnectionState.CLOSED -> onDisconnected()
                        else -> {}
                    }
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                    Log.d(TAG, "Peer connection state: $state")
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "ICE gathering state: $state")
                    state?.let { onIceGatheringChange?.invoke(it) }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    Log.d(TAG, "Remote track received")
                    val track = receiver?.track()
                    if (track is AudioTrack) {
                        track.setEnabled(true)
                        Log.d(TAG, "Remote audio track enabled")
                        onRemoteTrackReceived()
                    }
                }
            }
        ) ?: error("PeerConnection creation returned null")

        Log.d(TAG, "PeerConnection created")
    }

    fun startAudioSession() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        val factory = checkNotNull(peerConnectionFactory) { "PeerConnectionFactory is not initialized" }
        val connection = checkNotNull(peerConnection) { "PeerConnection is not initialized" }
        audioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("audio_track_0", checkNotNull(audioSource) { "AudioSource creation failed" })
        checkNotNull(localAudioTrack) { "AudioTrack creation failed" }.apply { setEnabled(true) }

        checkNotNull(connection.addTrack(localAudioTrack, listOf("stream_0"))) { "Adding local audio track failed" }
        started = true
        Log.d(TAG, "Audio session started with echo cancellation, AGC, noise suppression")
    }

    suspend fun createOffer(): AstraSessionDescription {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let { localSdp ->
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onSetSuccess() {
                                continuation.resumeWith(Result.success(
                                    AstraSessionDescription("offer", localSdp.description)
                                ))
                            }
                            override fun onCreateFailure(error: String?) {}
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "Set local description failed: $error")
                                continuation.resumeWith(Result.failure(RuntimeException("Set local description failed: $error")))
                            }
                        }, localSdp)
                    }
                }
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create offer failed: $error")
                    continuation.resumeWith(Result.failure(RuntimeException("Offer creation failed: $error")))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, sdpConstraints)
        }
    }

    suspend fun createAnswer(): AstraSessionDescription {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let { localSdp ->
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onSetSuccess() {
                                continuation.resumeWith(Result.success(
                                    AstraSessionDescription("answer", localSdp.description)
                                ))
                            }
                            override fun onCreateFailure(error: String?) {}
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "Set local description failed: $error")
                                continuation.resumeWith(Result.failure(RuntimeException("Set local description failed: $error")))
                            }
                        }, localSdp)
                    }
                }
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create answer failed: $error")
                    continuation.resumeWith(Result.failure(RuntimeException("Answer creation failed: $error")))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, sdpConstraints)
        }
    }

    fun setRemoteDescription(description: AstraSessionDescription) {
        val type = when (description.type.lowercase()) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> return
        }
        val sdp = SessionDescription(type, description.description)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully, applying pending ICE candidates")
                pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                pendingIceCandidates.clear()
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
            }
        }, sdp)
        Log.d(TAG, "Remote description set: ${description.type}")
    }

    suspend fun setRemoteDescriptionSuspend(offer: AstraSessionDescription) {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val type = SessionDescription.Type.OFFER
            val sdp = SessionDescription(type, offer.description)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onSetSuccess() {
                    Log.d(TAG, "Renegotiation remote description set successfully")
                    pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                    pendingIceCandidates.clear()
                    continuation.resumeWith(Result.success(Unit))
                }
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Failed to set remote description for renegotiation: $error")
                    continuation.resumeWith(Result.failure(RuntimeException("Set remote description failed: $error")))
                }
            }, sdp)
        }
    }

    fun addIceCandidate(candidate: AstraIceCandidate) {
        val iceCandidate = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        if (peerConnection?.remoteDescription == null) {
            Log.d(TAG, "Queuing ICE candidate (waiting for remote SDP)")
            pendingIceCandidates.add(iceCandidate)
        } else {
            peerConnection?.addIceCandidate(iceCandidate)
        }
    }

    suspend fun performIceRestart(): AstraSessionDescription {
        pendingIceCandidates.clear() // Clear any old candidates from the previous connection
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let { localSdp ->
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription?) {}
                            override fun onSetSuccess() {
                                continuation.resumeWith(Result.success(
                                    AstraSessionDescription("offer", localSdp.description)
                                ))
                            }
                            override fun onCreateFailure(error: String?) {}
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "Set local description failed: $error")
                                continuation.resumeWith(Result.failure(RuntimeException("Set local description failed: $error")))
                            }
                        }, localSdp)
                    }
                }
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create ICE Restart offer failed: $error")
                    continuation.resumeWith(Result.failure(RuntimeException("ICE Restart Offer creation failed: $error")))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, sdpConstraints)
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        Log.d(TAG, "Mic enabled: $enabled")
    }

    fun getStats(callback: (org.webrtc.RTCStatsReport?) -> Unit) {
        val pc = peerConnection
        if (pc == null) {
            callback(null)
            return
        }
        try {
            pc.getStats { report ->
                callback(report)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying WebRTC stats: ${e.message}")
            callback(null)
        }
    }

    fun close() {
        Log.d(TAG, "Closing WebRTC client (releasing connection and tracks)")
        localAudioTrack?.setEnabled(false)
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        peerConnectionFactory = null
        eglBase = null
        started = false
    }

    private class NoOpSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onCreateFailure(error: String?) {
            Log.e("WebRtcClient", "SDP failure: $error")
        }
        override fun onSetSuccess() {}
        override fun onSetFailure(error: String?) {
            Log.e("WebRtcClient", "SDP set failure: $error")
        }
    }
}

data class AstraSessionDescription(
    val type: String,
    val description: String
)

data class AstraIceCandidate(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val sdp: String
)
