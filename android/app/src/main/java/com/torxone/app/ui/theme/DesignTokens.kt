package com.torxone.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass

data class AstraSpacing(
    val tiny: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val standard: Dp = 16.dp,
    val large: Dp = 20.dp,
    val extraLarge: Dp = 24.dp,
    val massive1: Dp = 32.dp,
    val massive2: Dp = 40.dp,
    val massive3: Dp = 48.dp,
    val massive4: Dp = 56.dp,
    val massive5: Dp = 64.dp,
    
    // Specific component spacing
    val snackbar: Dp = 16.dp,
    val fab: Dp = 16.dp,
    val iconTouchTarget: Dp = 48.dp
)

data class AstraRadii(
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val button: Dp = 16.dp,
    val card: Dp = 20.dp,
    val dialog: Dp = 28.dp,
    val bottomSheet: Dp = 32.dp,
    val messageBubble: Dp = 20.dp,
    val image: Dp = 16.dp,
    val chip: Dp = 8.dp
)

data class AstraElevations(
    val cardResting: Dp = 1.dp,
    val cardHover: Dp = 3.dp,
    val dialog: Dp = 6.dp,
    val fab: Dp = 6.dp,
    val bottomBar: Dp = 8.dp
)

data class AstraIconSizes(
    val tiny: Dp = 18.dp,
    val small: Dp = 20.dp,
    val medium: Dp = 24.dp,
    val standard: Dp = 24.dp,
    val large: Dp = 32.dp,
    val extraLarge: Dp = 48.dp
)

data class AstraAvatarSizes(
    val small: Dp = 32.dp,
    val medium: Dp = 40.dp,
    val standard: Dp = 48.dp,
    val large: Dp = 56.dp,
    val extraLarge: Dp = 72.dp,
    val massive: Dp = 96.dp,
    val profileHero: Dp = 140.dp
)

data class AstraOpacities(
    val disabled: Float = 0.38f,
    val muted: Float = 0.6f,
    val overlay: Float = 0.32f, // For dialog backgrounds
    val glassmorphism: Float = 0.15f
)

// Adaptive computing functions
fun getAdaptiveSpacing(windowSizeClass: WindowSizeClass?): AstraSpacing {
    return when (windowSizeClass?.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> AstraSpacing(
            standard = 24.dp,
            large = 28.dp,
            extraLarge = 32.dp
        )
        WindowWidthSizeClass.Medium -> AstraSpacing(
            standard = 20.dp,
            large = 24.dp,
            extraLarge = 28.dp
        )
        else -> AstraSpacing() // Compact/Default
    }
}

fun getAdaptiveRadii(windowSizeClass: WindowSizeClass?): AstraRadii {
    return when (windowSizeClass?.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> AstraRadii(
            messageBubble = 24.dp,
            card = 24.dp
        )
        WindowWidthSizeClass.Medium -> AstraRadii(
            messageBubble = 22.dp,
            card = 22.dp
        )
        else -> AstraRadii() // Compact/Default
    }
}

fun getAdaptiveIconSizes(windowSizeClass: WindowSizeClass?): AstraIconSizes {
    return AstraIconSizes() // Can be scaled similarly if required
}

// Default instances
val defaultAstraSpacing = AstraSpacing()
val defaultAstraRadii = AstraRadii()
val defaultAstraElevations = AstraElevations()
val defaultAstraIconSizes = AstraIconSizes()
val defaultAstraAvatarSizes = AstraAvatarSizes()
val defaultAstraOpacities = AstraOpacities()
