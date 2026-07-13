package com.torxone.app.ui.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a red border if the composable's dimensions are smaller than the recommended minimum touch target size (48dp).
 */
fun Modifier.auditTouchTargets(minTargetSize: Dp = 48.dp): Modifier = composed {
    if (true) {
        val minTargetSizePx = with(LocalDensity.current) { minTargetSize.toPx() }
        this.drawWithContent {
            drawContent()
            if (size.width < minTargetSizePx || size.height < minTargetSizePx) {
                drawRect(
                    color = Color.Red.copy(alpha = 0.5f),
                    topLeft = Offset.Zero,
                    size = size,
                    style = Stroke(width = 4.dp.toPx())
                )
            }
        }
    } else {
        this
    }
}

/**
 * Draws a border around the component to help visualize padding and bounds alignment.
 */
fun Modifier.auditPadding(color: Color = Color.Magenta): Modifier = composed {
    if (true) {
        this.drawWithContent {
            drawContent()
            drawRect(
                color = color.copy(alpha = 0.5f),
                topLeft = Offset.Zero,
                size = size,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    } else {
        this
    }
}
