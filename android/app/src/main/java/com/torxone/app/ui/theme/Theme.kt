package com.torxone.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TorXBrand {
    val Signal = Color(0xFF35E58D); val Violet = Color(0xFFB8A7FF)
    val Obsidian = Color(0xFF07140F); val Danger = Color(0xFFFFB4AB)
    val Warning = Color(0xFFFFD166)
}

object TorXSpacing {
    val xxs = 4.dp; val xs = 8.dp; val sm = 12.dp; val md = 16.dp
    val lg = 24.dp; val xl = 32.dp; val xxl = 48.dp
}

object TorXMotion {
    const val QUICK_MS = 120; const val STANDARD_MS = 220; const val EMPHASIZED_MS = 360
}

private val DarkColorScheme = darkColorScheme(
    primary = TorXBrand.Signal, onPrimary = TorXBrand.Obsidian,
    primaryContainer = Color(0xFF0E4F34), onPrimaryContainer = Color(0xFFB9FAD5),
    secondary = TorXBrand.Violet, onSecondary = Color(0xFF211A46),
    secondaryContainer = Color(0xFF37305E), onSecondaryContainer = Color(0xFFE6DEFF),
    tertiary = Color(0xFF70D8C0), onTertiary = Color(0xFF00382F),
    background = TorXBrand.Obsidian, surface = Color(0xFF0C1B14),
    surfaceVariant = Color(0xFF1A2A22), onBackground = Color(0xFFE2F1E7),
    onSurface = Color(0xFFE2F1E7), onSurfaceVariant = Color(0xFFB9C9BF),
    outline = Color(0xFF84978B), outlineVariant = Color(0xFF3B4B42),
    error = TorXBrand.Danger, onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006C43), onPrimary = Color.White,
    primaryContainer = Color(0xFF8AF8BA), onPrimaryContainer = Color(0xFF002112),
    secondary = Color(0xFF5C4DA1), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DEFF), onSecondaryContainer = Color(0xFF190F54),
    tertiary = Color(0xFF006B5B), onTertiary = Color.White,
    background = Color(0xFFF7FCF8), surface = Color(0xFFF7FCF8),
    surfaceVariant = Color(0xFFDDE8E0), onBackground = Color(0xFF172019),
    onSurface = Color(0xFF172019), onSurfaceVariant = Color(0xFF3F4942),
    outline = Color(0xFF6F7972), outlineVariant = Color(0xFFBFC9C1),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)

private val TorXTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
)

private val TorXShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun TorXOneTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colorScheme, typography = TorXTypography, shapes = TorXShapes, content = content)
}

val ColorScheme.readReceipt: Color @Composable @ReadOnlyComposable get() = tertiary
val ColorScheme.onlineStatus: Color @Composable @ReadOnlyComposable get() = TorXBrand.Signal
val ColorScheme.goldAccent: Color @Composable @ReadOnlyComposable get() = TorXBrand.Warning
