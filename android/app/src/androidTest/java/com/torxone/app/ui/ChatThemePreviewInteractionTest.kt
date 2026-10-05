package com.torxone.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.ui.appearance.ChatAppearance
import com.torxone.app.ui.components.ChatThemePreview
import com.torxone.app.ui.theme.TorXOneTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatThemePreviewInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun previewUsesOnlyDemoContentAndSupportsLargeText() {
        compose.setContent { TorXOneTheme(darkTheme = false, fontSize = "EXTRA_LARGE") { ChatThemePreview(ChatAppearance(preset = "DEPTH", motionMode = "REDUCED")) } }
        compose.onNodeWithText("A quiet place to talk.").assertIsDisplayed()
        compose.onNodeWithText("Make it feel like you.").assertIsDisplayed()
    }
}
