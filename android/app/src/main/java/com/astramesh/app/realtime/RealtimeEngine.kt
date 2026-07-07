package com.astramesh.app.realtime

import com.astramesh.app.data.ContactEntity
import com.astramesh.app.network.Transport

enum class RealtimeEngineType {
    LIB_WEBRTC,
    DISABLED,
    FUTURE_PION,
    FUTURE_RUST
}

data class RealtimeRoute(
    val peerKey: String,
    val transport: Transport,
    val privacyOnly: Boolean
)

sealed class RealtimeSendResult {
    data class Sent(val engineType: RealtimeEngineType) : RealtimeSendResult()
    data class Unavailable(val reason: String) : RealtimeSendResult()
    data class Failed(val reason: String) : RealtimeSendResult()
}

interface RealtimeEngine {
    val type: RealtimeEngineType
    fun isAvailable(route: RealtimeRoute): Boolean
    suspend fun startAudioCall(contact: ContactEntity): RealtimeSendResult
    suspend fun startVideoCall(contact: ContactEntity): RealtimeSendResult
    suspend fun openDataChannel(contact: ContactEntity): RealtimeSendResult
    suspend fun sendData(contact: ContactEntity, bytes: ByteArray): RealtimeSendResult
    fun close(peerKey: String? = null)
}
