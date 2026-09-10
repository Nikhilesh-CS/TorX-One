package com.torxone.app.protocol

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 3 Unit Tests: Cryptographic Envelope Protocol & Hash Chain
 *
 * Verifies:
 * 1. Dedicated Protocol Layer: Wire JSON serialization/deserialization with version 2
 * 2. Genuine cryptographic message hash chain: H(prevHash || connId || queueId || seq || type || cipher)
 * 3. Tamper detection: modification of any header or ciphertext alters hash
 * 4. Non-fatal continuity verification: gaps/retransmissions return GapDetected rather than fatal errors
 */
@RunWith(RobolectricTestRunner::class)
class ProtocolEnvelopeHashChainTest {

    private val connId = "550e8400-e29b-41d4-a716-446655440000"
    private val queueId = "8f3a1c20-7b9d-4e51-9c84-112233445566"

    @Test
    fun testWireSerializationAndParsing_roundtrip() {
        val crypto = CryptoMetadata(
            sessionId = "sess-101",
            msgNum = 5,
            ratchetPub = "pub_hex_12345678",
            iv = "iv_base64_abc",
            signature = "sig_hex_xyz"
        )
        val original = ProtocolEnvelope(
            version = 2,
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 42L,
            previousHash = "prev_hash_987",
            timestamp = 1788960000000L,
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "encrypted_payload_bytes_base64",
            cryptoMetadata = crypto
        )

        val jsonStr = ProtocolEnvelope.toJson(original)
        assertNotNull(jsonStr)

        val parsed = ProtocolEnvelope.fromJson(jsonStr)
        assertEquals(2, parsed.version)
        assertEquals(connId, parsed.connectionId)
        assertEquals(queueId, parsed.queueId)
        assertEquals(42L, parsed.sequenceNumber)
        assertEquals("prev_hash_987", parsed.previousHash)
        assertEquals(1788960000000L, parsed.timestamp)
        assertEquals(ProtocolEnvelope.TYPE_MESSAGE, parsed.messageType)
        assertEquals("encrypted_payload_bytes_base64", parsed.ciphertext)

        assertNotNull(parsed.cryptoMetadata)
        assertEquals("sess-101", parsed.cryptoMetadata?.sessionId)
        assertEquals(5, parsed.cryptoMetadata?.msgNum)
        assertEquals("pub_hex_12345678", parsed.cryptoMetadata?.ratchetPub)
        assertEquals("iv_base64_abc", parsed.cryptoMetadata?.iv)
        assertEquals("sig_hex_xyz", parsed.cryptoMetadata?.signature)
    }

    @Test
    fun testHashChainComputation_deterministicAndSensitiveToTampering() {
        val hash1 = ProtocolEnvelope.computeEnvelopeHash(
            previousHash = null,
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 1L,
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "hello world"
        )
        assertNotNull(hash1)
        assertEquals(64, hash1.length) // 32-byte SHA-256 hex string

        // Deterministic reproduction
        val hash1Reproduction = ProtocolEnvelope.computeEnvelopeHash(
            previousHash = null,
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 1L,
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "hello world"
        )
        assertEquals(hash1, hash1Reproduction)

        // Altering ciphertext produces different hash
        val tamperedCipher = ProtocolEnvelope.computeEnvelopeHash(
            previousHash = null,
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 1L,
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "hello world!"
        )
        assertNotEquals(hash1, tamperedCipher)

        // Altering sequence number produces different hash
        val tamperedSeq = ProtocolEnvelope.computeEnvelopeHash(
            previousHash = null,
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 2L,
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "hello world"
        )
        assertNotEquals(hash1, tamperedSeq)

        // Altering queueId produces different hash
        val tamperedQueue = ProtocolEnvelope.computeEnvelopeHash(
            previousHash = null,
            connectionId = connId,
            queueId = "other_queue_id",
            sequenceNumber = 1L,
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "hello world"
        )
        assertNotEquals(hash1, tamperedQueue)
    }

    @Test
    fun testHashChainContinuity_threeMessageProgression() {
        // Message 1 (Genesis)
        val h1 = ProtocolEnvelope.computeEnvelopeHash(
            previousHash = null,
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 1L,
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "first message"
        )
        val env1 = ProtocolEnvelope(
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 1L,
            previousHash = null,
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "first message"
        )
        val res1 = ProtocolEnvelope.verifyContinuity(lastCommittedHash = null, envelope = env1)
        assertTrue("Genesis message must be continuous", res1 is HashChainVerificationResult.Continuous)

        // Message 2
        val h2 = ProtocolEnvelope.computeEnvelopeHash(
            previousHash = h1,
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 2L,
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "second message"
        )
        val env2 = ProtocolEnvelope(
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 2L,
            previousHash = h1,
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "second message"
        )
        val res2 = ProtocolEnvelope.verifyContinuity(lastCommittedHash = h1, envelope = env2)
        assertTrue("Linked message 2 must be continuous", res2 is HashChainVerificationResult.Continuous)

        // Message 3
        val h3 = ProtocolEnvelope.computeEnvelopeHash(
            previousHash = h2,
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 3L,
            messageType = ProtocolEnvelope.TYPE_ACK,
            ciphertext = "ack message"
        )
        assertNotNull(h3)
        val env3 = ProtocolEnvelope(
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 3L,
            previousHash = h2,
            messageType = ProtocolEnvelope.TYPE_ACK,
            ciphertext = "ack message"
        )
        val res3 = ProtocolEnvelope.verifyContinuity(lastCommittedHash = h2, envelope = env3)
        assertTrue("Linked message 3 must be continuous", res3 is HashChainVerificationResult.Continuous)
    }

    @Test
    fun testHashChainContinuity_nonFatalGapDetection() {
        val h1 = ProtocolEnvelope.computeEnvelopeHash(
            previousHash = null,
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 1L,
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "msg1"
        )

        // Envelope 3 arrives out-of-order before envelope 2 was committed
        val env3OutOfOrder = ProtocolEnvelope(
            connectionId = connId,
            queueId = queueId,
            sequenceNumber = 3L,
            previousHash = "some_future_h2_hash",
            messageType = ProtocolEnvelope.TYPE_MESSAGE,
            ciphertext = "msg3"
        )

        // Local state has only committed up to h1
        val res = ProtocolEnvelope.verifyContinuity(lastCommittedHash = h1, envelope = env3OutOfOrder)
        assertTrue("Out-of-order arrival must be detected as a non-fatal GapDetected", res is HashChainVerificationResult.GapDetected)

        val gap = res as HashChainVerificationResult.GapDetected
        assertEquals(h1, gap.expectedPrevHash)
        assertEquals("some_future_h2_hash", gap.actualPrevHash)
    }
}
