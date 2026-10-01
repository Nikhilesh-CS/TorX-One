package com.torxone.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.torxone.app.data.entity.ConversationAppearanceEntity
import kotlinx.coroutines.launch
import com.torxone.app.chat.AppearanceOptions

@Composable
fun ConversationAppearanceDialog(current: ConversationAppearanceEntity,
    onSave: suspend (ConversationAppearanceEntity) -> Unit, onDismiss: () -> Unit) {
    var draft by remember(current.conversationId) { mutableStateOf(current) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Chat appearance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved only on this device")
                Text("Color theme")
                Row { AppearanceOptions.themes.forEach { name ->
                    FilterChip(selected = draft.theme == name, enabled = !saving, onClick = { draft = draft.copy(theme = name) },
                        label = { Text(name.lowercase().replaceFirstChar { it.titlecase() }) })
                } }
                Text("Wallpaper color")
                Row { AppearanceOptions.wallpapers.forEach { name ->
                    FilterChip(selected = (draft.wallpaper ?: "NONE") == name, enabled = !saving,
                        onClick = { draft = draft.copy(wallpaper = name.takeUnless { it == "NONE" }) },
                        label = { Text(name.lowercase().replaceFirstChar { it.titlecase() }) })
                } }
                Text("Bubble shape")
                Row { AppearanceOptions.bubbles.forEach { name ->
                    FilterChip(selected = draft.bubbleStyle == name, enabled = !saving,
                        onClick = { draft = draft.copy(bubbleStyle = name) },
                        label = { Text(name.lowercase().replaceFirstChar { it.titlecase() }) })
                } }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = !saving, onClick = { scope.launch {
                saving = true
                try { onSave(draft); onDismiss() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Unable to save appearance" }
                finally { saving = false }
            } }) { Text(if (saving) "Saving…" else "Save") }
        }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}
