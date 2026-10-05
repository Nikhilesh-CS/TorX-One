package com.torxone.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Explicit readable presets. Compose sp units continue to honor Android font scaling.
fun torXTypography(scale: TorXTypographyScale): Typography = when (scale) {
    TorXTypographyScale.Compact -> Typography(
        displayLarge = style(56, 63, FontWeight.Bold),
        displayMedium = style(44, 51, FontWeight.Bold),
        displaySmall = style(35, 43, FontWeight.Bold),
        headlineLarge = style(31, 39, FontWeight.SemiBold),
        headlineMedium = style(27, 35, FontWeight.SemiBold),
        headlineSmall = style(23, 31, FontWeight.SemiBold),
        titleLarge = style(21, 27, FontWeight.SemiBold),
        titleMedium = style(15, 23, FontWeight.SemiBold),
        titleSmall = style(13, 19, FontWeight.SemiBold),
        bodyLarge = style(15, 23, FontWeight.Normal),
        bodyMedium = style(13, 19, FontWeight.Normal),
        bodySmall = style(11, 15, FontWeight.Normal),
        labelLarge = style(13, 19, FontWeight.SemiBold),
        labelMedium = style(11, 15, FontWeight.Medium),
        labelSmall = style(10, 15, FontWeight.Medium)
    )
    TorXTypographyScale.Default -> Typography(
        displayLarge = style(57, 64, FontWeight.Bold),
        displayMedium = style(45, 52, FontWeight.Bold),
        displaySmall = style(36, 44, FontWeight.Bold),
        headlineLarge = style(32, 40, FontWeight.SemiBold),
        headlineMedium = style(28, 36, FontWeight.SemiBold),
        headlineSmall = style(24, 32, FontWeight.SemiBold),
        titleLarge = style(22, 28, FontWeight.SemiBold),
        titleMedium = style(16, 24, FontWeight.SemiBold),
        titleSmall = style(14, 20, FontWeight.SemiBold),
        bodyLarge = style(16, 24, FontWeight.Normal),
        bodyMedium = style(14, 20, FontWeight.Normal),
        bodySmall = style(12, 16, FontWeight.Normal),
        labelLarge = style(14, 20, FontWeight.SemiBold),
        labelMedium = style(12, 16, FontWeight.Medium),
        labelSmall = style(11, 16, FontWeight.Medium)
    )
    TorXTypographyScale.Large -> Typography(
        displayLarge = style(58, 67, FontWeight.Bold),
        displayMedium = style(46, 55, FontWeight.Bold),
        displaySmall = style(37, 47, FontWeight.Bold),
        headlineLarge = style(33, 43, FontWeight.SemiBold),
        headlineMedium = style(29, 39, FontWeight.SemiBold),
        headlineSmall = style(25, 35, FontWeight.SemiBold),
        titleLarge = style(23, 31, FontWeight.SemiBold),
        titleMedium = style(18, 28, FontWeight.SemiBold),
        titleSmall = style(16, 24, FontWeight.SemiBold),
        bodyLarge = style(18, 28, FontWeight.Normal),
        bodyMedium = style(16, 24, FontWeight.Normal),
        bodySmall = style(14, 20, FontWeight.Normal),
        labelLarge = style(16, 24, FontWeight.SemiBold),
        labelMedium = style(14, 20, FontWeight.Medium),
        labelSmall = style(13, 20, FontWeight.Medium)
    )
    TorXTypographyScale.Accessibility -> Typography(
        displayLarge = style(59, 68, FontWeight.Bold),
        displayMedium = style(47, 56, FontWeight.Bold),
        displaySmall = style(38, 48, FontWeight.Bold),
        headlineLarge = style(34, 44, FontWeight.SemiBold),
        headlineMedium = style(30, 40, FontWeight.SemiBold),
        headlineSmall = style(26, 36, FontWeight.SemiBold),
        titleLarge = style(24, 32, FontWeight.SemiBold),
        titleMedium = style(20, 30, FontWeight.SemiBold),
        titleSmall = style(18, 26, FontWeight.SemiBold),
        bodyLarge = style(20, 30, FontWeight.Normal),
        bodyMedium = style(18, 26, FontWeight.Normal),
        bodySmall = style(16, 22, FontWeight.Normal),
        labelLarge = style(18, 26, FontWeight.SemiBold),
        labelMedium = style(16, 22, FontWeight.Medium),
        labelSmall = style(15, 22, FontWeight.Medium)
    )
}

private fun style(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = lineHeight.sp
)
