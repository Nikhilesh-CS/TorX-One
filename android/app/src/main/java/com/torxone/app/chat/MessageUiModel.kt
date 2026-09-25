package com.torxone.app.chat

import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.data.entity.MessageDirection

/**
 * Aggregated reaction model for display below bubbles.
 */
data class ReactionSummaryUiModel(
    val emoji: String,
    val count: Int,
    val userReacted: Boolean
)

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
    val reactions: List<ReactionSummaryUiModel> = emptyList()
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
