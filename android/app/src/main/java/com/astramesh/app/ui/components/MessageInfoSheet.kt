package com.astramesh.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astramesh.app.engine.MessageLifecycleState
import com.astramesh.app.engine.MessagePayload
import com.astramesh.app.engine.TransportType
import com.astramesh.app.ui.theme.AstraTheme
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInfoSheet(
    message: MessagePayload,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AstraTheme.colors.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AstraTheme.spacing.large)
                .padding(bottom = AstraTheme.spacing.extraLarge)
        ) {
            Text(
                text = "Message Info",
                style = AstraTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AstraTheme.colors.onSurface
            )
            Spacer(modifier = Modifier.height(AstraTheme.spacing.large))

            val timeFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

            // Status Timeline
            InfoRow(
                icon = Icons.Rounded.CheckCircle,
                title = "Status",
                value = message.lifecycleState.name
            )
            InfoRow(
                icon = Icons.Rounded.AccessTime,
                title = "Sent",
                value = timeFormat.format(Date(message.timestamp))
            )
            
            Divider(color = AstraTheme.colors.surfaceVariant, modifier = Modifier.padding(vertical = AstraTheme.spacing.medium))

            // Transport Details
            InfoRow(
                icon = Icons.Rounded.Router,
                title = "Transport Route",
                value = when (message.transportType) {
                    TransportType.BLUETOOTH -> "Bluetooth Direct (P2P)"
                    TransportType.WIFI_DIRECT -> "Wi-Fi Direct"
                    TransportType.TOR -> "Tor Onion Service"
                    TransportType.AUTO -> "Auto-negotiated"
                    TransportType.NONE -> "Offline Queue"
                }
            )
            InfoRow(
                icon = Icons.Rounded.Security,
                title = "Encryption",
                value = if (message.isEncrypted) "XChaCha20-Poly1305 (E2EE)" else "Plaintext"
            )

            Divider(color = AstraTheme.colors.surfaceVariant, modifier = Modifier.padding(vertical = AstraTheme.spacing.medium))

            // Technical Details
            val packetSize = message.text.toByteArray().size + (message.fileSize ?: 0L) + 256L // rough header est
            InfoRow(
                icon = Icons.Rounded.DataUsage,
                title = "Packet Size",
                value = "${packetSize} Bytes"
            )
            if (message.retryCount > 0) {
                InfoRow(
                    icon = Icons.Rounded.Replay,
                    title = "Delivery Retries",
                    value = "${message.retryCount} times"
                )
            }
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AstraTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AstraTheme.colors.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = AstraTheme.colors.primary)
        }
        Spacer(modifier = Modifier.width(AstraTheme.spacing.medium))
        Column {
            Text(title, style = AstraTheme.typography.labelMedium, color = AstraTheme.colors.onSurfaceVariant)
            Text(value, style = AstraTheme.typography.bodyMedium, color = AstraTheme.colors.onSurface, fontWeight = FontWeight.SemiBold)
        }
    }
}
