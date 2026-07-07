package com.astramesh.app.realtime

import android.content.Context
import com.astramesh.app.data.ContactEntity

class LibWebRtcEngine(
    private val context: Context
) : RealtimeEngine {
    override val type = RealtimeEngineType.LIB_WEBRTC

    override fun isAvailable(route: RealtimeRoute): Boolean {
        return WebRtcRuntimeStatus.isEnabled(context) && !route.privacyOnly
    }

    override suspend fun startAudioCall(contact: ContactEntity): RealtimeSendResult {
        return unavailable()
    }

    override suspend fun startVideoCall(contact: ContactEntity): RealtimeSendResult {
        return unavailable()
    }

    override suspend fun openDataChannel(contact: ContactEntity): RealtimeSendResult {
        return unavailable()
    }

    override suspend fun sendData(contact: ContactEntity, bytes: ByteArray): RealtimeSendResult {
        return unavailable()
    }

    override fun close(peerKey: String?) = Unit

    private fun unavailable(): RealtimeSendResult {
        return RealtimeSendResult.Unavailable(WebRtcRuntimeStatus.disabledReason)
    }
}

object WebRtcRuntimeStatus {
    const val disabledReason: String =
        "WebRTC runtime is packaged but gated until an isolated compatibility probe passes on this device."

    const val artifact: String = "io.github.webrtc-sdk:android:144.7559.09"

    fun isEnabled(context: Context): Boolean {
        return WebRtcProbeStore.isCompatible(context)
    }
}
