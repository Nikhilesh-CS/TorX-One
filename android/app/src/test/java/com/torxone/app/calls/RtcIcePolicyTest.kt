package com.torxone.app.calls
import org.junit.Assert.*
import org.junit.Test
class RtcIcePolicyTest {
    private val host = "candidate:1 1 udp 123 192.168.1.2 1234 typ host"
    private val relay = "candidate:3 1 udp 123 203.0.113.3 1234 typ relay raddr 0.0.0.0"
    @Test fun standardDefaultsToStun() { assertEquals(RtcIcePolicy.defaultStunUrls, RtcIcePolicy.stunUrls("", false)) }
    @Test fun customStun() { assertEquals(listOf("stun:example.com"), RtcIcePolicy.stunUrls(" stun:example.com, ", false)) }
    @Test fun maximumOmitsStun() { assertTrue(RtcIcePolicy.stunUrls("stun:example.com", true).isEmpty()) }
    @Test fun standardAllowsDirectAndRelay() {
        assertTrue(RtcIcePolicy.allowsCandidate(host, false))
        assertTrue(RtcIcePolicy.allowsCandidate(relay, false))
        assertTrue(RtcIcePolicy.allowsCandidate(host.replace("typ host", "typ srflx"), false))
    }
    @Test fun maximumAllowsOnlyRelay() {
        assertFalse(RtcIcePolicy.allowsCandidate(host, true))
        assertFalse(RtcIcePolicy.allowsCandidate(host.replace("typ host", "typ srflx"), true))
        assertTrue(RtcIcePolicy.allowsCandidate(relay, true))
        assertFalse(RtcIcePolicy.allowsCandidate("candidate:1 typ relayfake", true))
    }
    @Test fun sdpUsesSamePolicy() {
        val mixed = "v=0\r\na=$host\r\na=$relay\r\n"
        assertTrue(RtcIcePolicy.allowsSdp(mixed, false))
        assertFalse(RtcIcePolicy.allowsSdp(mixed, true))
        assertTrue(RtcIcePolicy.allowsSdp("v=0\r\na=$relay\r\n", true))
    }
}
