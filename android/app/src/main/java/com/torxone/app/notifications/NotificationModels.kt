package com.torxone.app.notifications

/**
 * Privacy modes controlling how message previews are displayed in system notifications.
 */
enum class NotificationPrivacyMode {
    /** Show sender name and decrypted message text */
    FULL,

    /** Show sender name and generic notice ("New message") */
    SENDER_ONLY,

    /** Completely hidden content ("TorX One" / "New message") */
    HIDDEN
}

data class NotificationDisplayContent(
    val title: String,
    val text: String
)

/**
 * Pure policy rules for TorX notification decisions.
 */
object NotificationPolicy {

    const val CHANNEL_MESSAGES = "torx_messages"
    const val CHANNEL_SILENT = "torx_silent_messages"

    /**
     * Determines whether an incoming message in conversationId should generate an Android notification.
     *
     * Rules:
     * - If app is in foreground and conversation is currently active on screen -> DO NOT NOTIFY
     * - If app is in foreground and a DIFFERENT conversation is active -> NOTIFY
     * - If app is in background -> NOTIFY
     */
    fun shouldNotify(
        conversationId: String,
        isAppForeground: Boolean,
        activeConversationId: String?
    ): Boolean {
        if (isAppForeground && activeConversationId == conversationId) {
            return false
        }
        return true
    }

    /**
     * Determine which notification channel to use based on mute status.
     */
    fun getChannelId(isMuted: Boolean): String {
        return if (isMuted) CHANNEL_SILENT else CHANNEL_MESSAGES
    }

    /**
     * Checks if a conversation is currently muted.
     */
    fun isConversationMuted(mutedUntil: Long?, now: Long = System.currentTimeMillis()): Boolean {
        return mutedUntil != null && mutedUntil > now
    }

    /**
     * Formats notification title and text according to privacy settings.
     */
    fun formatContent(
        privacyMode: NotificationPrivacyMode,
        senderName: String,
        messageText: String
    ): NotificationDisplayContent {
        return when (privacyMode) {
            NotificationPrivacyMode.FULL -> NotificationDisplayContent(
                title = senderName,
                text = messageText
            )
            NotificationPrivacyMode.SENDER_ONLY -> NotificationDisplayContent(
                title = senderName,
                text = "New message"
            )
            NotificationPrivacyMode.HIDDEN -> NotificationDisplayContent(
                title = "TorX One",
                text = "New message"
            )
        }
    }

    /**
     * Generates a stable integer notification ID from conversationId.
     */
    fun getNotificationId(conversationId: String): Int {
        return conversationId.hashCode() and 0x7FFFFFFF
    }
}
