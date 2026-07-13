package com.torxone.app.ui.utils

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

enum class HapticPattern {
    Send,
    Error,
    Success,
    Click
}

class AstraHaptics(
    private val composeHaptic: HapticFeedback,
    private val view: View
) {
    fun trigger(pattern: HapticPattern) {
        when (pattern) {
            HapticPattern.Send -> view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            HapticPattern.Error -> view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            HapticPattern.Success -> view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            HapticPattern.Click -> composeHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
}

@Composable
fun rememberAstraHaptics(): AstraHaptics {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    return remember(haptic, view) {
        AstraHaptics(haptic, view)
    }
}
