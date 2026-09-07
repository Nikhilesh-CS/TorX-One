package com.torxone.app.call

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

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
    private val onRemoteTrackReceived: () -> Unit
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
        Log.d(TAG, "Initializing WebRTC PeerConnectionFactory")
        eglBase = EglBase.create()

        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory created successfully")
    }

    suspend fun createPeerConnection() {
        val iceServers = iceServerProvider.getIceServers()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "ICE candidate: ${it.sdpMid}")
                        onIceCandidate(AstraIceCandidate(it.sdpMid, it.sdpMLineIndex, it.sdp))
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE connection state: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> onConnected()
                        PeerConnection.IceConnectionState.DISCONNECTED -> onReconnecting()
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> onDisconnected()
                        else -> {}
                    }
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                    Log.d(TAG, "Peer connection state: $state")
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
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
        )

        Log.d(TAG, "PeerConnection created")
    }

    fun startAudioSession() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        audioSource = peerConnectionFactory?.createAudioSource(constraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio_track_0", audioSource)
        localAudioTrack?.setEnabled(true)

        peerConnection?.addTrack(localAudioTrack, listOf("stream_0"))
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
                    sdp?.let {
                        peerConnection?.setLocalDescription(NoOpSdpObserver(), it)
                        continuation.resumeWith(Result.success(
                            AstraSessionDescription("offer", it.description)
                        ))
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
                    sdp?.let {
                        peerConnection?.setLocalDescription(NoOpSdpObserver(), it)
                        continuation.resumeWith(Result.success(
                            AstraSessionDescription("answer", it.description)
                        ))
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
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection?.setLocalDescription(NoOpSdpObserver(), it)
                        continuation.resumeWith(Result.success(
                            AstraSessionDescription("offer", it.description)
                        ))
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

    fun close() {
        Log.d(TAG, "Closing WebRTC client")
        localAudioTrack?.setEnabled(false)
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
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
