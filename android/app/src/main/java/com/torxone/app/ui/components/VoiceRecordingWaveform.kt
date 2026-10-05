package com.torxone.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Uses the recorder's real bounded amplitude history; no synthetic audio data. */
@Composable
fun VoiceRecordingWaveform(levels: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.height(32.dp).semantics { contentDescription = "Recording waveform" }) {
        val samples = levels.takeLast(30)
        if (samples.isNotEmpty()) {
            val step = size.width / samples.size
            samples.forEachIndexed { index, value ->
                val amplitude = (if (value.isFinite()) value else 0f).coerceIn(0f, 1f)
                val height = (size.height * amplitude).coerceAtLeast(2.dp.toPx())
                val x = step * (index + 0.5f)
                drawLine(color, Offset(x, (size.height - height) / 2f), Offset(x, (size.height + height) / 2f),
                    strokeWidth = minOf(3.dp.toPx(), step * 0.6f), cap = StrokeCap.Round)
            }
        }
    }
}
