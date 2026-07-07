package com.astramesh.app.realtime

import com.astramesh.app.data.ContactEntity

class DisabledRealtimeEngine(
    private val reason: String = "No realtime engine is available for this route."
) : RealtimeEngine {
    override val type = RealtimeEngineType.DISABLED

    override fun isAvailable(route: RealtimeRoute): Boolean = true

    override suspend fun startAudioCall(contact: ContactEntity): RealtimeSendResult {
        return RealtimeSendResult.Unavailable(reason)
    }

    override suspend fun startVideoCall(contact: ContactEntity): RealtimeSendResult {
        return RealtimeSendResult.Unavailable(reason)
    }

    override suspend fun openDataChannel(contact: ContactEntity): RealtimeSendResult {
        return RealtimeSendResult.Unavailable(reason)
    }

    override suspend fun sendData(contact: ContactEntity, bytes: ByteArray): RealtimeSendResult {
        return RealtimeSendResult.Unavailable(reason)
    }

    override fun close(peerKey: String?) = Unit
}
