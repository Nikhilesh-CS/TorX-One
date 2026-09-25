package com.torxone.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.torxone.app.data.entity.MessageEntity
import java.text.SimpleDateFormat
import java.util.*

/**
 * Diagnostic Delivery Inspector.
 * Displays precise timing and lifecycle transitions for a message.
 */
@Composable
fun DeliveryInspectorDialog(
    message: MessageEntity,
    onDismiss: () -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Delivery Inspector: ${message.logicalMessageId.take(8)}",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InspectorRow("Message ID", message.logicalMessageId)
                InspectorRow("Direction", message.direction.name)
                InspectorRow("Status", message.status)
                InspectorRow("Type", message.type)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                InspectorRow("Created", timeFormat.format(Date(message.createdAt)))
                message.receivedAt?.let {
                    InspectorRow("Received", timeFormat.format(Date(it)))
                }
                message.deliveredAt?.let {
                    InspectorRow("Delivered (ACK)", timeFormat.format(Date(it)))
                }
                message.readAt?.let {
                    InspectorRow("Read", timeFormat.format(Date(it)))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}
