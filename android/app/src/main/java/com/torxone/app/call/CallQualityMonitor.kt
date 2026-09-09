package com.torxone.app.call

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically samples WebRTC statistics during Connected state.
 * Uses a rolling window of recent samples to prevent flickering of the quality indicator.
 * Robustly handles missing RTCStats fields across different WebRTC builds and devices.
 */
class CallQualityMonitor(
    private val scope: CoroutineScope,
    private val statsProvider: (callback: (RTCStatsReport?) -> Unit) -> Unit,
    private val onQualityUpdate: (quality: CallQuality, stats: CallStats) -> Unit
) {
    companion object {
        private const val TAG = "CallQualityMonitor"
        private const val POLL_INTERVAL_MS = 2500L
        private const val WINDOW_SIZE = 3

        const val RTT_POOR_THRESHOLD_MS = 450
        const val RTT_FAIR_THRESHOLD_MS = 250
        const val PACKET_LOSS_POOR_THRESHOLD_PERCENT = 6.0f
        const val PACKET_LOSS_FAIR_THRESHOLD_PERCENT = 2.5f
        const val JITTER_POOR_THRESHOLD_MS = 70
        const val JITTER_FAIR_THRESHOLD_MS = 35

        fun evaluateQuality(samples: Collection<RawSample>): CallQuality = QualityEvaluator.evaluateQuality(samples)
        fun aggregateStats(samples: Collection<RawSample>): CallStats = QualityEvaluator.aggregateStats(samples)
    }

    object QualityEvaluator {
        fun evaluateQuality(samples: Collection<RawSample>): CallQuality {
            if (samples.isEmpty()) return CallQuality.UNKNOWN

            // Calculate averages ignoring missing (null) metrics
            val validRtt = samples.mapNotNull { it.rttMs }
            val validLoss = samples.mapNotNull { it.packetLossPercent }
            val validJitter = samples.mapNotNull { it.jitterMs }

            if (validRtt.isEmpty() && validLoss.isEmpty() && validJitter.isEmpty()) {
                return CallQuality.UNKNOWN
            }

            val avgRtt = if (validRtt.isNotEmpty()) validRtt.average() else null
            val avgLoss = if (validLoss.isNotEmpty()) validLoss.average() else null
            val avgJitter = if (validJitter.isNotEmpty()) validJitter.average() else null

            // Determine if POOR
            val isPoorRtt = avgRtt != null && avgRtt >= RTT_POOR_THRESHOLD_MS
            val isPoorLoss = avgLoss != null && avgLoss >= PACKET_LOSS_POOR_THRESHOLD_PERCENT
            val isPoorJitter = avgJitter != null && avgJitter >= JITTER_POOR_THRESHOLD_MS

            if (isPoorRtt || isPoorLoss || isPoorJitter) {
                return CallQuality.POOR
            }

            // Determine if FAIR
            val isFairRtt = avgRtt != null && avgRtt >= RTT_FAIR_THRESHOLD_MS
            val isFairLoss = avgLoss != null && avgLoss >= PACKET_LOSS_FAIR_THRESHOLD_PERCENT
            val isFairJitter = avgJitter != null && avgJitter >= JITTER_FAIR_THRESHOLD_MS

            if (isFairRtt || isFairLoss || isFairJitter) {
                return CallQuality.FAIR
            }

            return CallQuality.GOOD
        }

        fun aggregateStats(samples: Collection<RawSample>): CallStats {
            if (samples.isEmpty()) return CallStats()

            val latest = samples.last()
            val validRtt = samples.mapNotNull { it.rttMs }
            val validLoss = samples.mapNotNull { it.packetLossPercent }
            val validJitter = samples.mapNotNull { it.jitterMs }

            return CallStats(
                bitrateKbps = latest.bitrateKbps ?: 0,
                packetLossPercent = (if (validLoss.isNotEmpty()) validLoss.average().toFloat() else 0f),
                roundTripMs = (if (validRtt.isNotEmpty()) validRtt.average().toInt() else 0),
                jitterMs = (if (validJitter.isNotEmpty()) validJitter.average().toInt() else 0),
                audioLevel = latest.audioLevel ?: 0f
            )
        }
    }

    private var pollingJob: Job? = null
    private val isMonitoring = AtomicBoolean(false)

    // Historical samples for rolling window
    private val sampleHistory = ArrayDeque<RawSample>(WINDOW_SIZE)

    // Previous totals for delta calculations
    private var lastPacketsLost: Long? = null
    private var lastPacketsReceived: Long? = null
    private var lastBytesReceived: Long? = null
    private var lastTimestampMs: Long? = null

    data class RawSample(
        val rttMs: Int?,
        val packetLossPercent: Float?,
        val jitterMs: Int?,
        val audioLevel: Float?,
        val bitrateKbps: Int?
    )

    fun start() {
        if (isMonitoring.getAndSet(true)) return
        Log.d(TAG, "Starting CallQualityMonitor polling")
        sampleHistory.clear()
        lastPacketsLost = null
        lastPacketsReceived = null
        lastBytesReceived = null
        lastTimestampMs = null

        pollingJob = scope.launch {
            while (isActive && isMonitoring.get()) {
                delay(POLL_INTERVAL_MS)
                if (!isActive || !isMonitoring.get()) break
                queryStats()
            }
        }
    }

    fun stop() {
        if (!isMonitoring.getAndSet(false)) return
        Log.d(TAG, "Stopping CallQualityMonitor polling")
        pollingJob?.cancel()
        pollingJob = null
        sampleHistory.clear()
    }

    private fun queryStats() {
        try {
            statsProvider { report ->
                if (!isMonitoring.get()) return@statsProvider
                if (report == null) {
                    Log.d(TAG, "Stats report is null")
                    return@statsProvider
                }
                processStatsReport(report)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting stats: ${e.message}")
        }
    }

    fun processStatsReport(report: RTCStatsReport) {
        var rttMs: Int? = null
        var jitterMs: Int? = null
        var packetLossPercent: Float? = null
        var audioLevel: Float? = null
        var bitrateKbps: Int? = null

        val nowMs = System.currentTimeMillis()

        for (stats in report.statsMap.values) {
            when (stats.type) {
                "candidate-pair" -> {
                    // Extract RTT from active/selected candidate pair
                    val members = stats.members
                    val state = members["state"] as? String
                    val nominated = members["nominated"] as? Boolean
                    if (state == "succeeded" || nominated == true) {
                        val currentRttSec = (members["currentRoundTripTime"] as? Number)?.toDouble()
                        if (currentRttSec != null && currentRttSec > 0) {
                            rttMs = (currentRttSec * 1000.0).toInt()
                        }
                    }
                }
                "inbound-rtp" -> {
                    val members = stats.members
                    val mediaType = members["mediaType"] as? String ?: members["kind"] as? String
                    if (mediaType == "audio") {
                        // Jitter
                        val jitterSec = (members["jitter"] as? Number)?.toDouble()
                        if (jitterSec != null && jitterSec >= 0) {
                            jitterMs = (jitterSec * 1000.0).toInt()
                        }

                        // Audio level
                        val level = (members["audioLevel"] as? Number)?.toFloat()
                        if (level != null && level >= 0) {
                            audioLevel = level
                        }

                        // Delta packet loss calculation
                        val totalLost = (members["packetsLost"] as? Number)?.toLong()
                        val totalReceived = (members["packetsReceived"] as? Number)?.toLong()
                        if (totalLost != null && totalReceived != null) {
                            val prevLost = lastPacketsLost
                            val prevReceived = lastPacketsReceived
                            if (prevLost != null && prevReceived != null) {
                                val deltaLost = (totalLost - prevLost).coerceAtLeast(0L)
                                val deltaReceived = (totalReceived - prevReceived).coerceAtLeast(0L)
                                val totalDelta = deltaLost + deltaReceived
                                if (totalDelta > 0) {
                                    packetLossPercent = (deltaLost.toFloat() / totalDelta.toFloat()) * 100f
                                } else {
                                    packetLossPercent = 0f
                                }
                            }
                            lastPacketsLost = totalLost
                            lastPacketsReceived = totalReceived
                        }

                        // Delta bitrate calculation
                        val totalBytes = (members["bytesReceived"] as? Number)?.toLong()
                        if (totalBytes != null) {
                            val prevBytes = lastBytesReceived
                            val prevTime = lastTimestampMs
                            if (prevBytes != null && prevTime != null && nowMs > prevTime) {
                                val deltaBytes = (totalBytes - prevBytes).coerceAtLeast(0L)
                                val deltaSec = (nowMs - prevTime) / 1000.0
                                if (deltaSec > 0) {
                                    bitrateKbps = ((deltaBytes * 8) / (deltaSec * 1000.0)).toInt()
                                }
                            }
                            lastBytesReceived = totalBytes
                            lastTimestampMs = nowMs
                        }
                    }
                }
            }
        }

        val sample = RawSample(rttMs, packetLossPercent, jitterMs, audioLevel, bitrateKbps)
        recordSample(sample)
    }

    fun recordSample(sample: RawSample) {
        if (sampleHistory.size >= WINDOW_SIZE) {
            sampleHistory.removeFirst()
        }
        sampleHistory.addLast(sample)

        val evaluatedQuality = evaluateQuality(sampleHistory)
        val computedStats = aggregateStats(sampleHistory)

        onQualityUpdate(evaluatedQuality, computedStats)
    }
}
