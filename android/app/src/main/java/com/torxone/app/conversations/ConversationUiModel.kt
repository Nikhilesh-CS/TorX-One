package com.torxone.app.conversations

import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.data.entity.ConversationType

/**
 * UI representation of a conversation item in the list.
 */
data class ConversationUiModel(
    val conversationId: String,
    val title: String,
    val preview: String?,
    val timestamp: Long?,
    val unreadCount: Int,
    val manuallyUnread: Boolean,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isMuted: Boolean,
    val avatarHash: String? = null,
    val isLastMessageOutgoing: Boolean = false,
    val lastMessageStatus: DeliveryStatus? = null,
    val type: ConversationType = ConversationType.DIRECT
) {
    companion object {
        fun from(
            entity: com.torxone.app.data.entity.ConversationEntity,
            lastMessage: com.torxone.app.data.entity.MessageEntity? = null,
            now: Long = System.currentTimeMillis()
        ): ConversationUiModel {
            var isOutgoing = false
            var status: DeliveryStatus? = null

            if (lastMessage != null) {
                isOutgoing = lastMessage.direction == com.torxone.app.data.entity.MessageDirection.OUTGOING
                status = try {
                    DeliveryStatus.valueOf(lastMessage.status)
                } catch (_: Exception) {
                    null
                }
            }

            val preview = if (lastMessage != null && lastMessage.deletedAt != null) {
                "This message was deleted"
            } else {
                entity.lastMessagePreview
            }

            return ConversationUiModel(
                conversationId = entity.conversationId,
                title = entity.title ?: "Contact",
                preview = preview,
                timestamp = entity.lastMessageTime,
                unreadCount = entity.unreadCount,
                manuallyUnread = entity.manuallyUnread,
                isPinned = entity.isPinned,
                isArchived = entity.isArchived,
                isMuted = com.torxone.app.notifications.NotificationPolicy.isConversationMuted(entity.mutedUntil, now),
                avatarHash = entity.avatarHash,
                isLastMessageOutgoing = isOutgoing,
                lastMessageStatus = status,
                type = entity.type
            )
        }
    }
}
