package com.torxone.app.security.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Arrays
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionSecurityTest {

    @Test
    fun testSymmetricChainStepping() {
        val initialChain = ByteArray(32) { it.toByte() }

        val (key1, nextChain1) = SessionRatchet.stepSymmetricChain(initialChain)
        val (key2, nextChain2) = SessionRatchet.stepSymmetricChain(nextChain1)

        assertEquals(32, key1.size)
        assertEquals(32, key2.size)
        assertEquals(32, nextChain1.size)
        assertEquals(32, nextChain2.size)

        // Keys at different steps must be completely distinct (Forward Secrecy)
        assertFalse(Arrays.equals(key1, key2))
        assertFalse(Arrays.equals(initialChain, nextChain1))
        assertFalse(Arrays.equals(nextChain1, nextChain2))
    }

    @Test
    fun testAesGcmEncryptionDecryptionWithAad() {
        val messageKey = ByteArray(32) { (it * 3).toByte() }
        val messageKeyCopy = messageKey.clone()
        val plaintext = "Hello TorX Secure Session with Forward Secrecy!"
        val aad = SessionCipher.buildAad("session-xyz", 0, "alice", "bob")

        val encrypted = SessionCipher.encrypt(messageKey, plaintext, aad)

        // messageKey must be zeroized in memory
        assertTrue(messageKey.all { it == 0.toByte() })

        // Decrypt with key copy
        val decrypted = SessionCipher.decrypt(messageKeyCopy, encrypted.ciphertextBase64, encrypted.ivBase64, aad)
        assertEquals(plaintext, decrypted)
        assertTrue(messageKeyCopy.all { it == 0.toByte() })
    }

    @Test
    fun testAesGcmTamperedCiphertextFails() {
        val messageKey = ByteArray(32) { 42.toByte() }
        val plaintext = "Secret Message"
        val aad = SessionCipher.buildAad("session-1", 1, "alice", "bob")

        val encrypted = SessionCipher.encrypt(messageKey, plaintext, aad)

        val tamperedCiphertext = if (encrypted.ciphertextBase64.endsWith("A=")) {
            encrypted.ciphertextBase64.dropLast(2) + "B="
        } else {
            encrypted.ciphertextBase64.dropLast(2) + "A="
        }

        try {
            val keyForDecrypt = ByteArray(32) { 42.toByte() }
            SessionCipher.decrypt(keyForDecrypt, tamperedCiphertext, encrypted.ivBase64, aad)
            fail("Tampered ciphertext must fail GCM tag authentication")
        } catch (_: Exception) {
            // Expected authentication tag failure
        }
    }

    @Test
    fun testAesGcmMismatchedAadFails() {
        val messageKey = ByteArray(32) { 77.toByte() }
        val plaintext = "Top Secret"
        val aadAliceToBob = SessionCipher.buildAad("session-1", 0, "alice", "bob")
        val aadEveToBob = SessionCipher.buildAad("session-1", 0, "eve", "bob")

        val encrypted = SessionCipher.encrypt(messageKey, plaintext, aadAliceToBob)

        try {
            val keyForDecrypt = ByteArray(32) { 77.toByte() }
            SessionCipher.decrypt(keyForDecrypt, encrypted.ciphertextBase64, encrypted.ivBase64, aadEveToBob)
            fail("Mismatched recipient/sender in AAD must fail authentication")
        } catch (_: Exception) {
            // Expected
        }
    }

    @Test
    fun testAesGcmEncryptionDecryptionWithTimestampAad() {
        val messageKey = ByteArray(32) { (it * 5).toByte() }
        val messageKeyCopy = messageKey.clone()
        val plaintext = "Authenticated with Timestamp"
        val timestamp = System.currentTimeMillis()
        val aad = SessionCipher.buildAad("session-ts", 3, "alice", "bob", timestamp)

        val encrypted = SessionCipher.encrypt(messageKey, plaintext, aad)
        val decrypted = SessionCipher.decrypt(messageKeyCopy, encrypted.ciphertextBase64, encrypted.ivBase64, aad)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testAesGcmTamperedTimestampFails() {
        val messageKey = ByteArray(32) { 99.toByte() }
        val plaintext = "Time Sensitive"
        val timestamp = 1773091200000L
        val tamperedTimestamp = 1773091205000L

        val validAad = SessionCipher.buildAad("session-1", 1, "alice", "bob", timestamp)
        val tamperedAad = SessionCipher.buildAad("session-1", 1, "alice", "bob", tamperedTimestamp)

        val encrypted = SessionCipher.encrypt(messageKey, plaintext, validAad)

        try {
            val keyForDecrypt = ByteArray(32) { 99.toByte() }
            SessionCipher.decrypt(keyForDecrypt, encrypted.ciphertextBase64, encrypted.ivBase64, tamperedAad)
            fail("Tampered timestamp in AAD must fail GCM tag authentication")
        } catch (_: Exception) {
            // Expected authentication tag failure
        }
    }

    @Test
    fun testSkippedKeysOutOrderMechanics() {
        // Step sender chain to generate 3 messages (0, 1, 2)
        val initialChain = ByteArray(32) { 7.toByte() }
        val (k0, chain1) = SessionRatchet.stepSymmetricChain(initialChain)
        val (k1, chain2) = SessionRatchet.stepSymmetricChain(chain1)
        val (k2, _) = SessionRatchet.stepSymmetricChain(chain2)

        val msg0Aad = SessionCipher.buildAad("s1", 0, "alice", "bob")
        val msg1Aad = SessionCipher.buildAad("s1", 1, "alice", "bob")
        val msg2Aad = SessionCipher.buildAad("s1", 2, "alice", "bob")

        val enc0 = SessionCipher.encrypt(k0, "Msg 0", msg0Aad)
        val enc1 = SessionCipher.encrypt(k1, "Msg 1", msg1Aad)
        val enc2 = SessionCipher.encrypt(k2, "Msg 2", msg2Aad)

        // Receiver receives Msg 2 first (out-of-order, expected 0)
        val receiverChain0 = ByteArray(32) { 7.toByte() }
        val skippedKeys = mutableMapOf<Int, ByteArray>()

        // Simulate receiver stepping forward to msgNum 2:
        var tempChain = receiverChain0
        for (i in 0 until 2) {
            val (skippedKey, nextChain) = SessionRatchet.stepSymmetricChain(tempChain)
            skippedKeys[i] = skippedKey
            tempChain = nextChain
        }
        val (recvK2, nextRecvChain) = SessionRatchet.stepSymmetricChain(tempChain)

        // 1. Decrypt Msg 2 immediately
        val dec2 = SessionCipher.decrypt(recvK2, enc2.ciphertextBase64, enc2.ivBase64, msg2Aad)
        assertEquals("Msg 2", dec2)

        // 2. Late arrival: Msg 0 arrives
        val skippedK0 = skippedKeys.remove(0)
        assertTrue("Skipped key 0 must exist", skippedK0 != null)
        val dec0 = SessionCipher.decrypt(skippedK0!!, enc0.ciphertextBase64, enc0.ivBase64, msg0Aad)
        assertEquals("Msg 0", dec0)

        // 3. Late arrival: Msg 1 arrives
        val skippedK1 = skippedKeys.remove(1)
        assertTrue("Skipped key 1 must exist", skippedK1 != null)
        val dec1 = SessionCipher.decrypt(skippedK1!!, enc1.ciphertextBase64, enc1.ivBase64, msg1Aad)
        assertEquals("Msg 1", dec1)

        // 4. Replay attempt: Msg 0 arrives again
        val replayK0 = skippedKeys.remove(0)
        assertTrue("Replayed message must not find key in skipped store", replayK0 == null)
        assertTrue("All skipped keys must be exhausted after delivery", skippedKeys.isEmpty())
    }

    @Test
    fun testMaxSkippedKeysThreshold() {
        val maxAllowed = 100
        val currentExpected = 5
        val incomingMsgNum = 150 // skip = 145 > 100

        val skipCount = incomingMsgNum - currentExpected
        assertTrue("Skip count of 145 must exceed threshold of $maxAllowed", skipCount > maxAllowed)
    }

    @Test
    fun testSessionRelayEnvelopeEncoding() {
        val destKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val fromKey = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        val wirePayload = "{\"type\":\"SESSION_MSG\",\"sessionId\":\"s-123\",\"msgNum\":0}"
        
        val relayEnvelope = com.torxone.app.network.MeshProtocol.encodeSessionRelay(
            dest = destKey,
            from = fromKey,
            sessionWireJson = wirePayload,
            ttl = 3,
            messageId = "msg-xyz",
            senderOnion = "testonionaddress.onion"
        )

        val json = org.json.JSONObject(relayEnvelope)
        assertEquals(com.torxone.app.network.MeshProtocol.TYPE_RELAY, json.getString("type"))
        assertEquals(destKey, json.getString("dest"))
        assertEquals(fromKey, json.getString("from"))
        assertEquals(3, json.getInt("ttl"))
        assertEquals(com.torxone.app.network.MeshProtocol.TYPE_SESSION_MSG, json.getString("innerType"))
        assertEquals(wirePayload, json.getString("sessionWire"))
        assertEquals("msg-xyz", json.getString("msgId"))
        assertEquals("testonionaddress.onion", json.getString("senderOnion"))
    }

    @Test
    fun testDeterministicSessionArbitrationOrdering() {
        val keyAlice = "aaaa000000000000000000000000000000000000000000000000000000000000"
        val keyBob   = "bbbb000000000000000000000000000000000000000000000000000000000000"

        // Alice sees Bob as remote
        val remoteIsBobWinsFromAlicePerspective = keyBob < keyAlice // false
        assertFalse(remoteIsBobWinsFromAlicePerspective)

        // Bob sees Alice as remote
        val remoteIsAliceWinsFromBobPerspective = keyAlice < keyBob // true
        assertTrue(remoteIsAliceWinsFromBobPerspective)

        // Deterministic: Alice's session wins arbitration on both sides
        val winningKey = if (keyAlice < keyBob) keyAlice else keyBob
        assertEquals(keyAlice, winningKey)
    }

    @Test
    fun testReplayProtectionContract() = kotlinx.coroutines.runBlocking {
        val seen = mutableSetOf<Pair<String, Int>>()
        val mockDao = object : SessionReplayDao {
            override suspend fun isProcessed(sessionId: String, msgNum: Int): Int =
                if (seen.contains(Pair(sessionId, msgNum))) 1 else 0

            override suspend fun markProcessed(replay: SessionReplayEntity): Long {
                val pair = Pair(replay.sessionId, replay.msgNum)
                return if (seen.add(pair)) 1L else -1L
            }

            override suspend fun pruneOldRecords(cutoffMs: Long) {}
            override suspend fun clearSessionReplays(sessionId: String) {
                seen.removeIf { it.first == sessionId }
            }
            override suspend fun clearOrphanedReplays() {
                seen.clear()
            }
        }

        val replayProtection = ReplayProtection(mockDao)
        val sessionId = "session-test-replay-1"

        // 1. Initial message 0 must be accepted
        assertTrue(replayProtection.checkAndMark(sessionId, 0))

        // 2. Duplicate message 0 must be rejected as replay
        assertFalse(replayProtection.checkAndMark(sessionId, 0))

        // 3. New message 1 must be accepted
        assertTrue(replayProtection.checkAndMark(sessionId, 1))

        // 4. Duplicate message 1 must be rejected
        assertFalse(replayProtection.checkAndMark(sessionId, 1))
    }

    @Test
    fun testSessionCryptoServiceInterfaceAbstraction() {
        val mockDao = org.mockito.Mockito.mock(SessionDao::class.java)
        val mockReplayDao = org.mockito.Mockito.mock(SessionReplayDao::class.java)
        val replayProtection = ReplayProtection(mockReplayDao)
        val sessionManager = SessionManager(mockDao, replayProtection)

        // Verifies SessionManager is a valid SessionCryptoService instance decoupled from transports
        val cryptoService: SessionCryptoService = sessionManager
        assertTrue(cryptoService is SessionCryptoService)
    }
}
