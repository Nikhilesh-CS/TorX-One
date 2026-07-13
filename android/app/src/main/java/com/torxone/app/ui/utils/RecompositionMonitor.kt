package com.torxone.app.ui.utils

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/**
 * A modifier to track recomposition counts in debug mode.
 * Draws a small overlay indicating how many times the composable has recomposed.
 */
fun Modifier.recompositionMonitor(): Modifier = composed {
    val textMeasurer = rememberTextMeasurer()
    var recomposeCount by remember { mutableLongStateOf(0L) }

    SideEffect {
        recomposeCount++
    }

    this.drawWithCache {
        onDrawWithContent {
            drawContent()
            val text = recomposeCount.toString()
            val style = TextStyle(color = Color.Red, fontSize = 12.sp)
            val textLayoutResult = textMeasurer.measure(text, style)
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset.Zero,
                size = Size(textLayoutResult.size.width.toFloat(), textLayoutResult.size.height.toFloat())
            )
            drawText(
                textMeasurer = textMeasurer,
                text = text,
                style = style,
                topLeft = Offset.Zero
            )
        }
    }
}
