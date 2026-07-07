package com.astramesh.app.call

import com.astramesh.app.data.ContactEntity
import com.astramesh.app.network.Transport

enum class CallEngineType {
    LAN_AUDIO,
    BLUETOOTH_WALKIE_TALKIE,
    WEBRTC,
    VOICE_NOTE,
    DISABLED
}

data class CallRouteContext(
    val peerKey: String,
    val peerName: String,
    val transport: Transport,
    val privacyRequiresRelay: Boolean,
    val latencyMs: Long? = null,
    val packetLossPercent: Int? = null,
    val batteryLow: Boolean = false
)

data class CallEngineCapabilities(
    val type: CallEngineType,
    val supportsLiveAudio: Boolean,
    val supportsLiveVideo: Boolean,
    val supportsAsyncSegments: Boolean,
    val supportedTransports: Set<Transport>
)

sealed class CallStartResult {
    data class Started(
        val callId: String,
        val mode: CallMode,
        val engineType: CallEngineType
    ) : CallStartResult()

    data class Fallback(
        val reason: String,
        val preferredEngine: CallEngineType
    ) : CallStartResult()

    data class Failed(val reason: String) : CallStartResult()
}

interface CallEngine {
    val capabilities: CallEngineCapabilities
    fun isAvailable(context: CallRouteContext): Boolean
    suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult
    suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult
    fun handleRemoteDescription(description: AstraSessionDescription)
    fun handleIceCandidate(candidate: AstraIceCandidate)
    fun end()
}
