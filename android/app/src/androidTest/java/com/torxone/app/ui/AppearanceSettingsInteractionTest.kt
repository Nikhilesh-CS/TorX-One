package com.torxone.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torxone.app.ui.components.SettingsAppearanceControls
import com.torxone.app.ui.theme.TorXOneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceSettingsInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<UiTestActivity>()

    @Test fun selectionsUseDistinctSettingsCallbacksAndReflectSavedState() {
        val source = mutableStateOf("TORX")
        val accent = mutableStateOf("SAGE")
        val size = mutableStateOf("MEDIUM")
        val changes = mutableListOf<Pair<String, Any>>()
        compose.setContent {
            TorXOneTheme(darkTheme = false) {
                SettingsAppearanceControls(source.value, accent.value, size.value, onChange = { field, value ->
                    changes += field to value
                    when (field) {
                        "themeSource" -> source.value = value as String
                        "accentId" -> accent.value = value as String
                        "fontSize" -> size.value = value as String
                    }
                }, modifier = Modifier.verticalScroll(rememberScrollState()), systemColorsSupported = true)
            }
        }
        compose.onNodeWithText("System colors").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Your TorX accent is saved for when you use TorX Brand.").assertIsDisplayed()
        compose.onNodeWithText("Ocean").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Extra Large").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle {
            assertEquals(listOf("themeSource" to "SYSTEM", "accentId" to "OCEAN", "fontSize" to "EXTRA_LARGE"), changes)
        }
    }

    @Test fun unsupportedSystemColorsExplainFallbackAndChoicesWrapAtLargeFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                TorXOneTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        SettingsAppearanceControls("SYSTEM", "GRAPHITE", "EXTRA_LARGE", onChange = { _, _ -> },
                            systemColorsSupported = false)
                    }
                }
            }
        }
        compose.onNodeWithText("System colors require Android 12 or later. This device uses TorX Brand.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Graphite").performScrollTo().assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Extra Large").performScrollTo().assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Android text size also applies.").performScrollTo().assertIsDisplayed()
    }
}
