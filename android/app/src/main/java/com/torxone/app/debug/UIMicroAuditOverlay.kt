package com.torxone.app.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

object UIMicroAuditController {
    val isOverlayEnabled = MutableStateFlow(false)
}

@Composable
fun UIMicroAuditOverlay(
    content: @Composable () -> Unit
) {
    val isEnabled by UIMicroAuditController.isOverlayEnabled.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        
        if (isEnabled) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stepPx = 8.dp.toPx()
                val width = size.width
                val height = size.height

                var x = 0f
                while (x < width) {
                    drawLine(
                        color = Color.Red.copy(alpha = 0.15f),
                        start = Offset(x, 0f),
                        end = Offset(x, height),
                        strokeWidth = 1f
                    )
                    x += stepPx
                }

                var y = 0f
                while (y < height) {
                    drawLine(
                        color = Color.Blue.copy(alpha = 0.15f),
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1f
                    )
                    y += stepPx
                }
            }
        }
    }
}

