package com.torxone.app.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.theme.TorXAccent

/** Pure presentation: selections use the existing settings persistence callback. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAppearanceControls(
    themeSource: String,
    accentId: String,
    fontSize: String,
    onChange: (String, Any) -> Unit,
    modifier: Modifier = Modifier,
    systemColorsSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Theme colors", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("TORX" to "TorX Brand", "SYSTEM" to "System colors").forEach { (value, label) ->
                FilterChip(selected = themeSource == value, onClick = { onChange("themeSource", value) },
                    modifier = Modifier.heightIn(min = 48.dp), label = { Text(label) })
            }
        }
        Text(
            if (systemColorsSupported) "System colors use your Android wallpaper palette."
            else "System colors require Android 12 or later. This device uses TorX Brand.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Accent color", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (themeSource == "SYSTEM" && systemColorsSupported)
                "Your TorX accent is saved for when you use TorX Brand."
            else "Used for buttons, selected controls and small highlights.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TorXAccent.entries.forEach { accent ->
                FilterChip(selected = accentId == accent.name, onClick = { onChange("accentId", accent.name) },
                    modifier = Modifier.heightIn(min = 48.dp), label = { Text(accent.label) },
                    leadingIcon = {
                        Box(Modifier.size(18.dp).background(Color(accent.lightArgb), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    })
            }
        }
        Text("Text size", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("SMALL" to "Small", "MEDIUM" to "Default", "LARGE" to "Large", "EXTRA_LARGE" to "Extra Large")
                .forEach { (value, label) ->
                    FilterChip(selected = fontSize == value, onClick = { onChange("fontSize", value) },
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text(label) })
                }
        }
        Text("Android text size also applies.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
