package com.torxone.app.calls

/** Shared policy for call and file PeerConnections, snapshotted at session creation. */
object RtcIcePolicy {
    val defaultStunUrls = listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")

    fun stunUrls(configured: String, relayOnly: Boolean): List<String> {
        if (relayOnly) return emptyList()
        return configured.split(',').map(String::trim).filter(String::isNotBlank).ifEmpty { defaultStunUrls }
    }

    fun allowsCandidate(candidate: String, relayOnly: Boolean): Boolean =
        !relayOnly || Regex("\\btyp\\s+relay(?:\\s|$)").containsMatchIn(candidate)

    fun allowsSdp(sdp: String, relayOnly: Boolean): Boolean =
        !relayOnly || sdp.lineSequence().filter { it.startsWith("a=candidate:") }.all { allowsCandidate(it, true) }
}
