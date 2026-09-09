package com.torxone.app.call

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallQualityMonitorTest {

    @Test
    fun testQualityClassification_goodQuality() {
        val samples = listOf(
            CallQualityMonitor.RawSample(rttMs = 80, packetLossPercent = 0.5f, jitterMs = 12, audioLevel = 0.5f, bitrateKbps = 32),
            CallQualityMonitor.RawSample(rttMs = 85, packetLossPercent = 0.8f, jitterMs = 14, audioLevel = 0.5f, bitrateKbps = 32),
            CallQualityMonitor.RawSample(rttMs = 90, packetLossPercent = 1.0f, jitterMs = 15, audioLevel = 0.5f, bitrateKbps = 32)
        )

        val quality = CallQualityMonitor.QualityEvaluator.evaluateQuality(samples)
        assertEquals(CallQuality.GOOD, quality)

        val stats = CallQualityMonitor.QualityEvaluator.aggregateStats(samples)
        assertEquals(85, stats.roundTripMs)
        assertTrue(stats.packetLossPercent in 0.7f..0.8f)
        assertEquals(13, stats.jitterMs)
    }

    @Test
    fun testQualityClassification_fairQuality() {
        val samples = listOf(
            CallQualityMonitor.RawSample(rttMs = 280, packetLossPercent = 3.0f, jitterMs = 40, audioLevel = 0.4f, bitrateKbps = 24),
            CallQualityMonitor.RawSample(rttMs = 300, packetLossPercent = 3.5f, jitterMs = 45, audioLevel = 0.4f, bitrateKbps = 24),
            CallQualityMonitor.RawSample(rttMs = 290, packetLossPercent = 3.2f, jitterMs = 42, audioLevel = 0.4f, bitrateKbps = 24)
        )

        val quality = CallQualityMonitor.QualityEvaluator.evaluateQuality(samples)
        assertEquals(CallQuality.FAIR, quality)
    }

    @Test
    fun testQualityClassification_poorQuality() {
        val samples = listOf(
            CallQualityMonitor.RawSample(rttMs = 520, packetLossPercent = 8.5f, jitterMs = 85, audioLevel = 0.2f, bitrateKbps = 16),
            CallQualityMonitor.RawSample(rttMs = 480, packetLossPercent = 7.0f, jitterMs = 75, audioLevel = 0.2f, bitrateKbps = 16),
            CallQualityMonitor.RawSample(rttMs = 500, packetLossPercent = 7.8f, jitterMs = 80, audioLevel = 0.2f, bitrateKbps = 16)
        )

        val quality = CallQualityMonitor.QualityEvaluator.evaluateQuality(samples)
        assertEquals(CallQuality.POOR, quality)
    }

    @Test
    fun testRollingWindowSmoothesSingleBadPacketBurst() {
        // Sample 1: Healthy
        // Sample 2: Sudden bad spike
        // Sample 3: Healthy again
        val samples = listOf(
            CallQualityMonitor.RawSample(rttMs = 70, packetLossPercent = 0.0f, jitterMs = 10, audioLevel = 0.5f, bitrateKbps = 32),
            CallQualityMonitor.RawSample(rttMs = 460, packetLossPercent = 6.5f, jitterMs = 72, audioLevel = 0.4f, bitrateKbps = 20),
            CallQualityMonitor.RawSample(rttMs = 75, packetLossPercent = 0.2f, jitterMs = 12, audioLevel = 0.5f, bitrateKbps = 32)
        )

        // The rolling window averages: avg RTT ~201ms, avg loss ~2.2%, avg jitter ~31ms
        // This does NOT trigger POOR (which requires average >= 450ms / 6%), preventing UI flicker
        val quality = CallQualityMonitor.QualityEvaluator.evaluateQuality(samples)
        assertEquals(CallQuality.GOOD, quality)
    }

    @Test
    fun testMissingFields_toleratedGracefully() {
        // Test device where RTT and jitter are missing (null)
        val samples = listOf(
            CallQualityMonitor.RawSample(rttMs = null, packetLossPercent = 1.0f, jitterMs = null, audioLevel = 0.5f, bitrateKbps = 32),
            CallQualityMonitor.RawSample(rttMs = null, packetLossPercent = 1.5f, jitterMs = null, audioLevel = 0.5f, bitrateKbps = 32)
        )

        val quality = CallQualityMonitor.QualityEvaluator.evaluateQuality(samples)
        // Evaluates remaining valid metric (packet loss) -> GOOD
        assertEquals(CallQuality.GOOD, quality)

        val stats = CallQualityMonitor.QualityEvaluator.aggregateStats(samples)
        assertEquals(0, stats.roundTripMs)
        assertTrue(stats.packetLossPercent in 1.2f..1.3f)
    }
}
