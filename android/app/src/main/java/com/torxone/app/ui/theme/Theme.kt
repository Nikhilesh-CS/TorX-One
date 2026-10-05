package com.torxone.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object TorXBrand {
    val Signal = Color(0xFFA9CBB6)
    val Violet = Color(0xFFDFB8F3)
    val Obsidian = Color(0xFF141619)
    val Danger = Color(0xFFFFB4AB)
    val Warning = Color(0xFFEAC77D)
}
object TorXSpacing {
    val xxs = 4.dp; val xs = 8.dp; val sm = 12.dp; val md = 16.dp
    val lg = 24.dp; val xl = 32.dp; val xxl = 48.dp
}
object TorXMotion {
    const val QUICK_MS = 120; const val STANDARD_MS = 220; const val EMPHASIZED_MS = 360
}

/** Accent changes controls and tonal highlights, never the neutral app chrome. */
fun torXBrandColorScheme(dark: Boolean, accent: TorXAccent = TorXAccent.SAGE): ColorScheme {
    val primary = Color(if (dark) accent.darkArgb else accent.lightArgb)
    val background = Color(if (dark) 0xFF141619 else 0xFFF8F9F7)
    val surface = Color(if (dark) 0xFF191C20 else 0xFFF8F9F7)
    val text = Color(if (dark) 0xFFF0F1ED else 0xFF1A1D21)
    val variantText = Color(if (dark) 0xFFBAC0C6 else 0xFF535B63)
    val container = lerp(surface, primary, if (dark) 0.18f else 0.10f)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = if (dark) Color(0xFF17211B) else Color.White,
        primaryContainer = container, onPrimaryContainer = text,
        inversePrimary = Color(if (dark) accent.lightArgb else accent.darkArgb),
        secondary = primary, onSecondary = if (dark) Color(0xFF17211B) else Color.White,
        secondaryContainer = container, onSecondaryContainer = text,
        tertiary = primary, onTertiary = if (dark) Color(0xFF17211B) else Color.White,
        tertiaryContainer = container, onTertiaryContainer = text,
        background = background, onBackground = text,
        surface = surface, onSurface = text,
        surfaceVariant = Color(if (dark) 0xFF2B3036 else 0xFFE5E8E8), onSurfaceVariant = variantText,
        surfaceDim = Color(if (dark) 0xFF141619 else 0xFFD9DDDD),
        surfaceBright = Color(if (dark) 0xFF373C43 else 0xFFFFFFFF),
        surfaceContainerLowest = Color(if (dark) 0xFF101215 else 0xFFFFFFFF),
        surfaceContainerLow = Color(if (dark) 0xFF1D2025 else 0xFFF1F3F2),
        surfaceContainer = Color(if (dark) 0xFF23272C else 0xFFEBEEEC),
        surfaceContainerHigh = Color(if (dark) 0xFF2A2E34 else 0xFFE5E8E6),
        surfaceContainerHighest = Color(if (dark) 0xFF33383E else 0xFFDFE3E1),
        surfaceTint = primary,
        inverseSurface = Color(if (dark) 0xFFE5E8E6 else 0xFF2A2E34),
        inverseOnSurface = Color(if (dark) 0xFF1A1D21 else 0xFFF0F1ED),
        outline = Color(if (dark) 0xFF8D959E else 0xFF747D85),
        outlineVariant = Color(if (dark) 0xFF41474F else 0xFFCED4D8),
        error = Color(if (dark) 0xFFFFB4AB else 0xFFA63235),
        onError = Color(if (dark) 0xFF690005 else 0xFFFFFFFF),
        errorContainer = Color(if (dark) 0xFF51282C else 0xFFFFDAD6),
        onErrorContainer = Color(if (dark) 0xFFFFDAD6 else 0xFF410002),
        scrim = Color.Black
    )
}
private val TorXShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun TorXOneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    accentId: String = "SAGE",
    fontSize: String = "MEDIUM",
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val accent = TorXAccent.resolve(accentId)
    val colorScheme = remember(context, darkTheme, dynamicColor, accent) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else torXBrandColorScheme(darkTheme, accent)
    }
    val scale = TorXTypographyScale.resolve(fontSize)
    val typography = remember(scale) { torXTypography(scale) }
    MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = TorXShapes, content = content)
}
val ColorScheme.readReceipt: Color @Composable @ReadOnlyComposable get() = primary
val ColorScheme.onlineStatus: Color @Composable @ReadOnlyComposable get() = primary
val ColorScheme.goldAccent: Color @Composable @ReadOnlyComposable get() = if (background.luminance() < 0.5f) TorXBrand.Warning else Color(0xFF795A13)
