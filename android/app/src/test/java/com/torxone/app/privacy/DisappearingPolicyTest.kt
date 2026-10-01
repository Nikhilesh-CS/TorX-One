package com.torxone.app.privacy

import com.torxone.app.protocol.*
import org.junit.Assert.*
import org.junit.Test

class DisappearingPolicyTest {
    @Test fun disabledTimerKeepsLegacyWireAndExpiryRoundTripsWithBoundedExtension() {
        val message = SecureEnvelope(conversationId = "chat", senderIdentity = "alice", recipientBinding = "bob",
            messageType = MessageType.TEXT, timestamp = 1000, payload = "hello".toByteArray(), directionSequence = 1)
        val legacy = ProtocolCodec.encodeSecureEnvelope(message)
        assertNull(ProtocolCodec.decodeSecureEnvelope(legacy).expiresAt)
        val expiry = ProtocolCodec.encodeSecureEnvelope(message.copy(expiresAt = 61_000))
        assertEquals(legacy.size + 12, expiry.size)
        assertArrayEquals(legacy, expiry.copyOf(legacy.size))
        assertEquals(61_000L, ProtocolCodec.decodeSecureEnvelope(expiry).expiresAt)
    }
    @Test fun expiryIsAbsoluteAndCannotBeExtendedByDelayedReceipt() {
        assertEquals(61_000L, DisappearingPolicy.expiry(1000, 60_000))
        assertFalse(DisappearingPolicy.expired(61_000, 60_999))
        assertTrue(DisappearingPolicy.expired(61_000, 61_000))
        assertNull(DisappearingPolicy.expiry(1000, null))
    }
    @Test fun invalidAndOverflowTimersAreRejected() {
        for (duration in listOf(-1L, 0L, 59_999L, DisappearingPolicy.MAX_TIMER_MS + 1)) {
            assertTrue(runCatching { DisappearingPolicy.expiry(1000, duration) }.isFailure)
        }
        assertTrue(runCatching { DisappearingPolicy.expiry(Long.MAX_VALUE, 60_000) }.isFailure)
        assertTrue(runCatching { DisappearingPolicy.validate(1000, 999) }.isFailure)
    }
    @Test fun malformedExpiryExtensionRejected() {
        val message = SecureEnvelope(conversationId = "chat", senderIdentity = "alice", recipientBinding = "bob",
            messageType = MessageType.TEXT, timestamp = 1000, payload = byteArrayOf(), expiresAt = 61_000)
        val valid = ProtocolCodec.encodeSecureEnvelope(message)
        assertTrue(runCatching { ProtocolCodec.decodeSecureEnvelope(valid.copyOf(valid.size - 1)) }.isFailure)
        val altered = valid.copyOf(); altered[altered.size - 12] = 0
        assertTrue(runCatching { ProtocolCodec.decodeSecureEnvelope(altered) }.isFailure)
    }
}
