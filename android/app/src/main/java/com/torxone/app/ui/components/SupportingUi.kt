package com.torxone.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun TorXEmptyState(title: String, icon: ImageVector, modifier: Modifier = Modifier,
    message: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
    }
}

/** Presentation-owned busy/error state; the supplied service remains the operation authority. */
@Stable
class UiActionState(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    fun clearError() { error = null }
    fun run(fallback: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message?.takeIf { it.isNotBlank() } ?: fallback }
            finally { busy = false }
        }
    }
}

@Composable
fun rememberUiActionState(): UiActionState {
    val scope = rememberCoroutineScope()
    return remember(scope) { UiActionState(scope) }
}

/** ChatService expects an absolute expiry, never a duration since epoch. */
fun temporaryMuteUntil(now: Long, durationMillis: Long): Long {
    require(durationMillis > 0)
    return if (now > Long.MAX_VALUE - durationMillis) Long.MAX_VALUE else now + durationMillis
}
