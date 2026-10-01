package com.torxone.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.chat.MessageUiModel
import com.torxone.app.data.entity.ContactEntity
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.ui.screens.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent(content)
        compose.waitUntil(10_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }

    @Test fun savedLinkMetadataIsSearchableWithoutMessageBody() {
        val links = listOf(
            com.torxone.app.data.entity.MessageLinkEntity("first", "chat", "https://example.com/docs", "example.com", 1),
            com.torxone.app.data.entity.MessageLinkEntity("second", "chat", "https://openai.com/docs", "openai.com", 2))
        show { MaterialTheme { SharedMediaScreen(emptyList(), emptyList(), cachedLinks = links, onBack = {}) } }
        compose.onNodeWithText("Links").performClick()
        compose.onNodeWithText("Search saved links").performTextInput("openai")
        compose.onNodeWithText("https://openai.com/docs").assertIsDisplayed()
        compose.onNodeWithText("https://example.com/docs").assertDoesNotExist()
    }

    @Test fun chatAppearanceControlsSaveActualLocalSelection() {
        var saved: com.torxone.app.data.entity.ConversationAppearanceEntity? = null
        show { MaterialTheme { com.torxone.app.ui.components.ConversationAppearanceDialog(
            com.torxone.app.data.entity.ConversationAppearanceEntity("chat"), onSave = { saved = it }, onDismiss = {}) } }
        compose.onNodeWithText("Ocean").performClick()
        compose.onNodeWithText("Cool").performClick()
        compose.onNodeWithText("Square").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(com.torxone.app.data.entity.ConversationAppearanceEntity("chat", "OCEAN", "COOL", "SQUARE"), saved) }
    }

    @Test fun incomingMessageFollowsVisibleChatBottom() {
        fun message(index: Int) = MessageUiModel("id-$index", "chat", "peer", "message-$index",
            MessageDirection.INCOMING, DeliveryStatus.DELIVERED, index.toLong())
        val messages = mutableStateOf((0..39).map(::message))
        show { MaterialTheme { ChatScreen("Alice", messages = messages.value, composerText = "") } }
        compose.onNodeWithText("message-39").assertIsDisplayed()
        compose.runOnIdle { messages.value = messages.value + message(40) }
        compose.onNodeWithText("message-40").assertIsDisplayed()
    }
    @Test fun multiSelectStarsBothSelectedMessages() {
        val messages = (0..2).map { MessageUiModel("id-$it", "chat", "peer", "select-$it",
            MessageDirection.INCOMING, DeliveryStatus.DELIVERED, it.toLong()) }
        var selected = emptySet<String>()
        show { MaterialTheme { ChatScreen("Alice", messages = messages, composerText = "",
            onSetStarred = { ids, enabled -> if (enabled) selected = ids }) } }
        compose.onNodeWithText("select-0").performTouchInput { longClick() }
        compose.onNodeWithText("select-1").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Selection actions").performClick()
        compose.onNodeWithText("Star").performClick()
        compose.runOnIdle { assertEquals(setOf("id-0", "id-1"), selected) }
    }
    @Test fun searchNavigationJumpsToRequestedOlderMessage() {
        val messages = (0..39).map { MessageUiModel("id-$it", "chat", "peer", "jump-$it",
            MessageDirection.INCOMING, DeliveryStatus.DELIVERED, it.toLong()) }
        show { MaterialTheme { ChatScreen("Alice", messages = messages, composerText = "", initialMessageId = "id-3") } }
        compose.onNodeWithText("jump-3").assertIsDisplayed()
    }
    @Test fun mediaBrowserLinksTabShowsStoredMessageUrl() {
        val message = com.torxone.app.data.entity.MessageEntity("link", "chat", "peer", "TEXT", "See https://example.com/docs",
            MessageDirection.INCOMING, "DELIVERED")
        show { MaterialTheme { SharedMediaScreen(emptyList(), listOf(message), onBack = {}) } }
        compose.onNodeWithText("Links").performClick()
        compose.onNodeWithText("https://example.com/docs").assertIsDisplayed()
    }
    @Test fun outgoingMessageFollowsBottomWhileReadingOlderMessages() {
        fun message(index: Int, direction: MessageDirection = MessageDirection.INCOMING) = MessageUiModel("id-$index", "chat", "peer", "message-$index", direction, DeliveryStatus.DELIVERED, index.toLong())
        val messages = mutableStateOf((0..39).map { message(it) })
        show { MaterialTheme { ChatScreen("Alice", messages = messages.value, composerText = "") } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.runOnIdle { messages.value = messages.value + message(40, MessageDirection.OUTGOING) }
        compose.onNodeWithText("message-40").assertIsDisplayed()
    }

    @Test fun incomingMessageDoesNotMoveReaderAwayFromOlderMessages() {
        fun message(index: Int) = MessageUiModel("id-$index", "chat", "peer", "message-$index",
            MessageDirection.INCOMING, DeliveryStatus.DELIVERED, index.toLong())
        val messages = mutableStateOf((0..39).map(::message))
        show { MaterialTheme { ChatScreen("Alice", messages = messages.value, composerText = "") } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("message-0").assertIsDisplayed()
        compose.runOnIdle { messages.value = messages.value + message(40) }
        compose.onNodeWithText("message-0").assertIsDisplayed()
    }

    @Test fun pausedAudioDownloadInvokesActualAction() {
        var requested = false
        val media = com.torxone.app.chat.MediaUiModel("pending", com.torxone.app.media.MediaType.AUDIO,
            "Pending audio", "audio/wav", 1024, status = com.torxone.app.media.MediaStatus.QUEUED)
        show { MaterialTheme { com.torxone.app.ui.components.AudioPlayback(media, onDownload = { requested = true }) } }
        compose.onNodeWithText("Download").performClick()
        compose.runOnIdle { assertEquals(true, requested) }
        compose.onNodeWithContentDescription("Play audio").assertIsNotEnabled()
    }
    @Test fun identityRowActuallyCopiesFullIdentity() {
        val identity = "full-identity-1234567890"
        show { MaterialTheme { ProfileScreen("Alice", "", null, identity, null,
            onUpdateProfile = { _, _, _ -> }, onBackClick = {}, onShowQr = {}) } }
        compose.onNodeWithText("Identity ID").performScrollTo().performClick()
        // Android restricts clipboard reads to the focused activity; a package
        // context during an ActivityScenario transition can return null.
        compose.waitUntil(10_000) {
            var copied = false
            compose.runOnUiThread {
                val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                copied = compose.activity.hasWindowFocus() && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == identity
            }
            copied
        }
    }
    @Test fun groupCreationFailureAllowsRetry() {
        var attempts = 0
        val contact = ContactEntity("c", "r", "Alice", signingPublicKey = ByteArray(32), conversationId = "chat")
        show { MaterialTheme { NewGroupScreen(listOf(contact), onCreateGroup = { _, _, _ ->
            attempts++; error("Test creation failure")
        }, onBackClick = {}) } }
        compose.onNodeWithText("Alice").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithText("Group name").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithText("Group name").performTextInput("Test group")
        compose.onNodeWithContentDescription("Create group").performClick()
        compose.waitUntil { attempts == 1 }
        compose.onNodeWithContentDescription("Create group").assertIsDisplayed().performClick()
        compose.waitUntil { attempts == 2 }
    }
    @Test fun sharedGalleryShowsTruthfulEmptyStateAndBackAction() {
        var wentBack = false
        show { MaterialTheme { SharedMediaScreen(emptyList(), emptyList()) { wentBack = true } } }
        compose.onNodeWithText("No shared media or links yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(true, wentBack) }
    }
    @Test fun profileSaveFailureRetainsDraftAndShowsError() {
        show { MaterialTheme { ProfileScreen("Alice", "", null, "identity", null,
            onUpdateProfile = { _, _, _ -> error("Test profile failure") }, onBackClick = {}, onShowQr = {}) } }
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.onNodeWithText("Test profile failure").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun settingsAboutHasWorkingDialog() {
        show { MaterialTheme { SettingsScreen("Alice", about = "About me", lastSeenVisible = true, onlineVisible = true,
            readReceiptsEnabled = true, relayOnlyCalls = false, notificationsEnabled = true, soundEnabled = true,
            vibrationEnabled = true, notificationPreviewMode = "FULL", appLockEnabled = false, screenSecurityEnabled = false,
            autoConnectNearby = false, lowBandwidthMode = false,
            radioState = com.torxone.app.transport.lora.RadioConnectionState.Idle,
            haLowState = com.torxone.app.transport.halow.HaLowConnectionState.Idle,
            themeMode = "SYSTEM", dynamicColorsEnabled = false, autoDownloadMedia = true,
            onBackClick = {}, onProfileClick = {}, onPrivacyChange = { _, _ -> }, onNotificationChange = { _, _ -> },
            onSecurityChange = { _, _ -> }, onConnectionChange = { _, _ -> }, onPairRadio = {}, onPairHaLow = {},
            onAppearanceChange = { _, _ -> }, onDataChange = { _, _ -> }) } }
        compose.onNodeWithText("TorX One").performScrollTo().performClick()
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()
        compose.onNodeWithText("Close").assertDoesNotExist()
    }

    @Test fun audioControlsDriveRealPlayerAndPlaybackSpeed() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.filesDir, "test-audio.wav")
        val data = java.nio.ByteBuffer.allocate(44 + 16000 * 2 * 10).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(data.capacity() - 8).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(data.capacity() - 44)
        file.writeBytes(data.array())
        val media = com.torxone.app.chat.MediaUiModel("audio-test", com.torxone.app.media.MediaType.VOICE_NOTE,
            "Test audio", "audio/wav", file.length(), file.absolutePath)
        try {
            show { MaterialTheme { com.torxone.app.ui.components.AudioPlayback(media) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Audio ready").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1.0x").performClick()
            compose.onNodeWithText("1.5x").assertIsDisplayed()
            compose.onNodeWithContentDescription("Play audio").performClick()
            compose.onNodeWithContentDescription("Pause audio").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Play audio").assertIsDisplayed()
        } finally { file.delete() }
    }
}
