package com.torxone.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.chat.MessageUiModel
import com.torxone.app.chat.VoiceRecordingState
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.media.MediaType
import com.torxone.app.ui.components.AttachmentPreview
import com.torxone.app.ui.components.PendingAttachment
import com.torxone.app.ui.screens.ChatScreen
import com.torxone.app.ui.theme.TorXOneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRedesignInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()
    private fun message(index: Int) = MessageUiModel("id-$index", "chat", "peer", "body-$index",
        MessageDirection.INCOMING, DeliveryStatus.DELIVERED, System.currentTimeMillis() + index)

    @Test fun dateAndImmutableUnreadDividerDoNotDependOnLiveReadStatus() {
        compose.setContent { TorXOneTheme { ChatScreen("Alice", listOf(message(0), message(1).copy(status = DeliveryStatus.READ)), "",
            entryUnreadMessageId = "id-1", entryUnreadCount = 2) } }
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("2 unread messages").assertExists()
        compose.onNodeWithText("body-1").assertIsDisplayed()
    }

    @Test fun contextualReactionRoutesExactMessageAndClosesTray() {
        var selected: Pair<String, String>? = null
        compose.setContent { TorXOneTheme { ChatScreen("Alice", listOf(message(0)), "",
            onToggleReaction = { id, emoji -> selected = id to emoji }) } }
        compose.onNodeWithText("body-0").performTouchInput { longClick() }
        compose.onNodeWithText("\uD83D\uDC4D").performClick()
        compose.runOnIdle { assertEquals("id-0" to "\uD83D\uDC4D", selected) }
        compose.onNodeWithText("More actions").assertDoesNotExist()
    }

    @Test fun replyRemainsAvailableWithoutSwipeGesture() {
        var replied: String? = null
        compose.setContent { TorXOneTheme { ChatScreen("Alice", listOf(message(0)), "", onReply = { replied = it.logicalMessageId }) } }
        compose.onNodeWithText("body-0").performTouchInput { longClick() }
        compose.onNodeWithText("More actions").performClick()
        compose.onNodeWithText("Reply").performClick()
        compose.runOnIdle { assertEquals("id-0", replied) }
    }

    @Test fun incomingWhileReadingHistoryShowsReachableLatestAction() {
        val messages = mutableStateOf((0..39).map(::message))
        compose.setContent { TorXOneTheme { ChatScreen("Alice", messages.value, "") } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.runOnIdle { messages.value = messages.value + message(40) }
        compose.onNodeWithText("body-0").assertIsDisplayed()
        compose.onNodeWithContentDescription("Scroll to latest").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("body-40").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("body-40").assertIsDisplayed()
    }

    @Test fun pendingAttachmentCancelDoesNotInvokeSend() {
        var sends = 0; var cancels = 0
        compose.setContent { TorXOneTheme { AttachmentPreview(PendingAttachment("note.txt", "text/plain", MediaType.DOCUMENT),
            busy = false, error = null, onCancel = { cancels++ }, onSend = { sends++ }) } }
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, sends); assertEquals(1, cancels) }
    }

    @Test fun preparingAttachmentDisablesRepeatedConfirmation() {
        compose.setContent { TorXOneTheme { AttachmentPreview(PendingAttachment("note.txt", "text/plain", MediaType.DOCUMENT),
            busy = true, error = null, onCancel = {}, onSend = {}) } }
        compose.onNodeWithText("Preparing…").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test fun sendBusyAndErrorsAreVisiblePresentationState() {
        var dismissed = false
        compose.setContent { TorXOneTheme { ChatScreen("Alice", listOf(message(0)), "hello", isSending = true,
            uiError = "Could not send; message retained", onDismissUiError = { dismissed = true }) } }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithText("Could not send; message retained").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss error").performClick()
        compose.runOnIdle { assertEquals(true, dismissed) }
    }

    @Test fun realRecorderWaveformHasAccessibleRecordingControls() {
        compose.setContent { TorXOneTheme { ChatScreen("Alice", emptyList(), "", voiceRecording = VoiceRecordingState(
            isRecording = true, elapsedDurationMs = 1000, amplitudeLevels = listOf(0.1f, 0.8f, 0.2f))) } }
        compose.onNodeWithContentDescription("Recording waveform").assertExists()
        compose.onNodeWithContentDescription("Cancel recording").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send voice note").assertIsDisplayed()
    }

    @Test fun appearanceDelegatesToNewScreenWhenConsumerExists() {
        var opens = 0
        compose.setContent { TorXOneTheme { ChatScreen("Alice", emptyList(), "", onEditAppearance = { opens++ }) } }
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Chat appearance").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun unavailableQuotedTargetRequestsWindowAndJumpsWhenAvailable() {
        var requested: String? = null
        val messages = mutableStateOf(listOf(message(0).copy(quotedMessage = com.torxone.app.chat.QuotedMessageUiModel(
            "id-20", "Peer", "older quote"))))
        compose.setContent { TorXOneTheme { ChatScreen("Alice", messages.value, "", onJumpToMessage = { requested = it }) } }
        compose.onNodeWithText("older quote").performClick()
        compose.runOnIdle { assertEquals("id-20", requested); messages.value = (0..30).map(::message) }
        compose.onNodeWithText("body-20").assertIsDisplayed()
    }
    @Test fun additionalReactionRoutesExactMessage() {
        var selected: Pair<String, String>? = null
        compose.setContent { TorXOneTheme { ChatScreen("Alice", listOf(message(0)), "",
            onToggleReaction = { id, emoji -> selected = id to emoji }) } }
        compose.onNodeWithText("body-0").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("More reactions").performClick()
        compose.onNodeWithContentDescription("React with celebration").performClick()
        compose.runOnIdle { assertEquals("id-0" to "🎉", selected) }
        compose.onNodeWithText("React to message").assertDoesNotExist()
    }

    @Test fun pausedRetryUsesCallbackWhileTerminalFailureOpensDetails() {
        var retries = 0; var inspected: String? = null
        val rows = listOf(message(0).copy(direction = MessageDirection.OUTGOING, status = DeliveryStatus.WAITING_FOR_PEER),
            message(1).copy(direction = MessageDirection.OUTGOING, status = DeliveryStatus.FAILED))
        compose.setContent { TorXOneTheme { ChatScreen("Alice", rows, "",
            onRetryDelivery = { retries++ }, onRequestMessageInfo = { inspected = it.logicalMessageId }) } }
        compose.onNodeWithText("Retry delivery").performClick()
        compose.onNodeWithText("Couldn't send. View details.").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals("id-1", inspected) }
    }
}
