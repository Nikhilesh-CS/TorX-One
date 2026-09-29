package com.torxone.app.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Deterministic binary serialization for media descriptors and chunk control packets.
 */
object MediaProtocolCodec {
    const val MAX_CHUNK_BYTES = DedicatedMediaFrameCodec.MAX_PLAINTEXT_CHUNK_BYTES
    const val MAX_CHUNK_COUNT = 65_536
    const val MAX_MEDIA_BYTES = 1L shl 30
    private const val MAX_CONTROL_ID_LENGTH = 128

    private const val DESCRIPTOR_MAGIC = 0x54584D44 // "TXMD"
    private const val CHUNK_MAGIC = 0x54584D43      // "TXMC"
    private const val ACK_MAGIC = 0x54584D41        // "TXMA"
    private const val RESUME_MAGIC = 0x54584D52     // "TXMR"
    private const val CANCEL_MAGIC = 0x54584D58     // "TXMX"
    private const val COMPLETE_MAGIC = 0x54584350   // "TXCP"
    private const val ACCEPT_MAGIC = 0x54584D59     // "TXMY"

    // ─── MediaDescriptor ──────────────────────────────────────────────

    fun encodeDescriptor(d: MediaDescriptor): ByteArray {
        require(d.mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(d.fileSize in 1..MAX_MEDIA_BYTES && d.totalChunks in 1..MAX_CHUNK_COUNT)
        require(d.chunkSize in 1..MAX_CHUNK_BYTES)
        require(d.totalChunks.toLong() == (d.fileSize + 28L + d.chunkSize - 1) / d.chunkSize)
        require(d.encryptedSha256.matches(Regex("[A-Fa-f0-9]{64}")))
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(DESCRIPTOR_MAGIC)
        dos.writeUTF(d.mediaId)
        dos.writeUTF(d.type.name)
        dos.writeUTF(d.mimeType)
        dos.writeUTF(d.fileName)
        dos.writeLong(d.fileSize)
        dos.writeUTF(d.encryptedSha256)
        dos.writeUTF(d.mediaKeyBase64)
        dos.writeInt(d.totalChunks)
        dos.writeInt(d.chunkSize)
        dos.writeLong(d.durationMs ?: -1L)
        dos.writeUTF(d.thumbnailBase64 ?: "")
        dos.writeUTF(d.waveformBase64 ?: "")
        dos.writeInt(d.width ?: -1)
        dos.writeInt(d.height ?: -1)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeDescriptor(bytes: ByteArray): MediaDescriptor {
        require(bytes.size <= 48 * 1024) { "Media descriptor too large" }
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == DESCRIPTOR_MAGIC) { "Invalid MediaDescriptor magic header" }

        val mediaId = dis.readUTF()
        val type = MediaType.valueOf(dis.readUTF())
        val mimeType = dis.readUTF()
        val fileName = dis.readUTF()
        val fileSize = dis.readLong()
        val encryptedSha256 = dis.readUTF()
        val mediaKeyBase64 = dis.readUTF()
        val totalChunks = dis.readInt()
        val chunkSize = dis.readInt()
        val dur = dis.readLong()
        val durationMs = if (dur < 0) null else dur
        val thumb = dis.readUTF()
        val thumbnailBase64 = if (thumb.isEmpty()) null else thumb
        val wave = dis.readUTF()
        val waveformBase64 = if (wave.isEmpty()) null else wave
        val w = dis.readInt()
        val width = if (w < 0) null else w
        val h = dis.readInt()
        val height = if (h < 0) null else h

        require(dis.available() == 0) { "Trailing bytes in media descriptor" }
        require(mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid media ID" }
        require(fileSize in 1..MAX_MEDIA_BYTES) { "Invalid media size" }
        require(totalChunks in 1..MAX_CHUNK_COUNT && chunkSize in 1..MAX_CHUNK_BYTES) { "Invalid media chunk geometry" }
        require(totalChunks.toLong() == (fileSize + 28L + chunkSize - 1) / chunkSize) { "Chunk count does not match media size" }
        require(encryptedSha256.matches(Regex("[A-Fa-f0-9]{64}"))) { "Invalid media hash" }
        require(java.util.Base64.getDecoder().decode(mediaKeyBase64).size == 32) { "Invalid media key length" }
        require(fileName.isNotBlank() && fileName.length <= 255 && '/' !in fileName && '\\' !in fileName && fileName != "." && fileName != "..") { "Invalid media file name" }
        require(mimeType.length <= 128) { "Invalid MIME type" }
        return MediaDescriptor(
            mediaId = mediaId,
            type = type,
            mimeType = mimeType,
            fileName = fileName,
            fileSize = fileSize,
            encryptedSha256 = encryptedSha256,
            mediaKeyBase64 = mediaKeyBase64,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            durationMs = durationMs,
            thumbnailBase64 = thumbnailBase64,
            waveformBase64 = waveformBase64,
            width = width,
            height = height
        )
    }

    // ─── MediaChunkPayload ────────────────────────────────────────────

    fun encodeChunk(chunk: MediaChunkPayload): ByteArray {
        require(chunk.mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(chunk.totalChunks in 1..MAX_CHUNK_COUNT && chunk.chunkIndex in 0 until chunk.totalChunks)
        require(chunk.chunkData.size in 1..MAX_CHUNK_BYTES)
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(CHUNK_MAGIC)
        dos.writeUTF(chunk.mediaId)
        dos.writeInt(chunk.chunkIndex)
        dos.writeInt(chunk.totalChunks)
        dos.writeInt(chunk.chunkData.size)
        dos.write(chunk.chunkData)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeChunk(bytes: ByteArray): MediaChunkPayload {
        require(bytes.size <= MAX_CHUNK_BYTES + 512) { "Media chunk too large" }
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == CHUNK_MAGIC) { "Invalid MediaChunk magic header" }

        val mediaId = dis.readUTF()
        val chunkIndex = dis.readInt()
        val totalChunks = dis.readInt()
        val dataSize = dis.readInt()
        require(mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid media ID" }
        require(totalChunks in 1..MAX_CHUNK_COUNT && chunkIndex in 0 until totalChunks) { "Invalid chunk index/count" }
        require(dataSize in 1..MAX_CHUNK_BYTES && dataSize == dis.available()) { "Invalid chunk data size" }
        val chunkData = ByteArray(dataSize)
        dis.readFully(chunkData)
        require(dis.available() == 0) { "Trailing bytes in media chunk" }

        return MediaChunkPayload(
            mediaId = mediaId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            chunkData = chunkData
        )
    }

    // ─── MediaChunkAck ────────────────────────────────────────────────

    fun encodeAccept(accept: MediaAcceptPayload): ByteArray {
        require(accept.mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(ACCEPT_MAGIC)
                data.writeUTF(accept.mediaId)
            }
            output.toByteArray()
        }
    }

    fun decodeAccept(bytes: ByteArray): MediaAcceptPayload {
        require(bytes.size <= 2048) { "Media accept payload too large" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == ACCEPT_MAGIC) { "Invalid MediaAccept magic header" }
            val mediaId = data.readUTF()
            require(data.available() == 0 && mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            MediaAcceptPayload(mediaId)
        }
    }

    // ─── MediaChunkAck ────────────────────────────────────────────────

    fun encodeChunkAck(ack: MediaChunkAck): ByteArray {
        require(ack.mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(ack.chunkIndex in 0 until MAX_CHUNK_COUNT)
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(ACK_MAGIC)
        dos.writeUTF(ack.mediaId)
        dos.writeInt(ack.chunkIndex)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeChunkAck(bytes: ByteArray): MediaChunkAck {
        require(bytes.size <= 2048) { "Media control payload too large" }
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == ACK_MAGIC) { "Invalid MediaChunkAck magic header" }
        val value = MediaChunkAck(
            mediaId = dis.readUTF(),
            chunkIndex = dis.readInt()
        )
        require(dis.available() == 0) { "Trailing media control bytes" }
        require(value.mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(value.chunkIndex in 0 until MAX_CHUNK_COUNT)
        return value
    }

    // ─── MediaResumeRequest ───────────────────────────────────────────

    fun encodeResumeRequest(req: MediaResumeRequest): ByteArray {
        require(req.mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(req.missingChunkIndices.size <= MAX_CHUNK_COUNT && req.missingChunkIndices.all { it >= 0 })
        require(req.missingChunkIndices.distinct().size == req.missingChunkIndices.size)
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(RESUME_MAGIC)
        dos.writeUTF(req.mediaId)
        dos.writeInt(req.missingChunkIndices.size)
        for (idx in req.missingChunkIndices) {
            dos.writeInt(idx)
        }
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeResumeRequest(bytes: ByteArray): MediaResumeRequest {
        require(bytes.size <= MAX_CHUNK_COUNT * 4 + 256) { "Resume request too large" }
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == RESUME_MAGIC) { "Invalid MediaResumeRequest magic header" }
        val mediaId = dis.readUTF()
        val count = dis.readInt()
        require(count in 0..MAX_CHUNK_COUNT && count <= dis.available() / 4) { "Invalid resume index count" }
        val indices = ArrayList<Int>(count)
        for (i in 0 until count) {
            indices.add(dis.readInt())
        }
        require(dis.available() == 0 && mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid resume request" }
        require(indices.all { it >= 0 } && indices.distinct().size == indices.size) { "Invalid resume indices" }
        return MediaResumeRequest(
            mediaId = mediaId,
            missingChunkIndices = indices
        )
    }

    // ─── MediaCancelPayload ───────────────────────────────────────────

    fun encodeCancel(cancel: MediaCancelPayload): ByteArray {
        require(cancel.mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(cancel.reason.length <= 512)
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(CANCEL_MAGIC)
        dos.writeUTF(cancel.mediaId)
        dos.writeUTF(cancel.reason)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeCancel(bytes: ByteArray): MediaCancelPayload {
        require(bytes.size <= 2048) { "Media control payload too large" }
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == CANCEL_MAGIC) { "Invalid MediaCancel magic header" }
        val value = MediaCancelPayload(
            mediaId = dis.readUTF(),
            reason = dis.readUTF()
        )
        require(dis.available() == 0) { "Trailing media control bytes" }
        require(value.mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(value.reason.length <= 512)
        return value
    }

    // ─── MediaCompletePayload ─────────────────────────────────────────

    fun encodeComplete(complete: MediaCompletePayload): ByteArray {
        require(complete.mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(complete.verifiedSha256.matches(Regex("[A-Fa-f0-9]{64}")))
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(COMPLETE_MAGIC)
        dos.writeUTF(complete.mediaId)
        dos.writeUTF(complete.verifiedSha256)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeComplete(bytes: ByteArray): MediaCompletePayload {
        require(bytes.size <= 2048) { "Media control payload too large" }
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == COMPLETE_MAGIC) { "Invalid MediaComplete magic header" }
        val value = MediaCompletePayload(
            mediaId = dis.readUTF(),
            verifiedSha256 = dis.readUTF()
        )
        require(dis.available() == 0) { "Trailing media control bytes" }
        require(value.mediaId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(value.verifiedSha256.matches(Regex("[A-Fa-f0-9]{64}")))
        return value
    }
}
