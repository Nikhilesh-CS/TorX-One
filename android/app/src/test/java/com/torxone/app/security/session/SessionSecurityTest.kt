package com.torxone.app.security.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Arrays

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
    fun testHkdfDeterministicOutput() {
        val salt = "test-salt".toByteArray(Charsets.UTF_8)
        val ikm = "test-input-key-material".toByteArray(Charsets.UTF_8)
        val info = "test-info".toByteArray(Charsets.UTF_8)

        val out1 = SessionRatchet.hkdf(salt, ikm, info, 64)
        val out2 = SessionRatchet.hkdf(salt, ikm, info, 64)

        assertEquals(64, out1.size)
        assertArrayEquals(out1, out2)
    }
}
