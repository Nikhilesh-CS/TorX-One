package com.torxone.app.crypto

import com.torxone.app.identity.IdentityCrypto
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class SessionRatchetTest {

    private val secureRandom = SecureRandom()

    private fun randomBytes(size: Int): ByteArray {
        val b = ByteArray(size)
        secureRandom.nextBytes(b)
        return b
    }

    private fun setupAliceAndBobSessions(): Pair<SessionState, SessionState> {
        val sessionInitSecret = randomBytes(32)
        val aliceRatchet = IdentityCrypto.generateX25519KeyPair()
        val bobRatchet = IdentityCrypto.generateX25519KeyPair()

        val aliceState = SessionRatchet.initializeSession(
            relationshipId = "rel-1",
            sessionInitializationSecret = sessionInitSecret,
            isInitiator = true,
            remoteRatchetPublicKey = bobRatchet.publicKey,
            localRatchetPrivateKey = aliceRatchet.privateKey,
            localRatchetPublicKey = aliceRatchet.publicKey
        )

        val bobState = SessionRatchet.initializeSession(
            relationshipId = "rel-1",
            sessionInitializationSecret = sessionInitSecret,
            isInitiator = false,
            remoteRatchetPublicKey = aliceRatchet.publicKey,
            localRatchetPrivateKey = bobRatchet.privateKey,
            localRatchetPublicKey = bobRatchet.publicKey
        )

        return aliceState to bobState
    }

    @Test
    fun testBasicEncryptDecrypt() {
        val (alice, bob) = setupAliceAndBobSessions()
        val plaintext = "Hello Bob, this is Alice!".toByteArray(Charsets.UTF_8)
        val aad = "aad-metadata-v1".toByteArray(Charsets.UTF_8)

        val encrypted = SessionRatchet.ratchetEncrypt(alice, plaintext, aad)
        val decrypted = SessionRatchet.ratchetDecrypt(bob, encrypted, aad)

        assertArrayEquals(plaintext, decrypted)
        assertEquals("Hello Bob, this is Alice!", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testPingPongAlternatingMessagesRatchetSteps() {
        val (alice, bob) = setupAliceAndBobSessions()

        for (i in 0 until 50) {
            val aMsg = "Alice msg $i".toByteArray()
            val aAad = "aad-a-$i".toByteArray()
            val aEnc = SessionRatchet.ratchetEncrypt(alice, aMsg, aAad)
            val aDec = SessionRatchet.ratchetDecrypt(bob, aEnc, aAad)
            assertArrayEquals(aMsg, aDec)

            val bMsg = "Bob reply $i".toByteArray()
            val bAad = "aad-b-$i".toByteArray()
            val bEnc = SessionRatchet.ratchetEncrypt(bob, bMsg, bAad)
            val bDec = SessionRatchet.ratchetDecrypt(alice, bEnc, bAad)
            assertArrayEquals(bMsg, bDec)
        }
    }

    @Test
    fun testContinuousSendingOneDirection() {
        val (alice, bob) = setupAliceAndBobSessions()

        val messages = (1..20).map { "Stream message $it".toByteArray() }
        val encryptedList = messages.mapIndexed { idx, msg ->
            SessionRatchet.ratchetEncrypt(alice, msg, "aad-$idx".toByteArray())
        }

        encryptedList.forEachIndexed { idx, enc ->
            val dec = SessionRatchet.ratchetDecrypt(bob, enc, "aad-$idx".toByteArray())
            assertArrayEquals(messages[idx], dec)
        }
    }

    @Test
    fun testOutOfOrderMessagesWithSkippedKeys() {
        val (alice, bob) = setupAliceAndBobSessions()

        val msg0 = "Message 0".toByteArray()
        val msg1 = "Message 1".toByteArray()
        val msg2 = "Message 2".toByteArray()
        val msg3 = "Message 3".toByteArray()

        val enc0 = SessionRatchet.ratchetEncrypt(alice, msg0, "aad".toByteArray())
        val enc1 = SessionRatchet.ratchetEncrypt(alice, msg1, "aad".toByteArray())
        val enc2 = SessionRatchet.ratchetEncrypt(alice, msg2, "aad".toByteArray())
        val enc3 = SessionRatchet.ratchetEncrypt(alice, msg3, "aad".toByteArray())

        // Receive out of order: 2, 0, 3, 1
        val dec2 = SessionRatchet.ratchetDecrypt(bob, enc2, "aad".toByteArray())
        assertArrayEquals(msg2, dec2)

        val dec0 = SessionRatchet.ratchetDecrypt(bob, enc0, "aad".toByteArray())
        assertArrayEquals(msg0, dec0)

        val dec3 = SessionRatchet.ratchetDecrypt(bob, enc3, "aad".toByteArray())
        assertArrayEquals(msg3, dec3)

        val dec1 = SessionRatchet.ratchetDecrypt(bob, enc1, "aad".toByteArray())
        assertArrayEquals(msg1, dec1)
    }

    @Test
    fun testReplayRejected() {
        val (alice, bob) = setupAliceAndBobSessions()
        val msg = "Replay test".toByteArray()
        val aad = "aad".toByteArray()

        val enc = SessionRatchet.ratchetEncrypt(alice, msg, aad)
        val dec = SessionRatchet.ratchetDecrypt(bob, enc, aad)
        assertArrayEquals(msg, dec)

        // Decrypting the same message again must throw ReplayDetectedException
        try {
            SessionRatchet.ratchetDecrypt(bob, enc, aad)
            fail("Expected ReplayDetectedException on duplicate decrypt")
        } catch (_: ReplayDetectedException) {
            // Success
        }
    }

    @Test
    fun testTamperedCiphertextRejected() {
        val (alice, bob) = setupAliceAndBobSessions()
        val msg = "Secret".toByteArray()
        val aad = "aad".toByteArray()

        val enc = SessionRatchet.ratchetEncrypt(alice, msg, aad)

        // Tamper with ciphertext
        val tamperedCipher = enc.ciphertext.copyOf()
        tamperedCipher[tamperedCipher.size - 1] = (tamperedCipher[tamperedCipher.size - 1].toInt() xor 0xFF).toByte()
        val tamperedMsg = EncryptedSessionMessage(enc.header, tamperedCipher)

        try {
            SessionRatchet.ratchetDecrypt(bob, tamperedMsg, aad)
            fail("Expected AEADBadTagException on tampered ciphertext")
        } catch (_: Exception) {
            // Success
        }
    }

    @Test
    fun testModifiedAadRejected() {
        val (alice, bob) = setupAliceAndBobSessions()
        val msg = "Secret".toByteArray()
        val validAad = "aad-original".toByteArray()
        val modifiedAad = "aad-tampered".toByteArray()

        val enc = SessionRatchet.ratchetEncrypt(alice, msg, validAad)

        try {
            SessionRatchet.ratchetDecrypt(bob, enc, modifiedAad)
            fail("Expected AEADBadTagException on modified AAD")
        } catch (_: Exception) {
            // Success
        }
    }

    @Test
    fun testMaxSkipLimitExceeded() {
        val (alice, bob) = setupAliceAndBobSessions()
        val msg = "Big gap".toByteArray()

        // Create a fake header claiming message number 1500 (> MAX_SKIP 1000)
        val enc = SessionRatchet.ratchetEncrypt(alice, msg, byteArrayOf())
        val fakeHeader = MessageHeader(enc.header.ratchetPublicKey, 0, 1005)
        val fakeEnc = EncryptedSessionMessage(fakeHeader, enc.ciphertext)

        try {
            SessionRatchet.ratchetDecrypt(bob, fakeEnc, byteArrayOf())
            fail("Expected MaxSkipExceededException when gap exceeds 1000")
        } catch (_: MaxSkipExceededException) {
            // Success
        }
    }
}
