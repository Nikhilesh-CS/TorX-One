package com.torxone.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.appearance.ChatAppearance
import com.torxone.app.ui.theme.TorXAccent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

@Composable
fun ChatThemePreview(config: ChatAppearance, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 220.dp).clip(RoundedCornerShape(20.dp))) {
        ChatBackground(config, Modifier.matchParentSize(), preview = true)
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Chat preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PreviewBubble("A quiet place to talk.", outgoing = false, config = config)
            PreviewBubble("Make it feel like you.", outgoing = true, config = config)
        }
    }
}
@Composable
private fun ColumnScope.PreviewBubble(text: String, outgoing: Boolean, config: ChatAppearance) {
    val accent = config.effectiveAccentId?.let { Color(TorXAccent.resolve(it).lightArgb) } ?: MaterialTheme.colorScheme.primary
    val color = if (outgoing) lerp(MaterialTheme.colorScheme.surface, accent, 0.13f) else MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(Modifier.align(if (outgoing) Alignment.End else Alignment.Start).widthIn(max = 240.dp),
        shape = RoundedCornerShape(if (config.bubbleStyle == "COMPACT") 6.dp else 16.dp), color = color,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text("12:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (outgoing) { Spacer(Modifier.width(4.dp)); Icon(Icons.Default.DoneAll, "Read", Modifier.size(14.dp), tint = accent) }
            }
        }
    }
}
