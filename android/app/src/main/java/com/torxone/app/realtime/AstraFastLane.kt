package com.torxone.app.realtime

import android.util.Log
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.Transport

class AstraFastLane(
    private val realtimeEngineManager: RealtimeEngineManager
) {
    companion object {
        private const val TAG = "AstraFastLane"
    }

    fun canAttempt(contact: ContactEntity): Boolean {
        val route = realtimeEngineManager.routeFor(contact)
        if (route.transport == Transport.TOR) return false
        val engine = realtimeEngineManager.select(route)
        return engine.type != RealtimeEngineType.DISABLED && engine.isAvailable(route)
    }

    suspend fun trySendMedia(contact: ContactEntity, bytes: ByteArray): RealtimeSendResult {
        val route = realtimeEngineManager.routeFor(contact)
        if (route.transport == Transport.TOR) {
            return RealtimeSendResult.Unavailable("Tor route selected; using encrypted chunk fallback.")
        }

        val engine = realtimeEngineManager.select(route)
        val openResult = engine.openDataChannel(contact)
        if (openResult !is RealtimeSendResult.Sent) {
            Log.d(TAG, "DataChannel unavailable via ${engine.type}: $openResult")
            return openResult
        }

        return engine.sendData(contact, bytes)
    }
}
