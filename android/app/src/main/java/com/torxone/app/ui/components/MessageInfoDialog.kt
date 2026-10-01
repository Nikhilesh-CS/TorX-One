package com.torxone.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.entity.OutboxEntity
import com.torxone.app.ui.connection.MessageInfoPresentation
import java.text.DateFormat
import java.util.Date

@Composable
fun MessageInfoDialog(
    message: MessageEntity,
    pendingOutbox: List<OutboxEntity> = emptyList(),
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val info = MessageInfoPresentation.from(message, pendingOutbox)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Message info") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                info.rows.forEach { row ->
                    Column {
                        Text(row.label, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.timestamp?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it)) }
                            ?: row.value ?: "Not recorded", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (onRetry != null && info.canRetry) TextButton(onClick = onRetry) { Text("Retry delivery") } })
}
