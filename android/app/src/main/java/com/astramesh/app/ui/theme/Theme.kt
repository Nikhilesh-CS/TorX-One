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
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DeepSpace.toArgb()
            window.navigationBarColor = DeepSpace.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
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
        primary = animatedColor,
        secondary = animatedColor
    )

    CompositionLocalProvider(
        LocalActiveTransport provides activeTransport,
        LocalTransportColor provides animatedColor
    ) {
        MaterialTheme(
            colorScheme = dynamicColorScheme,
            typography = AstraTypography,
            content = content
        )
    }
}
