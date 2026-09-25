package com.torxone.app.chat

import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.media.MediaStatus
import com.torxone.app.media.MediaType

/**
 * Aggregated reaction model for display below bubbles.
 */
data class ReactionSummaryUiModel(
    val emoji: String,
    val count: Int,
    val userReacted: Boolean
)

/**
 * UI representation of an attached media item.
 */
data class MediaUiModel(
    val mediaId: String,
    val type: MediaType,
    val fileName: String,
    val fileSize: Long,
    val localPath: String? = null,
    val thumbnailData: ByteArray? = null,
    val durationMs: Long? = null,
    val waveformData: ByteArray? = null,
    val status: MediaStatus = MediaStatus.COMPLETE,
    val progress: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaUiModel) return false
        return mediaId == other.mediaId &&
                status == other.status &&
                progress == other.progress &&
                localPath == other.localPath
    }

    override fun hashCode(): Int = mediaId.hashCode()
}

/**
 * MessageUiModel — Presentation model for chat message bubbles.
 * Decouples Compose UI from raw database Room entities.
 */
data class MessageUiModel(
    val logicalMessageId: String,
    val conversationId: String,
    val senderId: String,
    val body: String?,
    val direction: MessageDirection,
    val status: DeliveryStatus,
    val createdAt: Long,
    val deliveredAt: Long? = null,
    val readAt: Long? = null,
    val replyToMessageId: String? = null,
    val quotedMessage: QuotedMessageUiModel? = null,
    val isEdited: Boolean = false,
    val editedAt: Long? = null,
    val isDeleted: Boolean = false,
    val reactions: List<ReactionSummaryUiModel> = emptyList(),
    val media: MediaUiModel? = null,
    val senderDisplayName: String? = null,
    val senderAvatarHash: String? = null
)

/**
 * Quoted message snippet for in-bubble replies and composer preview.
 */
data class QuotedMessageUiModel(
    val messageId: String,
    val senderName: String,
    val previewText: String,
    val isUnavailable: Boolean = false
)
