package com.torxone.app.media

import org.junit.Assert.*
import org.junit.Test
import java.security.GeneralSecurityException
import java.util.UUID

/**
 * Tests for MediaCrypto and MediaProtocolCodec.
 *
 * Covers:
 * - 256-bit key generation
 * - AES-GCM encryption/decryption round-trip
 * - Nonce uniqueness (different ciphertexts for identical plaintext)
 * - Cryptographic failure on wrong key
 * - Tampered ciphertext rejection (AEAD auth tag failure)
 * - SHA-256 integrity verification and tamper detection
 * - Serialization roundtrips for descriptors and chunk packets
 */
class MediaCryptoTest {

    @Test
    fun `generateMediaKey returns 32 bytes`() {
        val key = MediaCrypto.generateMediaKey()
        assertEquals(32, key.size)

        val key2 = MediaCrypto.generateMediaKey()
        assertFalse(key.contentEquals(key2))
    }

    @Test
    fun `encryption and decryption round trip successfully`() {
        val key = MediaCrypto.generateMediaKey()
        val originalText = "TorX One encrypted attachment content test payload with unicode: 🔒🚀"
        val plaintext = originalText.toByteArray(Charsets.UTF_8)

        val ciphertext = MediaCrypto.encrypt(key, plaintext)
        assertNotNull(ciphertext)
        assertTrue(ciphertext.size > plaintext.size) // Includes 12-byte IV + 16-byte tag

        val decrypted = MediaCrypto.decrypt(key, ciphertext)
        assertEquals(originalText, String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun `encrypt produces unique IV for identical plaintexts`() {
        val key = MediaCrypto.generateMediaKey()
        val plaintext = "Same file content repeated".toByteArray(Charsets.UTF_8)

        val ct1 = MediaCrypto.encrypt(key, plaintext)
        val ct2 = MediaCrypto.encrypt(key, plaintext)

        assertFalse("Ciphertexts must differ due to unique random IVs", ct1.contentEquals(ct2))
    }

    @Test
    fun `decrypt with wrong key fails AEAD authentication`() {
        val key1 = MediaCrypto.generateMediaKey()
        val key2 = MediaCrypto.generateMediaKey()
        val plaintext = "Secret payload".toByteArray(Charsets.UTF_8)

        val ciphertext = MediaCrypto.encrypt(key1, plaintext)

        try {
            MediaCrypto.decrypt(key2, ciphertext)
            fail("Decryption with wrong key must throw an AEAD authentication exception")
        } catch (_: GeneralSecurityException) {
            // Expected
        }
    }

    @Test
    fun `decrypt with tampered ciphertext fails AEAD authentication`() {
        val key = MediaCrypto.generateMediaKey()
        val plaintext = "Untampered data".toByteArray(Charsets.UTF_8)
        val ciphertext = MediaCrypto.encrypt(key, plaintext)

        // Flip a bit in the ciphertext body
        ciphertext[ciphertext.size - 5] = (ciphertext[ciphertext.size - 5].toInt() xor 0x01).toByte()

        try {
            MediaCrypto.decrypt(key, ciphertext)
            fail("Decryption with tampered ciphertext must fail authentication")
        } catch (_: GeneralSecurityException) {
            // Expected
        }
    }

    @Test
    fun `SHA-256 integrity verification detects alterations`() {
        val data = "Important file bytes 1234567890".toByteArray(Charsets.UTF_8)
        val hashHex = MediaCrypto.sha256Hex(data)
        assertEquals(64, hashHex.length)

        assertTrue(MediaCrypto.verifyIntegrity(data, hashHex))
        assertTrue(MediaCrypto.verifyIntegrity(data, hashHex.uppercase()))

        val tamperedData = "Important file bytes 1234567891".toByteArray(Charsets.UTF_8)
        assertFalse(MediaCrypto.verifyIntegrity(tamperedData, hashHex))
    }

    @Test
    fun `MediaProtocolCodec encodes and decodes MediaDescriptor`() {
        val descriptor = MediaDescriptor(
            mediaId = UUID.randomUUID().toString(),
            type = MediaType.IMAGE,
            mimeType = "image/png",
            fileName = "screenshot.png",
            fileSize = 1048576L,
            encryptedSha256 = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            mediaKeyBase64 = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=",
            totalChunks = 64,
            chunkSize = 16384,
            durationMs = null,
            thumbnailBase64 = "dGh1bWJuYWlsX2RhdGE=",
            waveformBase64 = null,
            width = 1920,
            height = 1080
        )

        val encoded = MediaProtocolCodec.encodeDescriptor(descriptor)
        val decoded = MediaProtocolCodec.decodeDescriptor(encoded)

        assertEquals(descriptor.mediaId, decoded.mediaId)
        assertEquals(descriptor.type, decoded.type)
        assertEquals(descriptor.mimeType, decoded.mimeType)
        assertEquals(descriptor.fileName, decoded.fileName)
        assertEquals(descriptor.fileSize, decoded.fileSize)
        assertEquals(descriptor.encryptedSha256, decoded.encryptedSha256)
        assertEquals(descriptor.mediaKeyBase64, decoded.mediaKeyBase64)
        assertEquals(descriptor.totalChunks, decoded.totalChunks)
        assertEquals(descriptor.chunkSize, decoded.chunkSize)
        assertNull(decoded.durationMs)
        assertEquals(descriptor.thumbnailBase64, decoded.thumbnailBase64)
        assertEquals(1920, decoded.width)
        assertEquals(1080, decoded.height)
    }

    @Test
    fun `MediaProtocolCodec encodes and decodes chunk packets`() {
        val chunk = MediaChunkPayload(
            mediaId = "media_123",
            chunkIndex = 5,
            totalChunks = 20,
            chunkData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        )

        val encodedChunk = MediaProtocolCodec.encodeChunk(chunk)
        val decodedChunk = MediaProtocolCodec.decodeChunk(encodedChunk)

        assertEquals(chunk.mediaId, decodedChunk.mediaId)
        assertEquals(chunk.chunkIndex, decodedChunk.chunkIndex)
        assertEquals(chunk.totalChunks, decodedChunk.totalChunks)
        assertArrayEquals(chunk.chunkData, decodedChunk.chunkData)

        val ack = MediaChunkAck(mediaId = "media_123", chunkIndex = 5)
        val encodedAck = MediaProtocolCodec.encodeChunkAck(ack)
        val decodedAck = MediaProtocolCodec.decodeChunkAck(encodedAck)
        assertEquals(ack, decodedAck)

        val resumeReq = MediaResumeRequest(mediaId = "media_123", missingChunkIndices = listOf(6, 7, 12, 19))
        val encodedResume = MediaProtocolCodec.encodeResumeRequest(resumeReq)
        val decodedResume = MediaProtocolCodec.decodeResumeRequest(encodedResume)
        assertEquals(resumeReq.mediaId, decodedResume.mediaId)
        assertEquals(resumeReq.missingChunkIndices, decodedResume.missingChunkIndices)

        val cancel = MediaCancelPayload(mediaId = "media_123", reason = "Timeout")
        val encodedCancel = MediaProtocolCodec.encodeCancel(cancel)
        val decodedCancel = MediaProtocolCodec.decodeCancel(encodedCancel)
        assertEquals(cancel, decodedCancel)
    }
}
