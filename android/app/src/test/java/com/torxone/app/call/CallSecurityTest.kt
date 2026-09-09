package com.torxone.app.call

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5 Security Tests: Privacy policy, signaling validation, and candidate filtering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallSecurityTest {

    // ─── CallPrivacyPolicy: Candidate Filtering ────────────────────────

    @Test
    fun `NORMAL policy allows host candidates with public IPs`() {
        val policy = CallPrivacyPolicy.NORMAL
        val candidate = "candidate:1 1 udp 2122260223 203.0.113.5 50000 typ host generation 0"
        assertTrue(policy.shouldSignalCandidate(candidate))
    }

    @Test
    fun `NORMAL policy allows private RFC1918 host candidates for local LAN P2P`() {
        val policy = CallPrivacyPolicy.NORMAL
        val candidate192 = "candidate:1 1 udp 2122260223 192.168.1.100 50000 typ host generation 0"
        assertTrue(policy.shouldSignalCandidate(candidate192))

        val candidate10 = "candidate:1 1 udp 2122260223 10.0.0.5 50000 typ host generation 0"
        assertTrue(policy.shouldSignalCandidate(candidate10))

        val candidate172 = "candidate:1 1 udp 2122260223 172.16.0.1 50000 typ host generation 0"
        assertTrue(policy.shouldSignalCandidate(candidate172))
    }

    @Test
    fun `NORMAL policy allows srflx candidates`() {
        val policy = CallPrivacyPolicy.NORMAL
        val candidate = "candidate:2 1 udp 1686052607 203.0.113.5 50000 typ srflx raddr 0.0.0.0 rport 0 generation 0"
        assertTrue(policy.shouldSignalCandidate(candidate))
    }

    @Test
    fun `NORMAL policy allows relay candidates`() {
        val policy = CallPrivacyPolicy.NORMAL
        val candidate = "candidate:3 1 udp 41885695 203.0.113.5 50000 typ relay raddr 0.0.0.0 rport 0 generation 0"
        assertTrue(policy.shouldSignalCandidate(candidate))
    }

    @Test
    fun `PRIVACY policy blocks all host candidates`() {
        val policy = CallPrivacyPolicy.PRIVACY
        val publicHost = "candidate:1 1 udp 2122260223 203.0.113.5 50000 typ host generation 0"
        assertFalse(policy.shouldSignalCandidate(publicHost))

        val privateHost = "candidate:1 1 udp 2122260223 192.168.1.5 50000 typ host generation 0"
        assertFalse(policy.shouldSignalCandidate(privateHost))
    }

    @Test
    fun `PRIVACY policy allows srflx candidates`() {
        val policy = CallPrivacyPolicy.PRIVACY
        val candidate = "candidate:2 1 udp 1686052607 203.0.113.5 50000 typ srflx raddr 0.0.0.0 rport 0 generation 0"
        assertTrue(policy.shouldSignalCandidate(candidate))
    }

    @Test
    fun `PRIVACY policy allows relay candidates`() {
        val policy = CallPrivacyPolicy.PRIVACY
        val candidate = "candidate:3 1 udp 41885695 203.0.113.5 50000 typ relay raddr 0.0.0.0 rport 0 generation 0"
        assertTrue(policy.shouldSignalCandidate(candidate))
    }

    @Test
    fun `STRICT policy blocks host candidates`() {
        val policy = CallPrivacyPolicy.STRICT
        val candidate = "candidate:1 1 udp 2122260223 203.0.113.5 50000 typ host generation 0"
        assertFalse(policy.shouldSignalCandidate(candidate))
    }

    @Test
    fun `STRICT policy blocks srflx candidates`() {
        val policy = CallPrivacyPolicy.STRICT
        val candidate = "candidate:2 1 udp 1686052607 203.0.113.5 50000 typ srflx raddr 0.0.0.0 rport 0 generation 0"
        assertFalse(policy.shouldSignalCandidate(candidate))
    }

    @Test
    fun `STRICT policy allows only relay candidates`() {
        val policy = CallPrivacyPolicy.STRICT
        val candidate = "candidate:3 1 udp 41885695 203.0.113.5 50000 typ relay raddr 0.0.0.0 rport 0 generation 0"
        assertTrue(policy.shouldSignalCandidate(candidate))
    }

    @Test
    fun `link-local IPv6 is detected as private`() {
        assertTrue(CallPrivacyPolicy.isPrivateNetworkCandidate(
            "candidate:1 1 udp 2122260223 fe80::1 50000 typ host generation 0"
        ))
    }

    @Test
    fun `ULA IPv6 fd prefix is detected as private`() {
        assertTrue(CallPrivacyPolicy.isPrivateNetworkCandidate(
            "candidate:1 1 udp 2122260223 fd12:3456:789a::1 50000 typ host generation 0"
        ))
    }

    @Test
    fun `unknown candidate type is rejected`() {
        val policy = CallPrivacyPolicy.NORMAL
        assertFalse(policy.shouldSignalCandidate("garbage candidate string"))
    }

    // ─── CallPrivacyPolicy: getCandidateType ───────────────────────────

    @Test
    fun `getCandidateType parses host correctly`() {
        assertEquals("host", CallPrivacyPolicy.getCandidateType("candidate:1 1 udp 123 1.2.3.4 5 typ host gen 0"))
    }

    @Test
    fun `getCandidateType parses srflx correctly`() {
        assertEquals("srflx", CallPrivacyPolicy.getCandidateType("candidate:2 1 udp 123 1.2.3.4 5 typ srflx raddr 0.0.0.0 rport 0"))
    }

    @Test
    fun `getCandidateType parses relay correctly`() {
        assertEquals("relay", CallPrivacyPolicy.getCandidateType("candidate:3 1 udp 123 1.2.3.4 5 typ relay raddr 0.0.0.0 rport 0"))
    }

    @Test
    fun `getCandidateType returns null for garbage`() {
        assertNull(CallPrivacyPolicy.getCandidateType("not a candidate"))
    }

    // ─── CallSignalingHandler: Payload Size Limits ─────────────────────

    @Test(expected = SecurityException::class)
    fun `parse rejects oversized signal payload`() {
        val handler = CallSignalingHandler(mockMessageRouter())
        val oversized = "{\"callId\":\"test\"," + "\"padding\":\"" + "A".repeat(70_000) + "\"}"
        handler.parse(oversized)
    }

    @Test(expected = SecurityException::class)
    fun `parse rejects malformed JSON`() {
        val handler = CallSignalingHandler(mockMessageRouter())
        handler.parse("this is not json {{{")
    }

    @Test(expected = SecurityException::class)
    fun `parse rejects signal missing callId`() {
        val handler = CallSignalingHandler(mockMessageRouter())
        handler.parse("{\"mode\":\"AUDIO\"}")
    }

    @Test(expected = SecurityException::class)
    fun `parse rejects oversized SDP field`() {
        val handler = CallSignalingHandler(mockMessageRouter())
        val bigSdp = "A".repeat(CallSignalingHandler.MAX_SDP_SIZE + 1)
        handler.parse("{\"callId\":\"test\",\"sdp\":\"$bigSdp\"}")
    }

    @Test(expected = SecurityException::class)
    fun `parse rejects oversized ICE candidate field`() {
        val handler = CallSignalingHandler(mockMessageRouter())
        val bigCandidate = "A".repeat(CallSignalingHandler.MAX_ICE_CANDIDATE_SIZE + 1)
        handler.parse("{\"callId\":\"test\",\"candidate\":\"$bigCandidate\"}")
    }

    @Test
    fun `parse accepts valid signal`() {
        val handler = CallSignalingHandler(mockMessageRouter())
        val signal = handler.parse("{\"callId\":\"call-123\",\"mode\":\"AUDIO\",\"sdp\":\"v=0\\r\\n\"}")
        assertEquals("call-123", signal.callId)
        assertEquals(CallMode.AUDIO, signal.mode)
        assertEquals("v=0\r\n", signal.sdp)
    }

    @Test
    fun `parse handles missing optional fields gracefully`() {
        val handler = CallSignalingHandler(mockMessageRouter())
        val signal = handler.parse("{\"callId\":\"call-456\"}")
        assertEquals("call-456", signal.callId)
        assertNull(signal.sdp)
        assertNull(signal.candidate)
        assertNull(signal.sdpMid)
        assertNull(signal.sdpMLineIndex)
        assertNull(signal.reason)
    }

    // ─── DefaultIceServerProvider ──────────────────────────────────────

    @Test
    fun `DefaultIceServerProvider with STRICT policy returns no servers when relay disabled`() {
        // Create a custom policy with relay disabled (edge case)
        val noRelayPolicy = CallPrivacyPolicy(
            stripLocalIps = true,
            allowDirectP2P = false,
            allowSrflxCandidates = false,
            allowRelayCandidates = false,
            maskNotificationIdentities = true
        )
        val provider = DefaultIceServerProvider(noRelayPolicy)
        val servers = kotlinx.coroutines.runBlocking { provider.getIceServers() }
        assertTrue("No servers when relay disabled", servers.isEmpty())
    }

    @Test
    fun `DefaultIceServerProvider with NORMAL policy returns TURN and STUN servers`() {
        val provider = DefaultIceServerProvider(CallPrivacyPolicy.NORMAL)
        val servers = kotlinx.coroutines.runBlocking { provider.getIceServers() }
        assertTrue("Should have ICE servers", servers.isNotEmpty())
        // NORMAL mode includes Google STUN for NAT traversal
        assertTrue("STUN should be present in NORMAL mode", servers.any { s -> s.urls.any { u -> u.contains("stun.l.google.com") } })
        assertTrue("TURN should be present in NORMAL mode", servers.any { s -> s.urls.any { u -> u.contains("openrelay.metered.ca") } })
    }

    @Test
    fun `STRICT policy returns TURN only and no STUN servers`() {
        val provider = DefaultIceServerProvider(CallPrivacyPolicy.STRICT)
        val servers = kotlinx.coroutines.runBlocking { provider.getIceServers() }
        assertTrue("STRICT should have TURN servers", servers.isNotEmpty())
        servers.forEach { server ->
            server.urls.forEach { url ->
                assertFalse("No Google STUN allowed in STRICT: $url", url.contains("google"))
                assertFalse("No STUN allowed in STRICT: $url", url.contains("stun:"))
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private fun mockMessageRouter(): com.torxone.app.network.MessageRouter {
        return org.mockito.Mockito.mock(com.torxone.app.network.MessageRouter::class.java)
    }
}
