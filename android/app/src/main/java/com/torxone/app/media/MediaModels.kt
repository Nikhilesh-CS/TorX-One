package com.torxone.app.media

import java.util.UUID

/**
 * Supported media attachment types.
 */
enum class MediaType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    VOICE_NOTE
}

/**
 * Lifecycle states for media attachments in the UI and database.
 */
enum class MediaStatus {
    PREPARING,
    QUEUED,
    UPLOADING,
    SENT,
    DELIVERED,
    DOWNLOADING,
    COMPLETE,
    FAILED,
    CANCELLED
}

/**
 * Transfer direction.
 */
enum class TransferDirection {
    UPLOAD,
    DOWNLOAD
}

/**
 * State of an active or paused chunked transfer.
 */
enum class TransferStatus {
    IDLE,
    ACTIVE,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * MediaDescriptor — Sent inside the initial SecureEnvelope via Double Ratchet.
 *
 * Contains metadata and the symmetric mediaKey, but NEVER the actual media payload.
 * Size is typically ~500 bytes - 2 KB (fits comfortably inside MAX_SECURE_PAYLOAD_BYTES).
 */
data class MediaDescriptor(
    val mediaId: String = UUID.randomUUID().toString(),
    val type: MediaType,
    val mimeType: String,
    val fileName: String,
    val fileSize: Long,
    val encryptedSha256: String,
    val mediaKeyBase64: String, // 32-byte AES-256 key encoded as Base64
    val totalChunks: Int,
    val chunkSize: Int,
    val durationMs: Long? = null,
    val thumbnailBase64: String? = null,
    val waveformBase64: String? = null,
    val width: Int? = null,
    val height: Int? = null
)

/**
 * Single chunk payload transferred across the network.
 */
data class MediaChunkPayload(
    val mediaId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaChunkPayload) return false
        return mediaId == other.mediaId && chunkIndex == other.chunkIndex
    }

    override fun hashCode(): Int = 31 * mediaId.hashCode() + chunkIndex
}

/**
 * Acknowledgment for received chunk.
 */
data class MediaChunkAck(
    val mediaId: String,
    val chunkIndex: Int
)

/**
 * Request to resume transfer by asking only for missing chunks.
 */
data class MediaResumeRequest(
    val mediaId: String,
    val missingChunkIndices: List<Int>
)

/**
 * Cancellation signal for a media transfer.
 */
data class MediaCancelPayload(
    val mediaId: String,
    val reason: String = "User cancelled"
)
