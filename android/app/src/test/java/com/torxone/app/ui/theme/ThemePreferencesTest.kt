package com.torxone.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class ThemePreferencesTest {
    @Test fun legacySourceIsPreservedWithoutEnablingItForNewUsers() {
        assertEquals(TorXThemeSource.TORX, TorXThemeSource.resolve(null))
        assertEquals(TorXThemeSource.SYSTEM, TorXThemeSource.resolve(null, true))
        assertEquals(TorXThemeSource.TORX, TorXThemeSource.resolve(null, false))
        assertEquals(TorXThemeSource.TORX, TorXThemeSource.resolve("TORX", true))
        assertEquals(TorXThemeSource.SYSTEM, TorXThemeSource.resolve("SYSTEM", false))
        assertEquals(TorXThemeSource.TORX, TorXThemeSource.resolve("corrupt", true))
    }

    @Test fun legacyTypographyAndUnknownPreferencesResolveSafely() {
        assertEquals(TorXTypographyScale.Compact, TorXTypographyScale.resolve("SMALL"))
        assertEquals(TorXTypographyScale.Default, TorXTypographyScale.resolve("MEDIUM"))
        assertEquals(TorXTypographyScale.Large, TorXTypographyScale.resolve("LARGE"))
        assertEquals(TorXTypographyScale.Accessibility, TorXTypographyScale.resolve("EXTRA_LARGE"))
        assertEquals("MEDIUM", TorXTypographyScale.storageValue("corrupt"))
        assertEquals(TorXAccent.SAGE, TorXAccent.resolve("corrupt"))
    }

    @Test fun everyCuratedAccentAndBubbleTextMeetsNormalTextContrast() {
        for (dark in listOf(false, true)) for (accent in TorXAccent.entries) {
            val colors = torXBrandColorScheme(dark, accent)
            for ((foreground, background) in listOf(
                colors.primary to colors.surface,
                colors.onPrimary to colors.primary,
                colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.surfaceContainerHighest,
                colors.error to colors.surface
            )) assertTrue("$dark ${accent.name}: ${contrast(foreground, background)}", contrast(foreground, background) >= 4.5)
        }
    }

    @Test fun accentChangesDoNotRecolorAppChrome() {
        for (dark in listOf(false, true)) {
            val baseline = torXBrandColorScheme(dark)
            for (accent in TorXAccent.entries) {
                val colors = torXBrandColorScheme(dark, accent)
                assertEquals(baseline.background, colors.background)
                assertEquals(baseline.surface, colors.surface)
                assertEquals(baseline.surfaceContainer, colors.surfaceContainer)
            }
        }
    }

    @Test fun typographyPresetsIncreaseReadingSizeAndKeepLineHeight() {
        val styles = TorXTypographyScale.entries.map { torXTypography(it) }
        styles.zipWithNext().forEach { (small, large) ->
            assertTrue(large.bodyLarge.fontSize.value > small.bodyLarge.fontSize.value)
            assertTrue(large.labelSmall.fontSize.value > small.labelSmall.fontSize.value)
        }
        styles.forEach {
            assertTrue(it.bodyLarge.lineHeight.value > it.bodyLarge.fontSize.value)
            assertTrue(it.labelSmall.lineHeight.value > it.labelSmall.fontSize.value)
            assertTrue(it.displayLarge.lineHeight.value > it.displayLarge.fontSize.value)
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
