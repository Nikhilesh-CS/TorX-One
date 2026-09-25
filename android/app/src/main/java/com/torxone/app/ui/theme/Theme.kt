package com.torxone.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF8AB4F8),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF003064),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF004A8F),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD4E3FF),
    secondary = androidx.compose.ui.graphics.Color(0xFF82D9C8),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF003830),
    tertiary = androidx.compose.ui.graphics.Color(0xFF4DD0E1),
    background = androidx.compose.ui.graphics.Color(0xFF0F1419),
    surface = androidx.compose.ui.graphics.Color(0xFF151A20),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE3E2E6),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE3E2E6),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1E2530),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC4C6D0)
)

private val LightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF1A6DCC),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFD4E3FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF001C3A),
    secondary = androidx.compose.ui.graphics.Color(0xFF006B5A),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = androidx.compose.ui.graphics.Color(0xFF00838F),
    background = androidx.compose.ui.graphics.Color(0xFFFDFBFF),
    surface = androidx.compose.ui.graphics.Color(0xFFFDFBFF),
    onBackground = androidx.compose.ui.graphics.Color(0xFF1B1B1F),
    onSurface = androidx.compose.ui.graphics.Color(0xFF1B1B1F),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE7E0EC),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF49454F)
)

@Composable
fun TorXOneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
