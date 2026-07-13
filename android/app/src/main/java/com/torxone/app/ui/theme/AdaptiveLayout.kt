package com.torxone.app.ui.theme

import android.app.Activity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberWindowSizeClass(): WindowSizeClass? {
    val context = LocalContext.current
    return if (context is Activity) {
        calculateWindowSizeClass(activity = context)
    } else {
        null
    }
}
