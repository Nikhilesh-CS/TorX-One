package com.torxone.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.ui.screens.SettingsScreen
import com.torxone.app.ui.theme.TorXOneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()
    @Composable private fun settings(back: () -> Unit = {}, privacy: (String, Boolean) -> Unit = { _, _ -> },
        defaults: () -> Unit = {}) {
        TorXOneTheme {
            SettingsScreen("Alice", about = "About me", lastSeenVisible = true, onlineVisible = true,
                readReceiptsEnabled = true, relayOnlyCalls = false, notificationsEnabled = true,
                soundEnabled = true, vibrationEnabled = true, notificationPreviewMode = "FULL",
                appLockEnabled = false, screenSecurityEnabled = false, autoConnectNearby = true,
                lowBandwidthMode = false, radioState = com.torxone.app.transport.lora.RadioConnectionState.Idle,
                haLowState = com.torxone.app.transport.halow.HaLowConnectionState.Idle,
                themeMode = "SYSTEM", dynamicColorsEnabled = false, autoDownloadMedia = true,
                onBackClick = back, onProfileClick = {}, onPrivacyChange = privacy,
                onNotificationChange = { _, _ -> }, onSecurityChange = { _, _ -> },
                onConnectionChange = { _, _ -> }, onPairRadio = {}, onPairHaLow = {},
                onAppearanceChange = { _, _ -> }, onDataChange = { _, _ -> }, onChatDefaultsClick = defaults)
        }
    }

    @Test fun privacyCategoryKeepsExistingAuthorityAndBackReturnsToRoot() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        var parentBack = 0
        compose.setContent { settings(back = { parentBack++ }, privacy = { field, value -> changes += field to value }) }
        compose.onNodeWithText("Last seen").assertDoesNotExist()
        compose.onNodeWithText("Privacy").performScrollTo().performClick()
        compose.onNodeWithText("Last seen").performClick()
        compose.runOnIdle { assertEquals(listOf("lastSeen" to false), changes) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Last seen").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, parentBack) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, parentBack) }
    }

    @Test fun callsCategoryStillUsesRelayOnlyPrivacySetting() {
        val changes = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { settings(privacy = { field, value -> changes += field to value }) }
        compose.onNodeWithText("Calls").performScrollTo().performClick()
        compose.onNodeWithText("Maximum Call Privacy").performClick()
        compose.runOnIdle { assertEquals(listOf("relayOnlyCalls" to true), changes) }
    }

    @Test fun chatAppearanceEntryUsesActualProvidedCallback() {
        var opened = 0
        compose.setContent { settings(defaults = { opened++ }) }
        compose.onNodeWithText("Chats").performScrollTo().performClick()
        compose.onNodeWithText("Default chat theme").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
