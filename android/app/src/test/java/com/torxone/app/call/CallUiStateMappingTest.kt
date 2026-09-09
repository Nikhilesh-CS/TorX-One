package com.torxone.app.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallUiStateMappingTest {

    private val testCallId = "call-uuid-12345"
    private val testPeerKey = "peer_pubkey_abc"
    private val testPeerName = "Alice"

    @Test
    fun testIsActiveCall_returnsTrueOnlyForActiveStates() {
        val activeStates = listOf(
            CallUiState.Outgoing(testCallId, testPeerKey, testPeerName, CallMode.AUDIO),
            CallUiState.Ringing(testCallId, testPeerKey, testPeerName, CallDirection.INCOMING, CallMode.AUDIO),
            CallUiState.Ringing(testCallId, testPeerKey, testPeerName, CallDirection.OUTGOING, CallMode.AUDIO),
            CallUiState.Accepted(testCallId, testPeerKey, testPeerName, CallMode.AUDIO),
            CallUiState.Negotiating(testCallId, testPeerKey, testPeerName, CallMode.AUDIO),
            CallUiState.IceConnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO),
            CallUiState.MediaConnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO),
            CallUiState.Connected(testCallId, testPeerKey, testPeerName, CallMode.AUDIO),
            CallUiState.Reconnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO)
        )

        for (state in activeStates) {
            assertTrue("State ${state::class.simpleName} should be active", state.isActiveCall)
        }

        val inactiveStates = listOf(
            CallUiState.Idle,
            CallUiState.Ended("Call ended", 10),
            CallUiState.Unavailable("Peer offline")
        )

        for (state in inactiveStates) {
            assertFalse("State ${state::class.simpleName} should NOT be active", state.isActiveCall)
        }
    }

    @Test
    fun testActiveCallId_returnsCallIdOnlyForActiveStates() {
        assertEquals(testCallId, CallUiState.Outgoing(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).activeCallId)
        assertEquals(testCallId, CallUiState.Ringing(testCallId, testPeerKey, testPeerName, CallDirection.INCOMING, CallMode.AUDIO).activeCallId)
        assertEquals(testCallId, CallUiState.Accepted(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).activeCallId)
        assertEquals(testCallId, CallUiState.Negotiating(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).activeCallId)
        assertEquals(testCallId, CallUiState.IceConnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).activeCallId)
        assertEquals(testCallId, CallUiState.MediaConnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).activeCallId)
        assertEquals(testCallId, CallUiState.Connected(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).activeCallId)
        assertEquals(testCallId, CallUiState.Reconnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).activeCallId)

        assertNull(CallUiState.Idle.activeCallId)
        assertNull(CallUiState.Ended("Ended", 0).activeCallId)
        assertNull(CallUiState.Unavailable("Failed").activeCallId)
    }

    @Test
    fun testBannerStatusText_deterministicMappingForAllStates() {
        assertEquals(
            "Calling…",
            CallUiState.Outgoing(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).bannerStatusText()
        )
        assertEquals(
            "Incoming call",
            CallUiState.Ringing(testCallId, testPeerKey, testPeerName, CallDirection.INCOMING, CallMode.AUDIO).bannerStatusText()
        )
        assertEquals(
            "Ringing…",
            CallUiState.Ringing(testCallId, testPeerKey, testPeerName, CallDirection.OUTGOING, CallMode.AUDIO).bannerStatusText()
        )
        assertEquals(
            "Connecting…",
            CallUiState.Accepted(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).bannerStatusText()
        )
        assertEquals(
            "Connecting…",
            CallUiState.Negotiating(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).bannerStatusText()
        )
        assertEquals(
            "Connecting to peer…",
            CallUiState.IceConnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).bannerStatusText()
        )
        assertEquals(
            "Starting audio…",
            CallUiState.MediaConnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).bannerStatusText()
        )
        assertEquals(
            "Connected • 00:42",
            CallUiState.Connected(testCallId, testPeerKey, testPeerName, CallMode.AUDIO, callDurationSeconds = 42).bannerStatusText()
        )
        assertEquals(
            "Connected • 03:15",
            CallUiState.Connected(testCallId, testPeerKey, testPeerName, CallMode.AUDIO, callDurationSeconds = 195).bannerStatusText()
        )
        assertEquals(
            "Reconnecting…",
            CallUiState.Reconnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO).bannerStatusText()
        )
        assertEquals(
            "",
            CallUiState.Idle.bannerStatusText()
        )
        assertEquals(
            "",
            CallUiState.Ended("Normal hangup", 42).bannerStatusText()
        )
        assertEquals(
            "",
            CallUiState.Unavailable("No network").bannerStatusText()
        )
    }

    /**
     * Test sequence:
     * Connected -> minimize -> Reconnecting -> Connected
     * Verifies that minimized state remains true throughout network reconnects.
     */
    @Test
    fun testMinimizedState_retainedDuringReconnecting() {
        var isCallMinimized = false
        var currentCallId: String? = null

        fun updateState(state: CallUiState) {
            when (state) {
                is CallUiState.Idle, is CallUiState.Ended -> {
                    isCallMinimized = false
                    currentCallId = null
                }
                is CallUiState.Unavailable -> {}
                else -> {
                    val id = state.activeCallId
                    if (id != null && id != currentCallId) {
                        currentCallId = id
                        isCallMinimized = false
                    }
                }
            }
        }

        // 1. Call connects
        updateState(CallUiState.Connected(testCallId, testPeerKey, testPeerName, CallMode.AUDIO, callDurationSeconds = 10))
        assertEquals(testCallId, currentCallId)
        assertFalse("Initial connect should not be minimized", isCallMinimized)

        // 2. User minimizes call
        isCallMinimized = true

        // 3. Network drops -> Reconnecting
        updateState(CallUiState.Reconnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO))
        assertEquals(testCallId, currentCallId)
        assertTrue("Call should remain minimized during Reconnecting", isCallMinimized)

        // 4. Network recovers -> Connected
        updateState(CallUiState.Connected(testCallId, testPeerKey, testPeerName, CallMode.AUDIO, callDurationSeconds = 15))
        assertEquals(testCallId, currentCallId)
        assertTrue("Call should remain minimized after reconnecting", isCallMinimized)
    }

    /**
     * Test sequence:
     * Ringing -> Accepted -> Negotiating -> IceConnecting -> MediaConnecting -> Connected
     * Verifies same callId, same minimized state, and NO expanded-screen reset mid-negotiation.
     */
    @Test
    fun testLifecycleTransitions_preserveMinimizedStateWithoutResurrection() {
        var isCallMinimized = false
        var currentCallId: String? = null

        fun updateState(state: CallUiState) {
            when (state) {
                is CallUiState.Idle, is CallUiState.Ended -> {
                    isCallMinimized = false
                    currentCallId = null
                }
                is CallUiState.Unavailable -> {}
                else -> {
                    val id = state.activeCallId
                    if (id != null && id != currentCallId) {
                        currentCallId = id
                        isCallMinimized = false
                    }
                }
            }
        }

        // 1. Ringing
        updateState(CallUiState.Ringing(testCallId, testPeerKey, testPeerName, CallDirection.OUTGOING, CallMode.AUDIO))
        assertEquals(testCallId, currentCallId)
        assertFalse(isCallMinimized)

        // 2. User minimizes while ringing
        isCallMinimized = true

        // 3. Remote accepts -> Accepted
        updateState(CallUiState.Accepted(testCallId, testPeerKey, testPeerName, CallMode.AUDIO))
        assertEquals(testCallId, currentCallId)
        assertTrue("Should remain minimized through Accepted", isCallMinimized)

        // 4. Negotiating (Securing)
        updateState(CallUiState.Negotiating(testCallId, testPeerKey, testPeerName, CallMode.AUDIO))
        assertEquals(testCallId, currentCallId)
        assertTrue("Should remain minimized through Negotiating", isCallMinimized)

        // 5. IceConnecting
        updateState(CallUiState.IceConnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO))
        assertEquals(testCallId, currentCallId)
        assertTrue("Should remain minimized through IceConnecting", isCallMinimized)

        // 6. MediaConnecting
        updateState(CallUiState.MediaConnecting(testCallId, testPeerKey, testPeerName, CallMode.AUDIO))
        assertEquals(testCallId, currentCallId)
        assertTrue("Should remain minimized through MediaConnecting", isCallMinimized)

        // 7. Connected
        updateState(CallUiState.Connected(testCallId, testPeerKey, testPeerName, CallMode.AUDIO, callDurationSeconds = 0))
        assertEquals(testCallId, currentCallId)
        assertTrue("Should remain minimized once Connected", isCallMinimized)
    }

    /**
     * Test sequence:
     * Connected -> End -> Ended -> Idle
     * Verifies both UIs disappear and state resets completely.
     */
    @Test
    fun testCallEnding_clearsAllUiAndState() {
        var isCallMinimized = true
        var currentCallId: String? = testCallId

        fun updateState(state: CallUiState) {
            when (state) {
                is CallUiState.Idle, is CallUiState.Ended -> {
                    isCallMinimized = false
                    currentCallId = null
                }
                is CallUiState.Unavailable -> {}
                else -> {
                    val id = state.activeCallId
                    if (id != null && id != currentCallId) {
                        currentCallId = id
                        isCallMinimized = false
                    }
                }
            }
        }

        // Call is active
        val connectedState = CallUiState.Connected(testCallId, testPeerKey, testPeerName, CallMode.AUDIO, callDurationSeconds = 30)
        assertTrue(connectedState.isActiveCall)

        // Call ends
        val endedState = CallUiState.Ended("Call ended", 30)
        assertFalse(endedState.isActiveCall)
        updateState(endedState)

        assertFalse("Banner and full screen must be gone (isCallMinimized reset)", isCallMinimized)
        assertNull("currentCallId must be reset", currentCallId)

        // Call resets to Idle
        val idleState = CallUiState.Idle
        assertFalse(idleState.isActiveCall)
        updateState(idleState)

        assertFalse(isCallMinimized)
        assertNull(currentCallId)
    }
}
