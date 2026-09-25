package com.torxone.app.protocol

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProtocolCodecTest {

    @Test
    fun testSecureEnvelopeDirectRoundTrip() {
        val envelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = UUID.randomUUID().toString(),
            conversationId = "conv_123",
            senderIdentity = "id_alice",
            recipientBinding = "id_bob",
            messageType = MessageType.TEXT,
            timestamp = 1700000000000L,
            payload = "Hello Bob".toByteArray(Charsets.UTF_8),
            replyToMessageId = "msg_prev",
            groupMetadata = null,
            directionSequence = 42L
        )

        val bytes = ProtocolCodec.encodeSecureEnvelope(envelope)
        val decoded = ProtocolCodec.decodeSecureEnvelope(bytes)

        assertEquals(envelope.logicalMessageId, decoded.logicalMessageId)
        assertEquals(envelope.conversationId, decoded.conversationId)
        assertEquals(envelope.senderIdentity, decoded.senderIdentity)
        assertEquals(envelope.recipientBinding, decoded.recipientBinding)
        assertEquals(envelope.messageType, decoded.messageType)
        assertEquals(envelope.timestamp, decoded.timestamp)
        assertEquals(envelope.replyToMessageId, decoded.replyToMessageId)
        assertEquals(envelope.directionSequence, decoded.directionSequence)
        assertArrayEquals(envelope.payload, decoded.payload)
        assertNull(decoded.groupMetadata)
    }

    @Test
    fun testSecureEnvelopeGroupMetadataRoundTrip() {
        val groupMeta = GroupEnvelopeMetadata(
            groupId = "group_abc",
            groupEpoch = 3,
            keyVersion = 1
        )
        val envelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = UUID.randomUUID().toString(),
            conversationId = "group_abc",
            senderIdentity = "id_alice",
            recipientBinding = "id_bob",
            messageType = MessageType.TEXT,
            timestamp = 1700000000000L,
            payload = "Hello Group".toByteArray(Charsets.UTF_8),
            replyToMessageId = null,
            groupMetadata = groupMeta,
            directionSequence = 10L
        )

        val bytes = ProtocolCodec.encodeSecureEnvelope(envelope)
        val decoded = ProtocolCodec.decodeSecureEnvelope(bytes)

        assertEquals(envelope.logicalMessageId, decoded.logicalMessageId)
        assertEquals(envelope.conversationId, decoded.conversationId)
        assertNotNull(decoded.groupMetadata)
        assertEquals("group_abc", decoded.groupMetadata?.groupId)
        assertEquals(3, decoded.groupMetadata?.groupEpoch)
        assertEquals(1, decoded.groupMetadata?.keyVersion)
    }
}
