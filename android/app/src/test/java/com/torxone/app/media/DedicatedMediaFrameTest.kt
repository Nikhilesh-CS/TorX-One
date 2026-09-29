package com.torxone.app.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.Base64
import kotlinx.coroutines.runBlocking
import com.torxone.app.incoming.IncomingTransportHub
import com.torxone.app.transport.TransportType

class DedicatedMediaFrameTest {
    @Test
    fun `frame codec round trips authenticated chunk`() {
        val mediaId = UUID.randomUUID().toString()
        val key = MediaCrypto.generateMediaKey()
        val plaintext = ByteArray(256 * 1024) { (it * 31).toByte() }
        val encrypted = DedicatedMediaChunkCrypto.encrypt(key, mediaId, "relationship-1", 3, 8, plaintext)
        val encoded = DedicatedMediaFrameCodec.encode(DedicatedMediaFrame(mediaId, 3, 8, encrypted))

        assertTrue(DedicatedMediaFrameCodec.isDedicatedMediaFrame(encoded))
        val decoded = DedicatedMediaFrameCodec.decode(encoded)
        assertEquals(mediaId, decoded.mediaId)
        assertEquals(3, decoded.chunkIndex)
        assertArrayEquals(
            plaintext,
            DedicatedMediaChunkCrypto.decrypt(key, mediaId, "relationship-1", 3, 8, decoded.encryptedChunk)
        )
    }

    @Test(expected = SecurityException::class)
    fun `chunk authentication rejects modified ciphertext`() {
        val mediaId = UUID.randomUUID().toString()
        val key = MediaCrypto.generateMediaKey()
        val encrypted = DedicatedMediaChunkCrypto.encrypt(key, mediaId, "relationship-1", 0, 1, byteArrayOf(1, 2, 3))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        try {
            DedicatedMediaChunkCrypto.decrypt(key, mediaId, "relationship-1", 0, 1, encrypted)
        } catch (error: javax.crypto.AEADBadTagException) {
            throw SecurityException("Chunk authentication failed", error)
        }
    }

    @Test
    fun `chunk binding rejects another transfer position`() {
        val mediaId = UUID.randomUUID().toString()
        val key = MediaCrypto.generateMediaKey()
        val encrypted = DedicatedMediaChunkCrypto.encrypt(key, mediaId, "relationship-1", 0, 2, byteArrayOf(4, 5, 6))
        val rejected = runCatching {
            DedicatedMediaChunkCrypto.decrypt(key, mediaId, "relationship-1", 1, 2, encrypted)
        }.isFailure
        assertTrue(rejected)
        assertFalse(DedicatedMediaFrameCodec.isDedicatedMediaFrame(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `incoming hub separates dedicated frames from ratchet dispatcher`() = runBlocking {
        val mediaId = UUID.randomUUID().toString()
        val key = MediaCrypto.generateMediaKey()
        val encrypted = DedicatedMediaChunkCrypto.encrypt(key, mediaId, "relationship-1", 0, 1, byteArrayOf(7))
        val encoded = DedicatedMediaFrameCodec.encode(DedicatedMediaFrame(mediaId, 0, 1, encrypted))
        var handled = false
        val hub = IncomingTransportHub(dedicatedMediaFrameHandler = { bytes, transport ->
            handled = DedicatedMediaFrameCodec.decode(bytes).mediaId == mediaId && transport == TransportType.FAKE
            true
        })

        assertTrue(hub.onRawFrameReceived(encoded, TransportType.FAKE))
        assertTrue(handled)
    }

    @Test
    fun `one gigabyte descriptor fits dedicated chunk geometry`() {
        val fileBytes = MediaProtocolCodec.MAX_MEDIA_BYTES
        val encryptedBytes = fileBytes + 28L
        val chunkSize = DedicatedMediaFrameCodec.MAX_PLAINTEXT_CHUNK_BYTES
        val chunks = ((encryptedBytes + chunkSize - 1) / chunkSize).toInt()
        val descriptor = MediaDescriptor(
            mediaId = UUID.randomUUID().toString(),
            type = MediaType.DOCUMENT,
            mimeType = "application/octet-stream",
            fileName = "one-gigabyte.bin",
            fileSize = fileBytes,
            encryptedSha256 = "00".repeat(32),
            mediaKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
            totalChunks = chunks,
            chunkSize = chunkSize
        )

        assertEquals(4097, chunks)
        assertEquals(descriptor, MediaProtocolCodec.decodeDescriptor(MediaProtocolCodec.encodeDescriptor(descriptor)))
    }
}
