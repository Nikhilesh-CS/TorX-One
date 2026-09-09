package com.torxone.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallConnectionDiagnosticsTest {

    @Test
    fun testMilestonesAndTimestampsRecorded() {
        val diagnostics = CallConnectionDiagnostics(
            callId = "call-diag-1",
            direction = CallDirection.OUTGOING,
            peerKey = "peer_pubkey_test"
        )

        diagnostics.markOfferSent()
        diagnostics.markIceGatheringStart()
        diagnostics.markIceCandidateSent("mid-0")
        diagnostics.markIceCandidateSent("mid-1")
        diagnostics.markIceGatheringComplete(2)
        diagnostics.markAnswerReceived()
        diagnostics.markIceChecking()
        diagnostics.markIceConnected()
        diagnostics.markRemoteTrackReceived()
        val totalMs = diagnostics.markFullyConnected()

        val milestones = diagnostics.getMilestones()
        assertTrue(milestones.size >= 9)

        // Verify milestone order and presence
        assertEquals(CallConnectionDiagnostics.EVENT_OFFER_SENT, milestones[0].name)
        assertEquals(CallConnectionDiagnostics.EVENT_ICE_GATHERING_START, milestones[1].name)
        assertEquals(CallConnectionDiagnostics.EVENT_ICE_CANDIDATE_SENT, milestones[2].name)
        assertEquals(CallConnectionDiagnostics.EVENT_ICE_CANDIDATE_SENT, milestones[3].name)
        assertEquals(CallConnectionDiagnostics.EVENT_ICE_GATHERING_COMPLETE, milestones[4].name)
        assertEquals(CallConnectionDiagnostics.EVENT_ANSWER_RECEIVED, milestones[5].name)
        assertEquals(CallConnectionDiagnostics.EVENT_ICE_CHECKING, milestones[6].name)
        assertEquals(CallConnectionDiagnostics.EVENT_ICE_CONNECTED, milestones[7].name)
        assertEquals(CallConnectionDiagnostics.EVENT_REMOTE_TRACK_RECEIVED, milestones[8].name)
        assertEquals(CallConnectionDiagnostics.EVENT_FULLY_CONNECTED, milestones[9].name)

        assertTrue(totalMs >= 0)

        val summary = diagnostics.getSummary()
        assertTrue(summary.contains("Call ID:    call-diag-1"))
        assertTrue(summary.contains("Total Sent Candidates: 2"))
        assertTrue(summary.contains("FULLY_CONNECTED"))
    }

    @Test
    fun testIncomingDiagnosticsAndFailure() {
        val diagnostics = CallConnectionDiagnostics(
            callId = "call-diag-2",
            direction = CallDirection.INCOMING,
            peerKey = "peer_pubkey_test_2"
        )

        diagnostics.markOfferReceived()
        diagnostics.markIceCandidateReceived("mid-0")
        diagnostics.markAnswerSent()
        diagnostics.markFailed("Connection timed out")

        val milestones = diagnostics.getMilestones()
        val last = milestones.last()
        assertEquals(CallConnectionDiagnostics.EVENT_FAILED, last.name)
        assertEquals("Connection timed out", last.detail)

        val summary = diagnostics.getSummary()
        assertTrue(summary.contains("Total Recv Candidates: 1"))
        assertTrue(summary.contains("FAILED - Connection timed out"))
    }
}
