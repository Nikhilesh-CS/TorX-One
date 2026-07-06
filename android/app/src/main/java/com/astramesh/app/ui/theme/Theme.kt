package com.astramesh.app.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
    backgroundColor: Color = Color(0x15FFFFFF),
    borderColor: Color = Color(0x20FFFFFF)
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(backgroundColor)
    .border(1.dp, borderColor, RoundedCornerShape(cornerRadius))

private val DarkColorScheme = darkColorScheme(
    primary = TorAccent, // Default fallback
    secondary = TorAccent,
    background = DeepSpace,
    surface = SurfaceDark,
    surfaceVariant = SurfaceDarker,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onPrimary = DeepSpace,
    onSecondary = DeepSpace,
    outline = OutlineColor,
    onSurfaceVariant = TextSecondary
)

@Composable
fun AstraMeshTheme(
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
                // Let the framework handle edge-to-edge styling, just ensure status bar is transparent
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            }
        }
    }

    val targetColor = when (activeTransport) {
        NetworkTransport.BLUETOOTH -> BluetoothAccent
        NetworkTransport.WIFI_DIRECT -> WiFiAccent
        NetworkTransport.TOR -> TorAccent
        NetworkTransport.DISCONNECTED -> DisconnectedAccent
    }

    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 1500),
        label = "transportColorAnimation"
    )

    // Override primary color dynamically based on transport
    val dynamicColorScheme = DarkColorScheme.copy(
        background = if (useAmoledTheme) AmoledBlack else DeepSpace,
        primary = animatedColor,
        secondary = animatedColor
    )

    val windowSizeClass = rememberWindowSizeClass()
    val dynamicSpacing = getAdaptiveSpacing(windowSizeClass)
    val dynamicRadii = getAdaptiveRadii(windowSizeClass)
    val dynamicIconSizes = getAdaptiveIconSizes(windowSizeClass)

    CompositionLocalProvider(
        LocalActiveTransport provides activeTransport,
        LocalTransportColor provides animatedColor,
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
            colorScheme = dynamicColorScheme,
            typography = AstraTypography,
            content = content
        )
    }
}

// Convenient accessor object
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
    val colors: androidx.compose.material3.ColorScheme
        @Composable get() = MaterialTheme.colorScheme
    val typography: androidx.compose.material3.Typography
        @Composable get() = MaterialTheme.typography
    val reduceMotion: Boolean
        @Composable get() = LocalReduceMotion.current
    val showTransportIcons: Boolean
        @Composable get() = LocalShowTransportIcons.current
}
