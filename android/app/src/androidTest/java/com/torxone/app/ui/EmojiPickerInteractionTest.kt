package com.torxone.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.ui.components.EmojiReactionPicker
import com.torxone.app.ui.theme.TorXOneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmojiPickerInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    @Test fun searchableCatalogueClosesBeforeDispatchingExactUnicode() {
        val visible = mutableStateOf(true)
        val actions = mutableListOf<String>()
        compose.setContent { TorXOneTheme {
            if (visible.value) EmojiReactionPicker(
                onDismiss = { actions += "close"; visible.value = false },
                onSelected = { actions += it }
            )
        } }
        compose.onNodeWithText("Search emoji").performTextInput("red heart")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("React with red heart").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("React with red heart").performClick()
        compose.runOnIdle { assertEquals(listOf("close", "❤️"), actions) }
        compose.onNodeWithText("React to message").assertDoesNotExist()
    }

    @Test fun deviceKeyboardPreservesSkinToneSequence() {
        val actions = mutableListOf<String>()
        compose.setContent { TorXOneTheme {
            EmojiReactionPicker(onDismiss = { actions += "close" }, onSelected = { actions += it })
        } }
        compose.onNodeWithText("Or enter an emoji from your keyboard").performTextInput("👍🏽")
        compose.onNodeWithText("React with keyboard emoji").performClick()
        compose.runOnIdle { assertEquals(listOf("close", "👍🏽"), actions) }
    }
}
