package com.torxone.app.calls

import org.junit.Assert.*
import org.junit.Test

/**
 * CallProtocolCodecTest — Round-trip and validation tests for the call binary codec.
 */
class CallProtocolCodecTest {

    @Test
    fun `offer round-trip preserves all fields`() {
        val original = CallOfferPayload(
            callId = "call-abc-123",
            callType = CallType.VOICE,
            sdpOffer = "v=0\r\no=- 123 456 IN IP4 127.0.0.1\r\ns=-\r\n",
            createdAt = 1700000000000L
        )
        val bytes = CallProtocolCodec.encodeOffer(original)
        val decoded = CallProtocolCodec.decodeOffer(bytes)

        assertEquals(original.callId, decoded.callId)
        assertEquals(original.callType, decoded.callType)
        assertEquals(original.sdpOffer, decoded.sdpOffer)
        assertEquals(original.createdAt, decoded.createdAt)
    }

    @Test
    fun `video offer round-trip`() {
        val original = CallOfferPayload(
            callId = "call-vid-456",
            callType = CallType.VIDEO,
            sdpOffer = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n",
            createdAt = 1700000001000L
        )
        val decoded = CallProtocolCodec.decodeOffer(CallProtocolCodec.encodeOffer(original))
        assertEquals(CallType.VIDEO, decoded.callType)
    }

    @Test
    fun `ringing round-trip`() {
        val original = CallRingingPayload(callId = "call-ring-789")
        val decoded = CallProtocolCodec.decodeRinging(CallProtocolCodec.encodeRinging(original))
        assertEquals(original.callId, decoded.callId)
    }

    @Test
    fun `answer round-trip preserves SDP`() {
        val original = CallAnswerPayload(
            callId = "call-ans-101",
            sdpAnswer = "v=0\r\no=- 789 012 IN IP4 192.168.1.5\r\ns=-\r\na=group:BUNDLE audio video\r\n"
        )
        val decoded = CallProtocolCodec.decodeAnswer(CallProtocolCodec.encodeAnswer(original))
        assertEquals(original.callId, decoded.callId)
        assertEquals(original.sdpAnswer, decoded.sdpAnswer)
    }

    @Test
    fun `ice candidate round-trip with null sdpMid`() {
        val original = IceCandidatePayload(
            callId = "call-ice-202",
            sdpMid = null,
            sdpMLineIndex = 0,
            candidate = "candidate:1 1 udp 2130706431 10.0.0.1 12345 typ host"
        )
        val decoded = CallProtocolCodec.decodeIceCandidate(CallProtocolCodec.encodeIceCandidate(original))
        assertEquals(original.callId, decoded.callId)
        assertNull(decoded.sdpMid)
        assertEquals(0, decoded.sdpMLineIndex)
        assertEquals(original.candidate, decoded.candidate)
    }

    @Test
    fun `ice candidate round-trip with sdpMid`() {
        val original = IceCandidatePayload(
            callId = "call-ice-303",
            sdpMid = "audio",
            sdpMLineIndex = 1,
            candidate = "candidate:2 1 tcp 1694498815 192.168.1.1 9 typ host"
        )
        val decoded = CallProtocolCodec.decodeIceCandidate(CallProtocolCodec.encodeIceCandidate(original))
        assertEquals("audio", decoded.sdpMid)
        assertEquals(1, decoded.sdpMLineIndex)
    }

    @Test
    fun `connected round-trip`() {
        val original = CallConnectedPayload(
            callId = "call-conn-404",
            connectedAt = 1700000002000L
        )
        val decoded = CallProtocolCodec.decodeConnected(CallProtocolCodec.encodeConnected(original))
        assertEquals(original.callId, decoded.callId)
        assertEquals(original.connectedAt, decoded.connectedAt)
    }

    @Test
    fun `end round-trip preserves reason and duration`() {
        val original = CallEndPayload(
            callId = "call-end-505",
            reason = CallEndReason.LOCAL_HANGUP,
            durationMs = 300_000L
        )
        val decoded = CallProtocolCodec.decodeEnd(CallProtocolCodec.encodeEnd(original))
        assertEquals(original.callId, decoded.callId)
        assertEquals(CallEndReason.LOCAL_HANGUP, decoded.reason)
        assertEquals(300_000L, decoded.durationMs)
    }

    @Test
    fun `end with all reason types`() {
        for (reason in CallEndReason.entries) {
            val payload = CallEndPayload(callId = "call-r-$reason", reason = reason, durationMs = 0L)
            val decoded = CallProtocolCodec.decodeEnd(CallProtocolCodec.encodeEnd(payload))
            assertEquals(reason, decoded.reason)
        }
    }

    @Test
    fun `decline round-trip`() {
        val original = CallDeclinePayload(callId = "call-dec-606")
        val decoded = CallProtocolCodec.decodeDecline(CallProtocolCodec.encodeDecline(original))
        assertEquals(original.callId, decoded.callId)
    }

    @Test
    fun `busy round-trip`() {
        val original = CallBusyPayload(callId = "call-busy-707")
        val decoded = CallProtocolCodec.decodeBusy(CallProtocolCodec.encodeBusy(original))
        assertEquals(original.callId, decoded.callId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode offer with wrong magic throws`() {
        val bogus = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x04, 0x74, 0x65, 0x73, 0x74)
        CallProtocolCodec.decodeOffer(bogus)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode answer with wrong magic throws`() {
        // Use offer bytes for answer decode
        val offerBytes = CallProtocolCodec.encodeOffer(
            CallOfferPayload("c1", CallType.VOICE, "sdp", 0L)
        )
        CallProtocolCodec.decodeAnswer(offerBytes)
    }

    @Test
    fun `large SDP survives round-trip`() {
        val largeSdp = "v=0\r\n" + "a=rtpmap:96 VP8/90000\r\n".repeat(500)
        val original = CallOfferPayload(
            callId = "call-large",
            callType = CallType.VIDEO,
            sdpOffer = largeSdp,
            createdAt = 0L
        )
        val decoded = CallProtocolCodec.decodeOffer(CallProtocolCodec.encodeOffer(original))
        assertEquals(largeSdp, decoded.sdpOffer)
    }
}
