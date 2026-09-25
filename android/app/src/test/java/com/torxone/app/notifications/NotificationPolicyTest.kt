package com.torxone.app.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {

    @Test
    fun testForegroundActiveChat_suppressesNotification() {
        val convId = "conv-active-123"

        // When conversation is currently open and app is in foreground -> suppress
        val shouldNotify = NotificationPolicy.shouldNotify(
            conversationId = convId,
            isAppForeground = true,
            activeConversationId = convId
        )
        assertFalse(shouldNotify)
    }

    @Test
    fun testForegroundDifferentChat_triggersNotification() {
        val incomingConvId = "conv-incoming"
        val activeConvId = "conv-currently-viewing"

        // User is chatting with someone else in foreground -> notify
        val shouldNotify = NotificationPolicy.shouldNotify(
            conversationId = incomingConvId,
            isAppForeground = true,
            activeConversationId = activeConvId
        )
        assertTrue(shouldNotify)
    }

    @Test
    fun testForegroundNoActiveChat_triggersNotification() {
        val incomingConvId = "conv-incoming"

        // User is on conversation list or settings in foreground -> notify
        val shouldNotify = NotificationPolicy.shouldNotify(
            conversationId = incomingConvId,
            isAppForeground = true,
            activeConversationId = null
        )
        assertTrue(shouldNotify)
    }

    @Test
    fun testBackground_alwaysTriggersNotificationEvenIfPreviouslyActive() {
        val convId = "conv-was-active"

        // App backgrounded (home screen or locked) -> must notify
        val shouldNotify = NotificationPolicy.shouldNotify(
            conversationId = convId,
            isAppForeground = false,
            activeConversationId = convId
        )
        assertTrue(shouldNotify)
    }

    @Test
    fun testChannelSelection_unmutedVsMuted() {
        val now = System.currentTimeMillis()

        // 1. Unmuted chat
        assertFalse(NotificationPolicy.isConversationMuted(null))
        assertEquals(NotificationPolicy.CHANNEL_MESSAGES, NotificationPolicy.getChannelId(false))

        // 2. Active mute (muted for next 2 hours)
        val futureMute = now + 7200_000L
        assertTrue(NotificationPolicy.isConversationMuted(futureMute))
        assertEquals(NotificationPolicy.CHANNEL_SILENT, NotificationPolicy.getChannelId(true))

        // 3. Expired mute (muted 5 minutes ago)
        val pastMute = now - 300_000L
        assertFalse(NotificationPolicy.isConversationMuted(pastMute))
        assertEquals(NotificationPolicy.CHANNEL_MESSAGES, NotificationPolicy.getChannelId(false))
    }

    @Test
    fun testPrivacyModeFormatting_full() {
        val content = NotificationPolicy.formatContent(
            privacyMode = NotificationPrivacyMode.FULL,
            senderName = "Alice",
            messageText = "Hey bro, let's meet up!"
        )
        assertEquals("Alice", content.title)
        assertEquals("Hey bro, let's meet up!", content.text)
    }

    @Test
    fun testPrivacyModeFormatting_senderOnly() {
        val content = NotificationPolicy.formatContent(
            privacyMode = NotificationPrivacyMode.SENDER_ONLY,
            senderName = "Alice",
            messageText = "Top secret message"
        )
        assertEquals("Alice", content.title)
        assertEquals("New message", content.text)
    }

    @Test
    fun testPrivacyModeFormatting_hidden() {
        val content = NotificationPolicy.formatContent(
            privacyMode = NotificationPrivacyMode.HIDDEN,
            senderName = "Alice",
            messageText = "Top secret message"
        )
        assertEquals("TorX One", content.title)
        assertEquals("New message", content.text)
    }

    @Test
    fun testTombstoneContentFormatting() {
        val content = NotificationPolicy.formatContent(
            privacyMode = NotificationPrivacyMode.FULL,
            senderName = "Bob",
            messageText = "This message was deleted"
        )
        assertEquals("Bob", content.title)
        assertEquals("This message was deleted", content.text)
    }

    @Test
    fun testNotificationId_deterministicAndDistinct() {
        val id1 = NotificationPolicy.getNotificationId("conv-alice")
        val id2 = NotificationPolicy.getNotificationId("conv-alice")
        val id3 = NotificationPolicy.getNotificationId("conv-bob")

        assertEquals(id1, id2)
        assertNotEquals(id1, id3)
        assertTrue(id1 >= 0)
        assertTrue(id3 >= 0)
    }
}
