package com.torxone.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

object AstraMotion {
    object Durations {
        const val Short = 100
        const val Medium = 200
        const val Long = 300
    }

    object Easing {
        val Standard: androidx.compose.animation.core.Easing = FastOutSlowInEasing
        val Decelerate: androidx.compose.animation.core.Easing = LinearOutSlowInEasing
        val Emphasized: androidx.compose.animation.core.Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
        
        fun <T> springStandard() = spring<T>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
        
        fun <T> springBouncy() = spring<T>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    }
}
