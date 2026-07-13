package com.torxone.app.call

import android.content.Context
import org.json.JSONObject

class WebRtcClient(
    private val context: Context,
    private val onIceCandidate: (AstraIceCandidate) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private var started = false

    fun initialize() {
        // Native WebRTC is intentionally not loaded here. The tested AAR crashes
        // this RMX5070 device in JNI_OnLoad, which is process-fatal and cannot be caught.
    }

    fun startAudioSession() {
        started = true
    }

    suspend fun createOffer(): AstraSessionDescription {
        return AstraSessionDescription(
            type = "offer",
            description = JSONObject()
                .put("engine", "webrtc-placeholder")
                .put("audio", true)
                .put("nativeRuntime", false)
                .toString()
        )
    }

    suspend fun createAnswer(): AstraSessionDescription {
        return AstraSessionDescription(
            type = "answer",
            description = JSONObject()
                .put("engine", "webrtc-placeholder")
                .put("audio", true)
                .put("nativeRuntime", false)
                .toString()
        )
    }

    fun setRemoteDescription(description: AstraSessionDescription) {
        // Signaling is wired; native media runtime is disabled until a compatible AAR is selected.
    }

    fun addIceCandidate(candidate: AstraIceCandidate) {
        // No-op while native runtime is disabled.
    }

    fun close() {
        started = false
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
