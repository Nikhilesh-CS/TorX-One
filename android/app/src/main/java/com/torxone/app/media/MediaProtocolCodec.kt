package com.torxone.app.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Deterministic binary serialization for media descriptors and chunk control packets.
 */
object MediaProtocolCodec {

    private const val DESCRIPTOR_MAGIC = 0x54584D44 // "TXMD"
    private const val CHUNK_MAGIC = 0x54584D43      // "TXMC"
    private const val ACK_MAGIC = 0x54584D41        // "TXMA"
    private const val RESUME_MAGIC = 0x54584D52     // "TXMR"
    private const val CANCEL_MAGIC = 0x54584D58     // "TXMX"
    private const val COMPLETE_MAGIC = 0x54584350   // "TXCP"

    // ─── MediaDescriptor ──────────────────────────────────────────────

    fun encodeDescriptor(d: MediaDescriptor): ByteArray {
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
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == CHUNK_MAGIC) { "Invalid MediaChunk magic header" }

        val mediaId = dis.readUTF()
        val chunkIndex = dis.readInt()
        val totalChunks = dis.readInt()
        val dataSize = dis.readInt()
        val chunkData = ByteArray(dataSize)
        dis.readFully(chunkData)

        return MediaChunkPayload(
            mediaId = mediaId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            chunkData = chunkData
        )
    }

    // ─── MediaChunkAck ────────────────────────────────────────────────

    fun encodeChunkAck(ack: MediaChunkAck): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(ACK_MAGIC)
        dos.writeUTF(ack.mediaId)
        dos.writeInt(ack.chunkIndex)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeChunkAck(bytes: ByteArray): MediaChunkAck {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == ACK_MAGIC) { "Invalid MediaChunkAck magic header" }
        return MediaChunkAck(
            mediaId = dis.readUTF(),
            chunkIndex = dis.readInt()
        )
    }

    // ─── MediaResumeRequest ───────────────────────────────────────────

    fun encodeResumeRequest(req: MediaResumeRequest): ByteArray {
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
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == RESUME_MAGIC) { "Invalid MediaResumeRequest magic header" }
        val mediaId = dis.readUTF()
        val count = dis.readInt()
        val indices = ArrayList<Int>(count)
        for (i in 0 until count) {
            indices.add(dis.readInt())
        }
        return MediaResumeRequest(
            mediaId = mediaId,
            missingChunkIndices = indices
        )
    }

    // ─── MediaCancelPayload ───────────────────────────────────────────

    fun encodeCancel(cancel: MediaCancelPayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(CANCEL_MAGIC)
        dos.writeUTF(cancel.mediaId)
        dos.writeUTF(cancel.reason)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeCancel(bytes: ByteArray): MediaCancelPayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == CANCEL_MAGIC) { "Invalid MediaCancel magic header" }
        return MediaCancelPayload(
            mediaId = dis.readUTF(),
            reason = dis.readUTF()
        )
    }

    // ─── MediaCompletePayload ─────────────────────────────────────────

    fun encodeComplete(complete: MediaCompletePayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(COMPLETE_MAGIC)
        dos.writeUTF(complete.mediaId)
        dos.writeUTF(complete.verifiedSha256)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeComplete(bytes: ByteArray): MediaCompletePayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == COMPLETE_MAGIC) { "Invalid MediaComplete magic header" }
        return MediaCompletePayload(
            mediaId = dis.readUTF(),
            verifiedSha256 = dis.readUTF()
        )
    }
}
