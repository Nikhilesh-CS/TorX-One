package com.torxone.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.entity.OutboxEntity
import com.torxone.app.ui.components.ConnectionDashboardDialog
import com.torxone.app.ui.components.ConnectionStatusBanner
import com.torxone.app.ui.components.MessageInfoDialog
import com.torxone.app.ui.connection.ConnectionPathInfo
import com.torxone.app.ui.connection.ConnectionUxSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionUxInteractionTest {
    @Test fun failedTorCanRestartWithNoQueuedMessagesAndSendingPaused() {
        var torRetries = 0
        var messageRetries = 0
        val state = ConnectionUxSnapshot(headline = "Sending paused", pendingDeliveries = 0,
            sendingPaused = true, torRetryAvailable = true, paths = listOf(
                ConnectionPathInfo("Tor", "Unavailable: Tor recovery budget exhausted; restart Tor to retry")))
        compose.setContent { MaterialTheme { ConnectionDashboardDialog(state, {},
            onRetry = { messageRetries++ }, onRetryTor = { torRetries++ }) } }
        compose.onNodeWithText("Restart Tor").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Retry queued messages").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, torRetries)
            assertEquals(0, messageRetries)
        }
    }

    @Test fun torRestartDisappearsWhileStartingAndWhenReady() {
        val state = mutableStateOf(ConnectionUxSnapshot(torRetryAvailable = true,
            paths = listOf(ConnectionPathInfo("Tor", "Stopped"))))
        compose.setContent { MaterialTheme { ConnectionDashboardDialog(state.value, {}, onRetryTor = {}) } }
        compose.onNodeWithText("Restart Tor").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(torRetryAvailable = false,
            paths = listOf(ConnectionPathInfo("Tor", "Starting"))) }
        compose.onNodeWithText("Restart Tor").assertDoesNotExist()
        compose.onNodeWithText("Starting").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(
            paths = listOf(ConnectionPathInfo("Tor", "Ready locally; peer route configured", true))) }
        compose.onNodeWithText("Restart Tor").assertDoesNotExist()
    }

    @Test fun pausedQueueOffersResumeAndSuppressesRetryButton() {
        var resumes = 0
        compose.setContent { MaterialTheme { ConnectionDashboardDialog(
            ConnectionUxSnapshot(headline = "Sending paused", pendingDeliveries = 2,
                sendingPaused = true, pendingControlDeliveries = 4), {}, onRetry = {},
            onToggleSendingPaused = { resumes++ }) } }
        compose.onNodeWithText("Pending messages: 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Background controls: 4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resume sending").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, resumes) }
    }
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    @Test fun pendingMessageRetryCallsExistingOutboxCallback() {
        var retries = 0
        val message = MessageEntity("message", "chat", "sender", "TEXT", "hello", MessageDirection.OUTGOING,
            "QUEUED", createdAt = 100)
        val pending = OutboxEntity("delivery", "message", "chat", "connection", "queue",
            byteArrayOf(1), byteArrayOf(2), "QUEUED", createdAt = 200, updatedAt = 200)
        compose.setContent { MaterialTheme { MessageInfoDialog(message, listOf(pending), {}, { retries++ }) } }
        compose.onNodeWithText("Retry delivery").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("Historical transport not recorded").performScrollTo().assertIsDisplayed()
    }

    @Test fun connectionDashboardDoesNotInventIdentityVerification() {
        var verifications = 0
        val state = ConnectionUxSnapshot(headline = "Tor ready", paths = listOf(
            ConnectionPathInfo("Tor", "Ready locally; peer route configured", true)),
            identity = "Identity not marked verified", pendingDeliveries = 1)
        compose.setContent { MaterialTheme { ConnectionDashboardDialog(state, {}, { verifications++ }) } }
        compose.onNodeWithText("Identity not marked verified").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View safety number").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, verifications) }
    }

    @Test fun offlineBannerOpensConnectionInformation() {
        var opens = 0
        compose.setContent { MaterialTheme { ConnectionStatusBanner(ConnectionUxSnapshot(
            headline = "Internet unavailable · searching nearby", pendingDeliveries = 2)) { opens++ } } }
        compose.onNodeWithText("Internet unavailable · searching nearby").performClick()
        compose.onNodeWithText("2 pending").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
