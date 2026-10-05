package com.torxone.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.conversations.ConversationUiModel
import com.torxone.app.ui.screens.ConversationListScreen
import com.torxone.app.ui.theme.TorXOneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationListRedesignTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()
    private fun row(id: String = "chat", preview: String = "Hello", status: DeliveryStatus? = null) =
        ConversationUiModel(id, "Alice", preview, null, 0, false, false, false, false,
            isLastMessageOutgoing = status != null, lastMessageStatus = status)

    @Test fun simplifiedHeaderPreservesNavigationActions() {
        val actions = mutableListOf<String>()
        compose.setContent { TorXOneTheme { ConversationListScreen(listOf(row()),
            onConversationClick = { actions += it }, onScanQrClick = { actions += "qr" },
            onToggleSearch = { if (it) actions += "search" }, onSettingsClick = { actions += "settings" }) } }
        compose.onNodeWithText("TorX One").assertIsDisplayed()
        compose.onNodeWithText("Private mesh messenger").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Scan QR").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Alice").performClick()
        compose.runOnIdle { assertEquals(listOf("search", "qr", "settings", "chat"), actions) }
    }

    @Test fun zeroSearchResultsKeepClearRecovery() {
        var query = "absent"
        compose.setContent { TorXOneTheme { ConversationListScreen(emptyList(), {}, {}, searchQuery = query,
            isSearching = true, onSearchQueryChange = { query = it }) } }
        compose.onNodeWithText("No matching chats or messages").assertIsDisplayed()
        compose.onNodeWithText("Clear search").performClick()
        compose.runOnIdle { assertEquals("", query) }
    }

    @Test fun failedAndReadReceiptsExposeDistinctSpokenStates() {
        compose.setContent { TorXOneTheme { ConversationListScreen(listOf(
            row("failed", status = DeliveryStatus.FAILED), row("read", status = DeliveryStatus.READ)), {}, {}) } }
        compose.onNodeWithContentDescription("Couldn’t send", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Read", useUnmergedTree = true).assertExists()
    }

    @Test fun explicitDraftDoesNotMisclassifyMessageText() {
        compose.setContent { TorXOneTheme { ConversationListScreen(listOf(
            row("draft").copy(draftText = "Meeting at seven"), row("ordinary", "Draft: somebody else's words")), {}, {}) } }
        compose.onNodeWithText("Draft:", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Meeting at seven", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Draft: somebody else's words", useUnmergedTree = true).assertExists()
    }

    @Test fun pinActionRoutesTheExactConversationOnce() {
        val selected = row().copy(isPinned = true)
        val actions = mutableListOf<String>()
        compose.setContent { TorXOneTheme { ConversationListScreen(listOf(selected), {}, {},
            onPinClick = { actions += it.conversationId }) } }
        compose.onNodeWithContentDescription("Pinned", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Alice").performTouchInput { longClick() }
        compose.onNodeWithText("Unpin chat").performClick()
        compose.runOnIdle { assertEquals(listOf("chat"), actions) }
    }

    @Test fun narrowLargeTextKeepsRowAndToolbarActionsAvailable() {
        var opened: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                TorXOneTheme { Box(Modifier.width(320.dp)) { ConversationListScreen(listOf(row().copy(
                    title = "A very long contact name that should not hide actions", unreadCount = 25, manuallyUnread = true)),
                    { opened = it }, {}) } }
            }
        }
        compose.onNodeWithContentDescription("Search").assertIsDisplayed()
        compose.onNodeWithContentDescription("25 unread messages", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("A very long contact name that should not hide actions").performClick()
        compose.runOnIdle { assertEquals("chat", opened) }
    }

    @Test fun firstConversationStillHasExplicitQrAction() {
        var scans = 0
        compose.setContent { TorXOneTheme { ConversationListScreen(emptyList(), {}, { scans++ }) } }
        compose.onNodeWithText("Scan QR Code").performClick()
        compose.runOnIdle { assertEquals(1, scans) }
    }
}
