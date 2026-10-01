package com.torxone.app.protocol

import org.junit.Assert.*
import org.junit.Test

class PeerCapabilitiesTest {
    @Test fun advertisementUsesLegacyProfilePrefixAndKnownReceiveFeaturesOnly() {
        val bytes = PeerCapabilitiesCodec.encode("Alice", "Hello")
        assertTrue(bytes.toString(Charsets.UTF_8).startsWith("Alice\nHello\n"))
        assertEquals(PeerCapabilitiesCodec.supported.map { it.name }.toSet(), PeerCapabilitiesCodec.decode(bytes))
        assertFalse(PeerCapabilitiesCodec.supported.contains(PeerFeature.POLL_V1))
    }
    @Test fun legacyProfileIsNotAFeatureAdvertisement() { assertNull(PeerCapabilitiesCodec.decode("Alice\nHello".toByteArray())) }
    @Test fun emptyAdvertisementRemovesCapabilities() { assertEquals(emptySet<String>(), PeerCapabilitiesCodec.decode(PeerCapabilitiesCodec.encode("Alice", "", emptySet()))) }
    @Test fun unknownMessageRetainsWireNameAndSequenceWithoutTreatingPayloadAsText() {
        val future = SecureEnvelope(conversationId = "chat", senderIdentity = "alice", recipientBinding = "bob",
            messageType = MessageType.UNKNOWN, unknownMessageType = "FUTURE_POLL", payload = byteArrayOf(0, 1, 2), directionSequence = 4)
        val decoded = ProtocolCodec.decodeSecureEnvelope(ProtocolCodec.encodeSecureEnvelope(future))
        assertEquals(MessageType.UNKNOWN, decoded.messageType)
        assertEquals("FUTURE_POLL", decoded.unknownMessageType)
        assertEquals(4L, decoded.directionSequence)
        assertArrayEquals(future.payload, decoded.payload)
        assertArrayEquals(ProtocolCodec.encodeSecureEnvelope(future), ProtocolCodec.encodeSecureEnvelope(decoded))
    }
    @Test(expected = IllegalArgumentException::class) fun malformedFutureNameRejected() {
        ProtocolCodec.encodeSecureEnvelope(SecureEnvelope(conversationId = "chat", senderIdentity = "a", recipientBinding = "b",
            messageType = MessageType.UNKNOWN, unknownMessageType = "bad\nname", payload = byteArrayOf()))
    }
}
