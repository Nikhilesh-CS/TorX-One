package com.torxone.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.connection.ConnectionUxSnapshot

@Composable
fun ConnectionDashboardDialog(
    snapshot: ConnectionUxSnapshot,
    onDismiss: () -> Unit,
    onVerifyIdentity: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onToggleSendingPaused: (() -> Unit)? = null
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connection") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(snapshot.headline, style = MaterialTheme.typography.titleMedium)
                Text(snapshot.detail, style = MaterialTheme.typography.bodySmall)
                Text(snapshot.internet)
                HorizontalDivider()
                Text("Current paths", style = MaterialTheme.typography.titleSmall)
                snapshot.paths.forEach { path ->
                    Column {
                        Text(path.name, style = MaterialTheme.typography.labelLarge)
                        Text(path.status, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
                Text("Identity", style = MaterialTheme.typography.titleSmall)
                Text(snapshot.identity)
                Text(snapshot.session, style = MaterialTheme.typography.bodySmall)
                onVerifyIdentity?.let { callback -> TextButton(onClick = callback) { Text("View safety number") } }
                Text("Pending messages: ${snapshot.pendingDeliveries}")
                if (snapshot.pendingControlDeliveries > 0) Text("Background controls: ${snapshot.pendingControlDeliveries}",
                    style = MaterialTheme.typography.bodySmall)
                onToggleSendingPaused?.let { action -> TextButton(onClick = action) {
                    Text(if (snapshot.sendingPaused) "Resume sending" else "Pause sending")
                } }
                Text("Retry keeps the same message and encrypted outbox item.", style = MaterialTheme.typography.bodySmall)
                snapshot.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("These are current paths, not the transport history of earlier messages.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (onRetry != null && !snapshot.sendingPaused && snapshot.pendingDeliveries > 0) {
                TextButton(onClick = onRetry) { Text("Retry queued messages") }
            }
        })
}

/** One compact, actionable status; protocol details stay inside the connection dashboard. */
@Composable
fun ConnectionStatusBanner(snapshot: ConnectionUxSnapshot, onOpenConnection: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()
        .clickable(onClick = onOpenConnection)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(snapshot.headline, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            if (snapshot.pendingDeliveries > 0) Text("${snapshot.pendingDeliveries} pending",
                style = MaterialTheme.typography.labelMedium)
        }
    }
}
