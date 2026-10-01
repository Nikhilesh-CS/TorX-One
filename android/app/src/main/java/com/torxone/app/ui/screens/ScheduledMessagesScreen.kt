package com.torxone.app.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.torxone.app.scheduling.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledMessagesScreen(service: ScheduledMessageService, conversationId: String, onBack: () -> Unit,
    initialText: String = "", initialReplyToMessageId: String? = null, onCreated: () -> Unit = {}) {
    val rows by remember(service, conversationId) { service.observe(conversationId) }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ScheduledMessageEntity?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(initialText.isNotBlank()) }
    var text by rememberSaveable { mutableStateOf(initialText) }
    var at by rememberSaveable { mutableLongStateOf(System.currentTimeMillis() + 60 * 60 * 1000) }
    var reply by rememberSaveable { mutableStateOf(initialReplyToMessageId) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("Scheduled messages") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }, actions = { TextButton(onClick = {
        editing = null; text = ""; reply = null; at = System.currentTimeMillis() + 60 * 60 * 1000; editorOpen = true
    }) { Text("New") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text("Messages queue when due and your identity is unlocked. Android battery saving may delay sending.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (rows.isEmpty()) Text("No scheduled messages", modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.scheduleId }) { row ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(formatter.format(Date(row.scheduledAt)))
                            Text(when (row.state) {
                                ScheduledMessageState.SENT -> "Queued for delivery"
                                ScheduledMessageState.CANCELED -> "Canceled"
                                ScheduledMessageState.SENDING -> "Queuing message…"
                                ScheduledMessageState.FAILED -> "Needs attention"
                                else -> "Scheduled"
                            }, style = MaterialTheme.typography.labelMedium)
                            if (row.draftPayload.isNotBlank()) Text(row.draftPayload)
                            row.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (row.state in listOf(ScheduledMessageState.PENDING, ScheduledMessageState.FAILED)) {
                                Row {
                                    TextButton(enabled = !busy, onClick = {
                                        editing = row; text = row.draftPayload; reply = row.replyToMessageId
                                        at = maxOf(row.scheduledAt, System.currentTimeMillis() + 60_000); editorOpen = true
                                    }) { Text(if (row.state == ScheduledMessageState.FAILED) "Edit / retry" else "Edit") }
                                    TextButton(enabled = !busy, onClick = {
                                        busy = true; error = null
                                        scope.launch {
                                            try { service.cancel(row.scheduleId, row.generation) }
                                            catch (e: CancellationException) { throw e }
                                            catch (e: Exception) { error = e.message ?: "Could not cancel" }
                                            finally { busy = false }
                                        }
                                    }) { Text("Cancel") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (editorOpen) AlertDialog(onDismissRequest = { if (!busy) editorOpen = false },
        title = { Text(if (editing == null) "Schedule message" else "Edit schedule") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text("Message") }, enabled = !busy, minLines = 3)
                Text(formatter.format(Date(at)), modifier = Modifier.padding(top = 12.dp))
                Row {
                    TextButton(enabled = !busy, onClick = {
                        val selected = Calendar.getInstance().apply { timeInMillis = at }
                        DatePickerDialog(context, { _, year, month, day ->
                            at = Calendar.getInstance().apply {
                                timeInMillis = at; set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
                            }.timeInMillis
                        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).show()
                    }) { Text("Date") }
                    TextButton(enabled = !busy, onClick = {
                        val selected = Calendar.getInstance().apply { timeInMillis = at }
                        TimePickerDialog(context, { _, hour, minute ->
                            at = Calendar.getInstance().apply {
                                timeInMillis = at; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                        }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
                    }) { Text("Time") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = !busy && text.isNotBlank(), onClick = {
            busy = true; error = null
            val current = editing
            scope.launch {
                try {
                    if (current == null) {
                        service.create(conversationId, text, at, reply)
                        onCreated()
                    }
                    else service.edit(current.scheduleId, current.generation, text, at, reply)
                    editorOpen = false
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Could not schedule message" }
                finally { busy = false }
            }
        }) { Text(if (busy) "Saving…" else "Save") } }, dismissButton = {
            TextButton(enabled = !busy, onClick = { editorOpen = false }) { Text("Close") }
        })
}
