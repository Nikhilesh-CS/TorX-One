package com.torxone.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.torxone.app.media.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Picker handles stay local and never enter the outbox until the explicit Send callback. */
data class PendingAttachment(val name: String, val mimeType: String, val type: MediaType,
    val uri: Uri? = null, val cameraBitmap: Bitmap? = null)

@Composable
fun AttachmentPreview(attachment: PendingAttachment, busy: Boolean, error: String?, onCancel: () -> Unit, onSend: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(attachment.cameraBitmap, attachment) {
        if (attachment.type == MediaType.IMAGE && attachment.uri != null) value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(attachment.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 1600 || options.outHeight / options.inSampleSize > 1600) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                context.contentResolver.openInputStream(attachment.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
        }
    }
    var video by remember(attachment) { mutableStateOf<VideoView?>(null) }
    DisposableEffect(attachment) { onDispose { video?.stopPlayback() } }
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text("Send attachment") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), attachment.name,
                Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
            else if (attachment.type == MediaType.VIDEO && attachment.uri != null) AndroidView(
                factory = { ctx -> VideoView(ctx).also { view -> video = view; view.setVideoURI(attachment.uri)
                    view.setOnPreparedListener { view.seekTo(1) } } }, modifier = Modifier.fillMaxWidth().height(200.dp))
            Text(attachment.name, style = MaterialTheme.typography.bodyMedium)
            Text("Only sent when you tap Send", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = onSend, enabled = !busy) { Text(if (busy) "Preparing…" else "Send attachment") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } })
}
