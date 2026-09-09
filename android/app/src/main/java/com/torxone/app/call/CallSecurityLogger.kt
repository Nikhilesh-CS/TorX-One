package com.torxone.app.call

import android.util.Log

/**
 * Structured security event logger for call signaling and lifecycle events.
 * Guarantees that sensitive identities, raw SDP contents, ICE candidates, and IP addresses
 * are never leaked into system logs.
 */
object CallSecurityLogger {
    private const val TAG = "CallSecurity"

    const val EVENT_MALFORMED_SIGNAL = "MALFORMED_SIGNAL"
    const val EVENT_OVERSIZED_PAYLOAD = "OVERSIZED_PAYLOAD"
    const val EVENT_WRONG_PEER_REJECTED = "WRONG_PEER_REJECTED"
    const val EVENT_BUSY_COLLISION_DROPPED = "BUSY_COLLISION_DROPPED"
    const val EVENT_STALE_SIGNAL_DISCARDED = "STALE_SIGNAL_DISCARDED"
    const val EVENT_REPLAY_REJECTED = "REPLAY_REJECTED"
    const val EVENT_TERMINATED_CALL_SIGNAL = "TERMINATED_CALL_SIGNAL"
    const val EVENT_PRIVATE_IP_CANDIDATE_STRIPPED = "PRIVATE_IP_CANDIDATE_STRIPPED"
    const val EVENT_AUDIO_LEAK_PREVENTED = "AUDIO_LEAK_PREVENTED"

    fun logSecurityEvent(
        event: String,
        callId: String?,
        peerKey: String?,
        detail: String? = null
    ) {
        val safeCallId = callId ?: "none"
        val safePeerKey = if (!peerKey.isNullOrBlank()) {
            "${peerKey.take(12)}..."
        } else {
            "unknown"
        }
        val detailStr = if (!detail.isNullOrBlank()) " - $detail" else ""
        runCatching {
            Log.w(TAG, "[$event] call=$safeCallId peer=$safePeerKey$detailStr")
        }
    }
}
