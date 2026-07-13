package com.torxone.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import com.torxone.app.ui.theme.AstraMotion

object NavigationStandards {
    
    val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(
                durationMillis = AstraMotion.Durations.Long,
                easing = AstraMotion.Easing.Emphasized
            )
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = AstraMotion.Durations.Medium,
                easing = AstraMotion.Easing.Standard
            )
        )
    }

    val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(
            targetOffsetX = { -it / 3 },
            animationSpec = tween(
                durationMillis = AstraMotion.Durations.Long,
                easing = AstraMotion.Easing.Emphasized
            )
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = AstraMotion.Durations.Medium,
                easing = AstraMotion.Easing.Standard
            )
        )
    }

    val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(
            initialOffsetX = { -it / 3 },
            animationSpec = tween(
                durationMillis = AstraMotion.Durations.Long,
                easing = AstraMotion.Easing.Emphasized
            )
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = AstraMotion.Durations.Medium,
                easing = AstraMotion.Easing.Standard
            )
        )
    }

    val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(
                durationMillis = AstraMotion.Durations.Long,
                easing = AstraMotion.Easing.Emphasized
            )
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = AstraMotion.Durations.Medium,
                easing = AstraMotion.Easing.Standard
            )
        )
    }
}
