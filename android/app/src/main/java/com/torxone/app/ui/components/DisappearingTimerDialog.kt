package com.torxone.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torxone.app.privacy.ConversationSecurityPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DisappearingTimerDialog(policy: ConversationSecurityPolicy?, onSave: suspend (Long?) -> Unit, onDismiss: () -> Unit) {
    val presets = listOf("Off" to null, "5 minutes" to 300_000L, "1 hour" to 3_600_000L,
        "24 hours" to 86_400_000L, "7 days" to 604_800_000L, "30 days" to 2_592_000_000L)
    var selected by remember { mutableStateOf(policy?.disappearAfterMs) }
    var custom by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Disappearing messages") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Applies to new messages you send. Every recipient must support this feature. Received messages use their sender's timer.")
            presets.forEach { (name, duration) -> FilterChip(selected == duration && !custom,
                onClick = { selected = duration; custom = false }, enabled = !saving, label = { Text(name) }) }
            FilterChip(custom, { custom = true }, enabled = !saving, label = { Text("Custom") })
            if (custom) OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(6) },
                label = { Text("Minutes (1–43200)") }, singleLine = true, enabled = !saving)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !saving, onClick = { scope.launch {
            saving = true
            try {
                val duration = if (custom) {
                    val value = minutes.toLongOrNull()
                    require(value != null && value in 1..43_200) { "Choose between 1 and 43200 minutes" }
                    value * 60_000
                } else selected
                onSave(duration); onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Unable to save timer" }
            finally { saving = false }
        } }) { Text(if (saving) "Saving…" else "Save") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}
