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
 * Standard mode uses LAN/STUN with optional TURN fallback. Direct peers can learn
 * network addresses. Maximum Call Privacy uses relay candidates only and omits
 * STUN. Tor carries authenticated signaling for both modes.
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
    private var mediaChannel: DataChannel? = null
    private var mediaPipe: com.torxone.app.media.RtcMediaPipe? = null
    private var relayOnly = false
    var onMediaFrameReceived: (suspend (String, ByteArray) -> Boolean)? = null

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
     * Create a PeerConnection from operator-supplied ICE configuration.
     * The policy is captured for this session; Maximum Call Privacy requires TURN.
     */
    fun createPeerConnection(callId: String, relayOnly: Boolean = false) {
        this.relayOnly = relayOnly
        activeCallId = callId
        val factory = peerConnectionFactory
            ?: throw IllegalStateException("PeerConnectionFactory not initialized")

        val iceServers = buildIceServers()
        require(!relayOnly || iceServers.any { server -> server.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } }) {
            "Maximum Call Privacy requires a configured TURN relay"
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (!RtcIcePolicy.allowsCandidate(candidate.sdp, relayOnly)) return
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
                            auditRelaySelection(cid)
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
            override fun onDataChannel(channel: DataChannel) { attachMediaChannel(callId, channel) }
            override fun onRenegotiationNeeded() {}
            override fun onAddStream(stream: MediaStream) {}
        })

        checkNotNull(peerConnection) { "Could not create WebRTC peer connection" }
        if (callManager.activeCall.value?.direction == CallDirection.OUTGOING) {
            val channel = peerConnection!!.createDataChannel("torx-media-v1", DataChannel.Init())
            if (channel != null) attachMediaChannel(callId, channel)
        }

        Log.i(TAG, "PeerConnection created; ICE=${if (relayOnly) "RELAY" else "ALL"}")
    }

    private fun attachMediaChannel(callId: String, channel: DataChannel) {
        val session = callManager.activeCall.value
        if (session?.callId != callId || channel.label() != "torx-media-v1" || mediaChannel != null) {
            channel.close()
            channel.dispose()
            return
        }
        mediaChannel = channel
        val pipe = com.torxone.app.media.RtcMediaPipe(
            scope,
            sendPacket = { packet ->
                channel.state() == DataChannel.State.OPEN &&
                    channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(packet), true))
            },
            bufferedBytes = { channel.bufferedAmount() },
            acceptFrame = { frame ->
                if (activeCallId == callId && callManager.activeCall.value?.callId == callId)
                    onMediaFrameReceived?.invoke(session.relationshipId, frame) ?: false else false
            }
        )
        mediaPipe = pipe
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.CLOSED) pipe.close()
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = buffer.data
                if (!buffer.binary || data.remaining() > com.torxone.app.media.RtcMediaPipe.FRAGMENT_BYTES + 21) {
                    pipe.close()
                    return
                }
                val packet = ByteArray(data.remaining())
                data.get(packet)
                pipe.receive(packet)
            }
        })
    }

    suspend fun sendDedicatedMedia(relationshipId: String, frame: ByteArray): Boolean {
        val session = callManager.activeCall.value ?: return false
        if (session.relationshipId != relationshipId || session.state != CallState.CONNECTED ||
            mediaChannel?.state() != DataChannel.State.OPEN) return false
        return mediaPipe?.send(frame) ?: false
    }

    private fun auditRelaySelection(callId: String) {
        peerConnection?.getStats(object : RTCStatsCollectorCallback {
            override fun onStatsDelivered(report: RTCStatsReport) {
            report.statsMap.values.filter {
                it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true
            }.forEach { pair ->
                val local = report.statsMap[pair.members["localCandidateId"] as? String]
                val remote = report.statsMap[pair.members["remoteCandidateId"] as? String]
                if (local?.members?.get("candidateType") == "relay" && remote?.members?.get("candidateType") == "relay") {
                    Log.i(TAG, "Selected ICE pair: relay/relay")
                } else if (relayOnly && local != null && remote != null) {
                    scope.launch { callManager.onCallFailed(callId, "Non-relay media path rejected") }
                } else if (local != null && remote != null) {
                    Log.i(TAG, "Selected ICE pair: ${local.members["candidateType"]}/${remote.members["candidateType"]}")
                }
            }
            }
        })
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

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    @Volatile
    private var isRemoteDescriptionSet = false

    private fun drainPendingIceCandidates() {
        synchronized(pendingIceCandidates) {
            val pc = peerConnection
            if (pc != null && isRemoteDescriptionSet) {
                Log.d(TAG, "[ICE DRAIN] Flushing ${pendingIceCandidates.size} buffered ICE candidates")
                for (cand in pendingIceCandidates) {
                    pc.addIceCandidate(cand)
                }
                pendingIceCandidates.clear()
            }
        }
    }

    // ─── SDP Negotiation ─────────────────────────────────────────────────

    fun createOffer(iceRestart: Boolean = false, callback: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            val receiveMedia = (callManager.activeCall.value?.type != CallType.DATA).toString()
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", receiveMedia))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", receiveMedia))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        callback(sdp.description)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "setLocalDescription for offer failed: $error")
                        scope.launch {
                            activeCallId?.let { callManager.onCallFailed(it, "Set local offer failed: $error") }
                        }
                    }
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Create offer failed: $error")
                scope.launch {
                    activeCallId?.let { callManager.onCallFailed(it, "Create offer failed: $error") }
                }
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    fun createAnswer(sdpOffer: String, callback: (String) -> Unit) {
        if (!allowsOnlyRelayCandidates(sdpOffer)) return
        val offerSdp = SessionDescription(SessionDescription.Type.OFFER, sdpOffer)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                drainPendingIceCandidates()
                val constraints = MediaConstraints().apply {
                    val receiveMedia = (callManager.activeCall.value?.type != CallType.DATA).toString()
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", receiveMedia))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", receiveMedia))
                }
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                callback(sdp.description)
                            }
                            override fun onSetFailure(error: String) {
                                Log.e(TAG, "setLocalDescription for answer failed: $error")
                                scope.launch {
                                    activeCallId?.let { callManager.onCallFailed(it, "Set local answer failed: $error") }
                                }
                            }
                            override fun onCreateSuccess(sdp: SessionDescription) {}
                            override fun onCreateFailure(error: String) {}
                        }, sdp)
                    }
                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "Create answer failed: $error")
                        scope.launch {
                            activeCallId?.let { callManager.onCallFailed(it, "Create answer failed: $error") }
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String) {}
                }, constraints)
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Set remote offer failed: $error")
                scope.launch {
                    activeCallId?.let { callManager.onCallFailed(it, "Set remote offer failed: $error") }
                }
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, offerSdp)
    }

    fun setRemoteAnswer(sdpAnswer: String) {
        if (!allowsOnlyRelayCandidates(sdpAnswer)) return
        val answerSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                drainPendingIceCandidates()
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Set remote answer failed: $error")
                scope.launch {
                    activeCallId?.let { callManager.onCallFailed(it, "Set remote answer failed: $error") }
                }
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, answerSdp)
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        if (!RtcIcePolicy.allowsCandidate(candidate, relayOnly)) return
        val iceCandidate = IceCandidate(sdpMid ?: "", sdpMLineIndex, candidate)
        synchronized(pendingIceCandidates) {
            val pc = peerConnection
            if (pc != null && isRemoteDescriptionSet) {
                pc.addIceCandidate(iceCandidate)
            } else {
                Log.d(TAG, "[ICE BUFFER] Buffering remote candidate until remote description is set")
                pendingIceCandidates.add(iceCandidate)
            }
        }
    }

    private fun allowsOnlyRelayCandidates(sdp: String): Boolean {
        val valid = RtcIcePolicy.allowsSdp(sdp, relayOnly)
        if (!valid) scope.launch { activeCallId?.let { callManager.onCallFailed(it, "Non-relay media offer rejected") } }
        return valid
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

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        fun urls(value: String): List<String> = value.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)

        val servers = mutableListOf<PeerConnection.IceServer>()
        RtcIcePolicy.stunUrls(com.torxone.app.BuildConfig.TORX_STUN_URLS, relayOnly).forEach { url ->
            require(url.startsWith("stun:") || url.startsWith("stuns:")) {
                "Invalid STUN URL scheme"
            }
            servers += PeerConnection.IceServer.builder(url).createIceServer()
        }

        val turnUrls = urls(com.torxone.app.BuildConfig.TORX_TURN_URLS)
        if (turnUrls.isNotEmpty()) {
            val username = com.torxone.app.BuildConfig.TORX_TURN_USERNAME
            val credential = com.torxone.app.BuildConfig.TORX_TURN_CREDENTIAL
            if (username.isBlank() || credential.isBlank()) {
                Log.w(TAG, "TURN fallback disabled: client credentials are missing")
                return servers
            }
            turnUrls.forEach { url ->
                if (!url.startsWith("turn:") && !url.startsWith("turns:")) {
                    Log.w(TAG, "Ignoring TURN endpoint with invalid URL scheme")
                    return@forEach
                }
                servers += PeerConnection.IceServer.builder(url)
                    .setUsername(username)
                    .setPassword(credential)
                    .createIceServer()
            }
        }
        return servers
    }

    // ─── Teardown ────────────────────────────────────────────────────────

    /**
     * Release ALL WebRTC resources for the current call.
     * Must be called on every call end path.
     */
    fun release() {
        mediaPipe?.close()
        mediaPipe = null
        mediaChannel?.unregisterObserver()
        mediaChannel?.close()
        mediaChannel?.dispose()
        mediaChannel = null
        activeCallId = null
        synchronized(pendingIceCandidates) {
            pendingIceCandidates.clear()
            isRemoteDescriptionSet = false
        }

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

        isRemoteDescriptionSet = false
        synchronized(pendingIceCandidates) {
            pendingIceCandidates.clear()
        }

        Log.i(TAG, "WebRTC resources released")
    }

    fun closePeerConnection(callId: String) {
        if (activeCallId != null && activeCallId != callId) return
        release()
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
