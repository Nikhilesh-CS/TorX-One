package com.torxone.app.identity

import org.junit.Assert.*
import org.junit.Test

class IdentityCryptoTest {

    @Test
    fun testEd25519KeyPairGenerationAndKeySizes() {
        val keyPair1 = IdentityCrypto.generateEd25519KeyPair()
        val keyPair2 = IdentityCrypto.generateEd25519KeyPair()

        assertEquals("Ed25519 public key must be exactly 32 bytes", 32, keyPair1.publicKey.size)
        assertEquals("Ed25519 private key must be exactly 32 bytes", 32, keyPair1.privateKey.size)

        assertFalse("Subsequent key pairs must be cryptographically distinct",
            keyPair1.publicKey.contentEquals(keyPair2.publicKey))
        assertFalse("Subsequent private keys must be cryptographically distinct",
            keyPair1.privateKey.contentEquals(keyPair2.privateKey))
    }

    @Test
    fun testEd25519SignAndVerifySuccess() {
        val keyPair = IdentityCrypto.generateEd25519KeyPair()
        val payload = "TorX One Protocol Identity Authentication Payload".toByteArray(Charsets.UTF_8)

        val signature = IdentityCrypto.signEd25519(keyPair.privateKey, payload)
        assertEquals("Ed25519 signature must be exactly 64 bytes", 64, signature.size)

        val isValid = IdentityCrypto.verifyEd25519(keyPair.publicKey, payload, signature)
        assertTrue("Signature verification must succeed for valid key and payload", isValid)
    }

    @Test
    fun testEd25519VerifyTamperedPayloadFails() {
        val keyPair = IdentityCrypto.generateEd25519KeyPair()
        val originalPayload = "Valid message".toByteArray(Charsets.UTF_8)
        val signature = IdentityCrypto.signEd25519(keyPair.privateKey, originalPayload)

        val tamperedPayload = "Valid messagE".toByteArray(Charsets.UTF_8)
        val isValid = IdentityCrypto.verifyEd25519(keyPair.publicKey, tamperedPayload, signature)
        assertFalse("Verification must fail on tampered payload", isValid)
    }

    @Test
    fun testEd25519VerifyWrongPublicKeyFails() {
        val alice = IdentityCrypto.generateEd25519KeyPair()
        val bob = IdentityCrypto.generateEd25519KeyPair()
        val payload = "Confidential message".toByteArray(Charsets.UTF_8)

        val signature = IdentityCrypto.signEd25519(alice.privateKey, payload)
        val isValidWithBob = IdentityCrypto.verifyEd25519(bob.publicKey, payload, signature)
        assertFalse("Verification must fail when checked against a different public key", isValidWithBob)
    }

    @Test
    fun testEd25519VerifyMalformedInputsDoesNotThrow() {
        val keyPair = IdentityCrypto.generateEd25519KeyPair()
        val payload = "Test".toByteArray()
        val validSig = IdentityCrypto.signEd25519(keyPair.privateKey, payload)

        // Truncated / empty signature
        assertFalse(IdentityCrypto.verifyEd25519(keyPair.publicKey, payload, ByteArray(0)))
        assertFalse(IdentityCrypto.verifyEd25519(keyPair.publicKey, payload, ByteArray(63)))
        assertFalse(IdentityCrypto.verifyEd25519(keyPair.publicKey, payload, ByteArray(65)))

        // Truncated / wrong public key size
        assertFalse(IdentityCrypto.verifyEd25519(ByteArray(0), payload, validSig))
        assertFalse(IdentityCrypto.verifyEd25519(ByteArray(31), payload, validSig))
        assertFalse(IdentityCrypto.verifyEd25519(ByteArray(33), payload, validSig))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testEd25519SignInvalidKeyLengthThrows() {
        IdentityCrypto.signEd25519(ByteArray(16), "Test".toByteArray())
    }

    @Test
    fun testX25519DiffieHellmanMutualAgreement() {
        val alice = IdentityCrypto.generateX25519KeyPair()
        val bob = IdentityCrypto.generateX25519KeyPair()

        assertEquals(32, alice.publicKey.size)
        assertEquals(32, alice.privateKey.size)
        assertEquals(32, bob.publicKey.size)
        assertEquals(32, bob.privateKey.size)

        // Alice computes DH with Bob's public key
        val secretAlice = IdentityCrypto.diffieHellmanX25519(alice.privateKey, bob.publicKey)
        // Bob computes DH with Alice's public key
        val secretBob = IdentityCrypto.diffieHellmanX25519(bob.privateKey, alice.publicKey)

        assertEquals("Shared secret must be 32 bytes", 32, secretAlice.size)
        assertArrayEquals("Both parties must arrive at the exact same shared secret", secretAlice, secretBob)
        assertFalse("Shared secret must not be all zeros", secretAlice.all { it == 0.toByte() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun testX25519InvalidKeyLengthThrows() {
        IdentityCrypto.diffieHellmanX25519(ByteArray(16), ByteArray(32))
    }

    @Test
    fun testHkdfDeterministicDerivation() {
        val ikm = "shared-input-key-material-12345678".toByteArray()
        val salt = "custom-salt-value-for-testing-12".toByteArray()
        val info1 = "purpose-encryption".toByteArray()
        val info2 = "purpose-authentication".toByteArray()

        val key1a = IdentityCrypto.hkdf(ikm, salt, info1, 32)
        val key1b = IdentityCrypto.hkdf(ikm, salt, info1, 32)
        val key2 = IdentityCrypto.hkdf(ikm, salt, info2, 32)

        assertArrayEquals("Same inputs must yield byte-for-byte identical output", key1a, key1b)
        assertFalse("Different info tags must yield cryptographically separated keys", key1a.contentEquals(key2))

        val keyShort = IdentityCrypto.hkdf(ikm, salt, info1, 16)
        assertEquals("Requested output length must be respected", 16, keyShort.size)
    }

    @Test
    fun testComputeFingerprintFormatting() {
        val key = ByteArray(32) { it.toByte() }
        val fingerprint = IdentityCrypto.computeFingerprint(key)

        // Format is: "XXXX XXXX XXXX XXXX XXXX XXXX XXXX XXXX" (8 blocks of 4 hex characters separated by 7 spaces = 39 chars)
        assertEquals(39, fingerprint.length)
        val blocks = fingerprint.split(" ")
        assertEquals(8, blocks.size)
        assertTrue(blocks.all { it.length == 4 && it.all { c -> c.isLetterOrDigit() && (c.isDigit() || c.isUpperCase()) } })

        // Determinism
        assertEquals(fingerprint, IdentityCrypto.computeFingerprint(key))

        val differentKey = ByteArray(32) { (it + 1).toByte() }
        assertNotEquals(fingerprint, IdentityCrypto.computeFingerprint(differentKey))
    }

    @Test
    fun testHmacSha256AndQueueAuthenticator() {
        val secret = ByteArray(32) { 0x42.toByte() }
        val envelopeId = "env-12345"
        val queueAddress = "queue-torx-main"
        val ciphertext = "encrypted-payload-data".toByteArray(Charsets.UTF_8)

        val mac1 = IdentityCrypto.computeQueueAuthenticator(secret, envelopeId, queueAddress, ciphertext)
        val mac2 = IdentityCrypto.computeQueueAuthenticator(secret, envelopeId, queueAddress, ciphertext)

        assertEquals(32, mac1.size)
        assertArrayEquals("Same parameters must produce identical queue authenticator", mac1, mac2)

        // Changing ciphertext changes MAC
        val macAlteredCiphertext = IdentityCrypto.computeQueueAuthenticator(
            secret, envelopeId, queueAddress, "tampered-payload-data".toByteArray(Charsets.UTF_8)
        )
        assertFalse("Altered ciphertext must invalidate MAC", mac1.contentEquals(macAlteredCiphertext))

        // Changing queueAddress changes MAC
        val macAlteredQueue = IdentityCrypto.computeQueueAuthenticator(
            secret, envelopeId, "queue-other", ciphertext
        )
        assertFalse("Altered queueAddress must invalidate MAC", mac1.contentEquals(macAlteredQueue))
    }
}
