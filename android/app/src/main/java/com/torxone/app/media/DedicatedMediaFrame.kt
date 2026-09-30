package com.torxone.app.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.torxone.app.transport.TransportDestination
import com.torxone.app.transport.TransportResult
import com.torxone.app.transport.TransportRouter

data class DedicatedMediaFrame(
    val mediaId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val encryptedChunk: ByteArray
)

interface DedicatedMediaTransport {
    suspend fun send(destination: TransportDestination, frame: DedicatedMediaFrame): TransportResult
}

class RoutedDedicatedMediaTransport(
    private val transportRouter: TransportRouter
) : DedicatedMediaTransport {
    override suspend fun send(destination: TransportDestination, frame: DedicatedMediaFrame): TransportResult =
        transportRouter.send(destination, DedicatedMediaFrameCodec.encode(frame))
}

/** Use an authenticated online RTC lane when available, otherwise the durable Tor path. */
class HybridDedicatedMediaTransport(
    private val rtcSend: suspend (TransportDestination, ByteArray) -> Boolean,
    private val fallback: DedicatedMediaTransport
) : DedicatedMediaTransport {
    override suspend fun send(destination: TransportDestination, frame: DedicatedMediaFrame): TransportResult {
        val rtcAccepted = try {
            rtcSend(destination, DedicatedMediaFrameCodec.encode(frame))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            false
        }
        if (rtcAccepted) {
            return TransportResult.Accepted(com.torxone.app.transport.TransportType.WEBRTC)
        }
        return fallback.send(destination, frame)
    }
}

/** Wire format for media payload frames that bypasses the chat outbox and ratchet. */
object DedicatedMediaFrameCodec {
    const val MAGIC = 0x54584D53 // TXMS
    const val MAX_PLAINTEXT_CHUNK_BYTES = 256 * 1024
    private const val GCM_OVERHEAD_BYTES = 12 + 16
    private const val MAX_FRAME_BYTES = MAX_PLAINTEXT_CHUNK_BYTES + GCM_OVERHEAD_BYTES + 256

    fun isDedicatedMediaFrame(bytes: ByteArray): Boolean =
        bytes.size >= 4 && ByteBuffer.wrap(bytes, 0, 4).int == MAGIC

    fun encode(frame: DedicatedMediaFrame): ByteArray {
        require(frame.mediaId.matches(Regex("[A-Fa-f0-9-]{36}")))
        require(frame.totalChunks in 1..MediaProtocolCodec.MAX_CHUNK_COUNT)
        require(frame.chunkIndex in 0 until frame.totalChunks)
        require(frame.encryptedChunk.size in 29..(MAX_PLAINTEXT_CHUNK_BYTES + GCM_OVERHEAD_BYTES))
        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeUTF(frame.mediaId)
                data.writeInt(frame.chunkIndex)
                data.writeInt(frame.totalChunks)
                data.writeInt(frame.encryptedChunk.size)
                data.write(frame.encryptedChunk)
            }
            output.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): DedicatedMediaFrame {
        require(bytes.size in 1..MAX_FRAME_BYTES) { "Dedicated media frame too large" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid dedicated media frame magic" }
            val mediaId = data.readUTF()
            val chunkIndex = data.readInt()
            val totalChunks = data.readInt()
            val encryptedSize = data.readInt()
            require(mediaId.matches(Regex("[A-Fa-f0-9-]{36}"))) { "Invalid media ID" }
            require(totalChunks in 1..MediaProtocolCodec.MAX_CHUNK_COUNT && chunkIndex in 0 until totalChunks)
            require(encryptedSize in 29..(MAX_PLAINTEXT_CHUNK_BYTES + GCM_OVERHEAD_BYTES))
            require(encryptedSize == data.available()) { "Invalid dedicated media frame length" }
            val encrypted = ByteArray(encryptedSize)
            data.readFully(encrypted)
            require(data.available() == 0) { "Trailing dedicated media frame bytes" }
            DedicatedMediaFrame(mediaId, chunkIndex, totalChunks, encrypted)
        }
    }
}

/** Per-chunk AEAD allowing authenticated random access, retry, and resume. */
object DedicatedMediaChunkCrypto {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun encrypt(
        transferKey: ByteArray,
        mediaId: String,
        relationshipId: String,
        chunkIndex: Int,
        totalChunks: Int,
        plaintext: ByteArray
    ): ByteArray {
        require(plaintext.size in 1..DedicatedMediaFrameCodec.MAX_PLAINTEXT_CHUNK_BYTES)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = newCipher(Cipher.ENCRYPT_MODE, transferKey, mediaId, relationshipId, chunkIndex, totalChunks, nonce)
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(
        transferKey: ByteArray,
        mediaId: String,
        relationshipId: String,
        chunkIndex: Int,
        totalChunks: Int,
        encrypted: ByteArray
    ): ByteArray {
        require(encrypted.size > NONCE_BYTES + TAG_BITS / 8)
        val nonce = encrypted.copyOfRange(0, NONCE_BYTES)
        val cipher = newCipher(Cipher.DECRYPT_MODE, transferKey, mediaId, relationshipId, chunkIndex, totalChunks, nonce)
        return cipher.doFinal(encrypted, NONCE_BYTES, encrypted.size - NONCE_BYTES)
    }

    private fun newCipher(
        mode: Int,
        transferKey: ByteArray,
        mediaId: String,
        relationshipId: String,
        chunkIndex: Int,
        totalChunks: Int,
        nonce: ByteArray
    ): Cipher {
        require(transferKey.size == 32) { "Media transfer key must be 32 bytes" }
        require(mediaId.matches(Regex("[A-Fa-f0-9-]{36}")))
        require(relationshipId.isNotBlank() && relationshipId.length <= 256)
        require(totalChunks in 1..MediaProtocolCodec.MAX_CHUNK_COUNT && chunkIndex in 0 until totalChunks)
        val chunkKey = deriveChunkKey(transferKey, mediaId, chunkIndex)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(chunkKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad(mediaId, relationshipId, chunkIndex, totalChunks))
        }
    }

    private fun deriveChunkKey(transferKey: ByteArray, mediaId: String, chunkIndex: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(transferKey, "HmacSHA256"))
        mac.update("TorX-Media-Chunk-V1".toByteArray(Charsets.UTF_8))
        mac.update(mediaId.toByteArray(Charsets.UTF_8))
        mac.update(ByteBuffer.allocate(4).putInt(chunkIndex).array())
        return mac.doFinal()
    }

    private fun aad(mediaId: String, relationshipId: String, chunkIndex: Int, totalChunks: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeUTF("TorX-Dedicated-Media-V1")
                data.writeUTF(mediaId)
                data.writeUTF(relationshipId)
                data.writeInt(chunkIndex)
                data.writeInt(totalChunks)
            }
            output.toByteArray()
        }
}
