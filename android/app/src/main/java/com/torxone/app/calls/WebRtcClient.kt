package com.torxone.app.calls

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRtcClient — WebRTC media engine for TorX calls.
 *
 * Architecture:
 *   CallManager → WebRtcClient
 *                 ├── PeerConnectionFactory
 *                 ├── PeerConnection
 *                 ├── Local AudioTrack / VideoTrack
 *                 ├── ICE candidate gathering
 *                 └── Connection state callbacks → CallManager
 *
 * Invariant: No UI component manipulates PeerConnection directly.
 * TorX treats WebRTC purely as a media engine underneath CallManager.
 *
 * Privacy: No public STUN by default. First milestone uses host ICE candidates
 * only (direct/local connectivity). Internet-wide calls require TorX-controlled
 * TURN/relay infrastructure in a future milestone.
 */
class WebRtcClient(
    private val context: Context,
    private val callManager: CallManager
) {
    companion object {
        private const val TAG = "WebRtcClient"
        private const val AUDIO_TRACK_ID = "torx-audio-0"
        private const val VIDEO_TRACK_ID = "torx-video-0"
        private const val LOCAL_STREAM_ID = "torx-stream-0"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localVideoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localEglBase: EglBase? = null
    private var activeCallId: String? = null
    private var remoteVideoTrack: VideoTrack? = null

    var remoteVideoSink: VideoSink? = null
        set(value) {
            val old = field
            if (old != null && remoteVideoTrack != null) {
                try { remoteVideoTrack?.removeSink(old) } catch (_: Exception) {}
            }
            field = value
            if (value != null && remoteVideoTrack != null) {
                try { remoteVideoTrack?.addSink(value) } catch (_: Exception) {}
            }
        }

    var localVideoSink: VideoSink? = null
        set(value) {
            val old = field
            if (old != null && localVideoTrack != null) {
                try { localVideoTrack?.removeSink(old) } catch (_: Exception) {}
            }
            field = value
            if (value != null && localVideoTrack != null) {
                try { localVideoTrack?.addSink(value) } catch (_: Exception) {}
            }
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Initialize the WebRTC factory. Call once at Application startup or when first call starts.
     */
    fun initialize() {
        if (peerConnectionFactory != null) return

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        localEglBase = EglBase.create()

        val encoderFactory = DefaultVideoEncoderFactory(
            localEglBase!!.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(localEglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(
                JavaAudioDeviceModule.builder(context)
                    .createAudioDeviceModule()
            )
            .createPeerConnectionFactory()

        Log.i(TAG, "WebRTC PeerConnectionFactory initialized")
    }

    fun getEglBase(): EglBase? = localEglBase

    /**
     * Create a PeerConnection with host-only ICE (no public STUN).
     * This restricts first milestone to direct-reachable peers.
     */
    fun createPeerConnection(callId: String) {
        activeCallId = callId
        val factory = peerConnectionFactory
            ?: throw IllegalStateException("PeerConnectionFactory not initialized")

        // Privacy: No public STUN servers. Host candidates only.
        val iceServers = emptyList<PeerConnection.IceServer>()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val cid = activeCallId ?: return
                scope.launch {
                    callManager.onLocalIceCandidate(cid, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                val cid = activeCallId ?: return
                Log.d(TAG, "[ICE] State changed: $state for call=${cid.take(8)}")
                scope.launch {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            callManager.onIceConnected(cid)
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            callManager.onIceDisconnected(cid)
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            callManager.onIceFailed(cid)
                        }
                        else -> {}
                    }
                }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "[TRACK] Remote video track added")
                    remoteVideoTrack = track
                    remoteVideoSink?.let { track.addSink(it) }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "[TRACK] Remote video track via onTrack")
                    remoteVideoTrack = track
                    remoteVideoSink?.let { track.addSink(it) }
                }
            }

            // Required but unused observer methods
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddStream(stream: MediaStream) {}
        })

        Log.i(TAG, "PeerConnection created for call=$callId (host ICE only)")
    }

    // ─── Audio Track ─────────────────────────────────────────────────────

    fun createAudioTrack() {
        val factory = peerConnectionFactory ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        localAudioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, localAudioSource)
        localAudioTrack?.setEnabled(true)

        peerConnection?.addTrack(localAudioTrack, listOf(LOCAL_STREAM_ID))
        Log.d(TAG, "Audio track created and added to peer connection")
    }

    // ─── Video Track ─────────────────────────────────────────────────────

    fun createVideoTrack() {
        val factory = peerConnectionFactory ?: return
        val egl = localEglBase ?: return

        videoCapturer = createCameraCapturer()
        if (videoCapturer == null) {
            Log.w(TAG, "No camera available")
            return
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
        localVideoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)
        videoCapturer!!.startCapture(640, 480, 30)

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, localVideoSource)
        localVideoTrack?.setEnabled(true)
        localVideoSink?.let { localVideoTrack?.addSink(it) }

        peerConnection?.addTrack(localVideoTrack, listOf(LOCAL_STREAM_ID))
        Log.d(TAG, "Video track created and added to peer connection")
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        // Prefer front camera
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
        }
        // Fallback to any camera
        for (name in enumerator.deviceNames) {
            return enumerator.createCapturer(name, null)
        }
        return null
    }

    // ─── SDP Negotiation ─────────────────────────────────────────────────

    fun createOffer(callback: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(noOpSdpObserver, sdp)
                callback(sdp.description)
            }
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Create offer failed: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    fun createAnswer(sdpOffer: String, callback: (String) -> Unit) {
        val offerSdp = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        peerConnection?.setLocalDescription(noOpSdpObserver, sdp)
                        callback(sdp.description)
                    }
                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "Create answer failed: $error")
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String) {}
                }, constraints)
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Set remote offer failed: $error")
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, offerSdp)
    }

    fun setRemoteAnswer(sdpAnswer: String) {
        val answerSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer)
        peerConnection?.setRemoteDescription(noOpSdpObserver, answerSdp)
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val iceCandidate = IceCandidate(sdpMid ?: "", sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    // ─── Media Controls ──────────────────────────────────────────────────

    fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        Log.d(TAG, "Microphone ${if (enabled) "enabled" else "muted"}")
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        if (enabled) {
            videoCapturer?.startCapture(640, 480, 30)
        } else {
            try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        }
        Log.d(TAG, "Camera ${if (enabled) "enabled" else "disabled"}")
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                Log.d(TAG, "Camera switched to ${if (isFrontCamera) "front" else "back"}")
            }
            override fun onCameraSwitchError(error: String) {
                Log.e(TAG, "Camera switch failed: $error")
            }
        })
    }

    // ─── Teardown ────────────────────────────────────────────────────────

    /**
     * Release ALL WebRTC resources for the current call.
     * Must be called on every call end path.
     */
    fun release() {
        activeCallId = null

        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        videoCapturer?.dispose()
        videoCapturer = null

        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        remoteVideoTrack = null
        localVideoSource?.dispose()
        localVideoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        Log.i(TAG, "WebRTC resources released")
    }

    /**
     * Full shutdown — call only when application is shutting down.
     */
    fun destroy() {
        release()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        localEglBase?.release()
        localEglBase = null
        scope.cancel()
        Log.i(TAG, "WebRTC factory destroyed")
    }

    private val noOpSdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) { Log.e(TAG, "SDP op failed: $error") }
        override fun onSetFailure(error: String) { Log.e(TAG, "SDP op failed: $error") }
    }
}
