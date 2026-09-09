package com.torxone.app.call

import android.util.Log

/**
 * High-resolution diagnostic tracker for call signaling, WebRTC negotiation,
 * ICE gathering, and media connection lifecycle.
 */
class CallConnectionDiagnostics(
    val callId: String,
    val direction: CallDirection,
    val peerKey: String,
    val startTimeMs: Long = System.currentTimeMillis()
) {
    data class Milestone(
        val name: String,
        val elapsedMs: Long,
        val detail: String? = null
    )

    private val milestones = mutableListOf<Milestone>()
    private var iceCandidateSentCount = 0
    private var iceCandidateReceivedCount = 0
    private var isConnected = false
    private var totalTimeToConnectMs: Long? = null

    var networkChangeCount: Int = 0
        private set
    var iceRestartCount: Int = 0
        private set
    var latestRttMs: Int? = null
        private set
    var latestPacketLossPercent: Float? = null
        private set
    var latestJitterMs: Int? = null
        private set

    // Privacy-safe transport indicators (no raw IPs stored)
    var signalingTransport: String = "UNKNOWN"  // "TOR", "NEARBY", "UNKNOWN"
    var iceConnectionType: String = "UNKNOWN"   // "DIRECT_P2P", "RELAY", "UNKNOWN"
    var stunUsed: Boolean = false
    var turnUsed: Boolean = false

    companion object {
        private const val TAG = "CallDiagnostics"

        const val EVENT_OFFER_SENT = "CALL_OFFER_SENT"
        const val EVENT_OFFER_RECEIVED = "CALL_OFFER_RECEIVED"
        const val EVENT_ANSWER_SENT = "CALL_ANSWER_SENT"
        const val EVENT_ANSWER_RECEIVED = "CALL_ANSWER_RECEIVED"
        const val EVENT_ICE_GATHERING_START = "ICE_GATHERING_START"
        const val EVENT_ICE_GATHERING_COMPLETE = "ICE_GATHERING_COMPLETE"
        const val EVENT_ICE_CANDIDATE_SENT = "ICE_CANDIDATE_SENT"
        const val EVENT_ICE_CANDIDATE_RECEIVED = "ICE_CANDIDATE_RECEIVED"
        const val EVENT_ICE_CHECKING = "ICE_CHECKING"
        const val EVENT_ICE_CONNECTED = "ICE_CONNECTED"
        const val EVENT_REMOTE_TRACK_RECEIVED = "REMOTE_TRACK_RECEIVED"
        const val EVENT_AUDIO_READY = "AUDIO_READY"
        const val EVENT_FULLY_CONNECTED = "FULLY_CONNECTED"
        const val EVENT_RECONNECTING = "RECONNECTING"
        const val EVENT_FAILED = "FAILED"
    }

    @Synchronized
    fun record(eventName: String, detail: String? = null): Long {
        val elapsed = System.currentTimeMillis() - startTimeMs
        if (eventName == "NETWORK_HANDOVER") networkChangeCount++
        if (eventName.contains("ICE_RESTART")) iceRestartCount++
        milestones.add(Milestone(eventName, elapsed, detail))
        val detailStr = if (detail != null) " ($detail)" else ""
        Log.d(TAG, "[$callId] $eventName at +${elapsed}ms$detailStr")
        return elapsed
    }

    @Synchronized
    fun recordQualitySnapshot(rttMs: Int, packetLossPercent: Float, jitterMs: Int) {
        if (rttMs > 0) latestRttMs = rttMs
        if (packetLossPercent >= 0) latestPacketLossPercent = packetLossPercent
        if (jitterMs >= 0) latestJitterMs = jitterMs
    }

    fun markOfferSent() = record(EVENT_OFFER_SENT)
    fun markOfferReceived() = record(EVENT_OFFER_RECEIVED)
    fun markAnswerSent() = record(EVENT_ANSWER_SENT)
    fun markAnswerReceived() = record(EVENT_ANSWER_RECEIVED)

    fun markIceGatheringStart() = record(EVENT_ICE_GATHERING_START)
    fun markIceGatheringComplete(count: Int) = record(EVENT_ICE_GATHERING_COMPLETE, "$count candidates")

    @Synchronized
    fun markIceCandidateSent(mid: String? = null): Long {
        iceCandidateSentCount++
        return record(EVENT_ICE_CANDIDATE_SENT, "#$iceCandidateSentCount mid=$mid")
    }

    @Synchronized
    fun markIceCandidateReceived(mid: String? = null): Long {
        iceCandidateReceivedCount++
        return record(EVENT_ICE_CANDIDATE_RECEIVED, "#$iceCandidateReceivedCount mid=$mid")
    }

    fun markIceChecking() = record(EVENT_ICE_CHECKING)
    fun markIceConnected() = record(EVENT_ICE_CONNECTED)
    fun markRemoteTrackReceived() = record(EVENT_REMOTE_TRACK_RECEIVED)
    fun markAudioReady() = record(EVENT_AUDIO_READY)

    @Synchronized
    fun markFullyConnected(): Long {
        if (!isConnected) {
            isConnected = true
            val elapsed = record(EVENT_FULLY_CONNECTED)
            totalTimeToConnectMs = elapsed
            logSummary()
            return elapsed
        }
        return System.currentTimeMillis() - startTimeMs
    }

    fun markReconnecting(reason: String? = null) = record(EVENT_RECONNECTING, reason)
    fun markFailed(reason: String) {
        record(EVENT_FAILED, reason)
        logSummary()
    }

    @Synchronized
    fun getMilestones(): List<Milestone> = milestones.toList()

    @Synchronized
    fun getSummary(): String {
        val sb = StringBuilder()
        sb.append("\n================ CALL DIAGNOSTICS ================\n")
        sb.append("Call ID:    $callId\n")
        sb.append("Direction:  $direction\n")
        sb.append("Peer:       ${peerKey.take(16)}...\n")
        sb.append("Total Sent Candidates: $iceCandidateSentCount\n")
        sb.append("Total Recv Candidates: $iceCandidateReceivedCount\n")
        sb.append("Network Changes:       $networkChangeCount\n")
        sb.append("ICE Restarts:          $iceRestartCount\n")
        if (latestRttMs != null || latestPacketLossPercent != null) {
            sb.append("Latest Quality:        RTT=${latestRttMs ?: 0}ms, Loss=${latestPacketLossPercent ?: 0f}%, Jitter=${latestJitterMs ?: 0}ms\n")
        }
        totalTimeToConnectMs?.let {
            sb.append("Time to Connected:     $it ms (${it / 1000.0}s)\n")
        }
        sb.append("---------------- Timeline ----------------\n")
        for (m in milestones) {
            val detailPart = if (m.detail != null) " - ${m.detail}" else ""
            sb.append(String.format("  +%-6d ms : %s%s\n", m.elapsedMs, m.name, detailPart))
        }
        sb.append("==================================================")
        return sb.toString()
    }

    fun logSummary() {
        Log.i(TAG, getSummary())
    }
}
