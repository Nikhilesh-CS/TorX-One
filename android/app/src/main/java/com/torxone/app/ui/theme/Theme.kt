package com.torxone.app.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class NetworkTransport {
    DISCONNECTED,
    BLUETOOTH,
    WIFI_DIRECT,
    TOR
}

val LocalActiveTransport = staticCompositionLocalOf { NetworkTransport.DISCONNECTED }
val LocalTransportColor = staticCompositionLocalOf { DisconnectedAccent }

// Design Tokens Locals
val LocalSpacing = staticCompositionLocalOf { defaultAstraSpacing }
val LocalRadii = staticCompositionLocalOf { defaultAstraRadii }
val LocalElevations = staticCompositionLocalOf { defaultAstraElevations }
val LocalIconSizes = staticCompositionLocalOf { defaultAstraIconSizes }
val LocalAvatarSizes = staticCompositionLocalOf { defaultAstraAvatarSizes }
val LocalOpacities = staticCompositionLocalOf { defaultAstraOpacities }
val LocalReduceMotion = staticCompositionLocalOf { false }
val LocalShowTransportIcons = staticCompositionLocalOf { true }

fun Modifier.glassmorphism(
    cornerRadius: Dp = 16.dp,
    backgroundColor: Color = SurfaceCard,
    borderColor: Color = BorderColor
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(backgroundColor)
    .border(1.dp, borderColor, RoundedCornerShape(cornerRadius))

private val ProfessionalLightColorScheme = lightColorScheme(
    primary = TorXPrimary, // #2563EB Professional blue
    onPrimary = Color.White,
    primaryContainer = TorXPrimarySoft, // #EFF6FF Pale blue soft background
    onPrimaryContainer = TorXPrimaryDark, // #1D4ED8
    secondary = SecondaryText, // #475569 Slate
    onSecondary = Color.White,
    secondaryContainer = SurfaceSecondary, // #F1F5F9 Soft slate
    onSecondaryContainer = PrimaryText, // #0F172A Dark slate
    tertiary = TorXPrimary,
    onTertiary = Color.White,
    tertiaryContainer = TorXPrimarySoft,
    onTertiaryContainer = TorXPrimaryDark,
    background = AppBackground, // #F8FAFC Very light gray
    onBackground = PrimaryText, // #0F172A Dark slate
    surface = SurfaceCard, // #FFFFFF White
    onSurface = PrimaryText, // #0F172A Dark slate
    surfaceVariant = SurfaceSecondary, // #F1F5F9 Soft slate
    onSurfaceVariant = SecondaryText, // #475569 Slate
    outline = BorderColor, // #E2E8F0 Light slate border
    outlineVariant = BorderColor.copy(alpha = 0.6f),
    error = ErrorRed, // #DC2626
    onError = Color.White,
    errorContainer = Color(0xFFFEF2F2),
    onErrorContainer = ErrorRed
)

private val ProfessionalDarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A).copy(alpha = 0.5f),
    onPrimaryContainer = Color(0xFF93C5FD),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFF1F5F9),
    tertiary = Color(0xFF3B82F6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF1E3A8A).copy(alpha = 0.5f),
    onTertiaryContainer = Color(0xFF93C5FD),
    background = Color(0xFF0B0F19),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF151D2A),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF263346),
    outlineVariant = Color(0xFF1E293B),
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFCA5A5)
)

val LocalDarkMode = staticCompositionLocalOf { false }

@Composable
fun TorXOneTheme(
    activeTransport: NetworkTransport = NetworkTransport.DISCONNECTED,
    useAmoledTheme: Boolean = false,
    reduceMotion: Boolean = false,
    showTransportIcons: Boolean = true,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
            is Activity -> this
            is android.content.ContextWrapper -> baseContext.findActivity()
            else -> null
        }

        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
                WindowCompat.setDecorFitsSystemWindows(window, false)
                // Dark icons on light status & navigation bars, light icons on dark
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useAmoledTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useAmoledTheme
            }
        }
    }

    // Transport status accent color (animated smoothly, isolated from Material primary)
    val targetTransportColor = when (activeTransport) {
        NetworkTransport.BLUETOOTH -> BluetoothAccent
        NetworkTransport.WIFI_DIRECT -> WiFiAccent
        NetworkTransport.TOR -> TorAccent
        NetworkTransport.DISCONNECTED -> DisconnectedAccent
    }

    val animatedTransportColor by animateColorAsState(
        targetValue = targetTransportColor,
        animationSpec = tween(durationMillis = 800),
        label = "transportColorAnimation"
    )

    val windowSizeClass = rememberWindowSizeClass()
    val dynamicSpacing = getAdaptiveSpacing(windowSizeClass)
    val dynamicRadii = getAdaptiveRadii(windowSizeClass)
    val dynamicIconSizes = getAdaptiveIconSizes(windowSizeClass)

    CompositionLocalProvider(
        LocalActiveTransport provides activeTransport,
        LocalTransportColor provides animatedTransportColor,
        LocalDarkMode provides useAmoledTheme,
        LocalSpacing provides dynamicSpacing,
        LocalRadii provides dynamicRadii,
        LocalElevations provides defaultAstraElevations,
        LocalIconSizes provides dynamicIconSizes,
        LocalAvatarSizes provides defaultAstraAvatarSizes,
        LocalOpacities provides defaultAstraOpacities,
        LocalReduceMotion provides reduceMotion,
        LocalShowTransportIcons provides showTransportIcons
    ) {
        MaterialTheme(
            colorScheme = if (useAmoledTheme) ProfessionalDarkColorScheme else ProfessionalLightColorScheme,
            typography = AstraTypography,
            content = content
        )
    }
}

// Convenient design token accessor object
object AstraTheme {
    val spacing: AstraSpacing
        @Composable get() = LocalSpacing.current
    val radii: AstraRadii
        @Composable get() = LocalRadii.current
    val elevations: AstraElevations
        @Composable get() = LocalElevations.current
    val iconSizes: AstraIconSizes
        @Composable get() = LocalIconSizes.current
    val avatarSizes: AstraAvatarSizes
        @Composable get() = LocalAvatarSizes.current
    val opacities: AstraOpacities
        @Composable get() = LocalOpacities.current
    val colors: ColorScheme
        @Composable get() = MaterialTheme.colorScheme
    val typography: androidx.compose.material3.Typography
        @Composable get() = MaterialTheme.typography
    val reduceMotion: Boolean
        @Composable get() = LocalReduceMotion.current
    val showTransportIcons: Boolean
        @Composable get() = LocalShowTransportIcons.current
    val isDarkMode: Boolean
        @Composable get() = LocalDarkMode.current
    val surfaceCard: Color
        @Composable get() = if (LocalDarkMode.current) Color(0xFF151D2A) else SurfaceCard
    val surfaceApp: Color
        @Composable get() = if (LocalDarkMode.current) Color(0xFF0B0F19) else AppBackground
    val textPrimary: Color
        @Composable get() = if (LocalDarkMode.current) Color(0xFFF8FAFC) else PrimaryText
    val textSecondary: Color
        @Composable get() = if (LocalDarkMode.current) Color(0xFF94A3B8) else SecondaryText
    val border: Color
        @Composable get() = if (LocalDarkMode.current) Color(0xFF263346) else BorderColor
    val transportColor: Color
        @Composable get() = LocalTransportColor.current
}

// Semantic extensions for ColorScheme
val ColorScheme.transport: Color
    @Composable get() = LocalTransportColor.current

val ColorScheme.textPrimary: Color
    @Composable get() = onBackground

val ColorScheme.textSecondary: Color
    @Composable get() = onSurfaceVariant

val ColorScheme.textMuted: Color
    @Composable get() = TextMuted

val ColorScheme.border: Color
    @Composable get() = outline

val ColorScheme.primarySoft: Color
    @Composable get() = primaryContainer

val ColorScheme.surfaceSecondary: Color
    @Composable get() = surfaceVariant

val ColorScheme.success: Color
    @Composable get() = SuccessGreen

val ColorScheme.warning: Color
    @Composable get() = WarningAmber
