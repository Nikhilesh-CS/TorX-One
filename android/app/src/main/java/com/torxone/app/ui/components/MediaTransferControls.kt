package com.torxone.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.torxone.app.chat.MediaUiModel
import com.torxone.app.media.MediaStatus

/** Only offer controls whose consumer is wired; completed/cancelled transfers are terminal. */
@Composable
fun MediaTransferControls(media: MediaUiModel, modifier: Modifier = Modifier,
                          onPause: ((String) -> Unit)? = null,
                          onResume: ((String) -> Unit)? = null,
                          onCancel: ((String) -> Unit)? = null) {
    val active = media.status in setOf(MediaStatus.UPLOADING, MediaStatus.DOWNLOADING)
    val paused = media.status == MediaStatus.PAUSED
    val retryable = media.status in setOf(MediaStatus.QUEUED, MediaStatus.FAILED)
    if (!active && !paused && !retryable) return
    if (onPause == null && onResume == null && onCancel == null) return
    val colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (active && onPause != null) TextButton(onClick = { onPause(media.mediaId) }, colors = colors, modifier = Modifier.weight(1f)) {
            Text("Pause transfer")
        }
        if ((paused || retryable) && onResume != null) TextButton(onClick = { onResume(media.mediaId) }, colors = colors, modifier = Modifier.weight(1f)) {
            Text(if (paused) "Resume transfer" else "Retry transfer")
        }
        if (onCancel != null) TextButton(onClick = { onCancel(media.mediaId) }, colors = colors, modifier = Modifier.weight(1f)) {
            Text("Cancel transfer")
        }
    }
}
