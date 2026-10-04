package com.torxone.app.ui.components

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.torxone.app.chat.MediaUiModel
import com.torxone.app.media.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun openMedia(context: android.content.Context, media: MediaUiModel, onDownload: (() -> Unit)? = null) {
    val source = media.localPath?.let(::File)?.takeIf(File::isFile)
    if (source == null) {
        if (onDownload != null && media.status in setOf(com.torxone.app.media.MediaStatus.QUEUED, com.torxone.app.media.MediaStatus.PAUSED, com.torxone.app.media.MediaStatus.FAILED)) {
            onDownload()
            return
        }
        val message = if (media.status in setOf(com.torxone.app.media.MediaStatus.COMPLETE, com.torxone.app.media.MediaStatus.SENT, com.torxone.app.media.MediaStatus.DELIVERED))
            "This attachment is no longer on this device. Ask the sender to share it again."
        else "${media.fileName}: ${media.status.name.lowercase().replace('_', ' ')}"
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        return
    }
    // Only an explicit Open action grants a reader temporary access to this private file.
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.media", source)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, media.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { Toast.makeText(context, "No app can open this file", Toast.LENGTH_LONG).show() }
}

/** Player state comes from MediaPlayer callbacks and is released on screen/lifecycle exit. */
@Composable
fun AudioPlayback(media: MediaUiModel, modifier: Modifier = Modifier, onDownload: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ready by remember(media.localPath) { mutableStateOf(false) }
    var playing by remember(media.localPath) { mutableStateOf(false) }
    var speed by remember(media.mediaId) { mutableFloatStateOf(1f) }
    var error by remember(media.localPath) { mutableStateOf<String?>(null) }
    val player = remember(media.localPath) { MediaPlayer() }
    val audioManager = remember(context) { context.getSystemService(android.media.AudioManager::class.java) }
    val focus = remember(player) { android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
        .setOnAudioFocusChangeListener { change ->
            if (change != android.media.AudioManager.AUDIOFOCUS_GAIN && playing) {
                runCatching { player.pause() }; playing = false
            }
        }.build() }
    DisposableEffect(player, lifecycle) {
        player.setOnPreparedListener { ready = true }
        player.setOnCompletionListener { playing = false; audioManager.abandonAudioFocusRequest(focus) }
        player.setOnErrorListener { _, _, _ -> ready = false; playing = false; error = "Unable to play audio"; true }
        val path = media.localPath
        if (path != null && File(path).isFile) runCatching {
            player.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setDataSource(path); player.prepareAsync()
        }.onFailure { error = "Unable to play audio" }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && playing) { player.pause(); playing = false; audioManager.abandonAudioFocusRequest(focus) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); audioManager.abandonAudioFocusRequest(focus); player.release() }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (media.localPath == null && onDownload != null && media.status in setOf(com.torxone.app.media.MediaStatus.QUEUED, com.torxone.app.media.MediaStatus.PAUSED, com.torxone.app.media.MediaStatus.FAILED)) {
            TextButton(onClick = onDownload) { Text("Download") }
        }
        IconButton(enabled = ready, onClick = {
            runCatching {
                if (playing) { player.pause(); audioManager.abandonAudioFocusRequest(focus) } else {
                    check(audioManager.requestAudioFocus(focus) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio is in use by another app" }
                    player.playbackParams = player.playbackParams.setSpeed(speed)
                    player.start()
                }
                playing = player.isPlaying
            }.onFailure { error = "Unable to play audio" }
        }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause audio" else "Play audio") }
        Column(Modifier.weight(1f)) {
            Text(media.fileName, maxLines = 1)
            Text(error ?: if (!ready) media.status.name.lowercase().replace('_', ' ') else "Audio ready",
                style = MaterialTheme.typography.labelSmall)
        }
        TextButton(enabled = ready, onClick = {
            speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
            val wasPlaying = player.isPlaying
            runCatching { player.playbackParams = player.playbackParams.setSpeed(speed); if (!wasPlaying) player.pause() }
                .onFailure { error = "Playback speed unavailable" }
        }) { Text("${speed}x") }
    }
}

@Composable
fun MediaViewer(media: MediaUiModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var video by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) video?.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); video?.stopPlayback() }
    }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, media.localPath) {
        if (media.type == MediaType.IMAGE) value = withContext(Dispatchers.IO) {
            runCatching {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(media.localPath, options)
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 2048 || options.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                android.graphics.BitmapFactory.decodeFile(media.localPath, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (media.type == MediaType.VIDEO && media.localPath != null) {
                AndroidView(factory = { ctx -> VideoView(ctx).also { view ->
                    video = view
                    view.setMediaController(MediaController(ctx).apply { setAnchorView(view) })
                    view.setVideoURI(Uri.fromFile(File(media.localPath)))
                    view.setOnPreparedListener { view.start() }
                    view.setOnErrorListener { _, _, _ -> Toast.makeText(context, "Unable to play video", Toast.LENGTH_LONG).show(); true }
                } }, modifier = Modifier.fillMaxSize())
            } else if (bitmap != null) {
                var zoom by remember { mutableFloatStateOf(1f) }
                var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                Image(bitmap!!, media.fileName, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, offset, factor, _ ->
                            zoom = (zoom * factor).coerceIn(1f, 5f); pan = if (zoom == 1f) androidx.compose.ui.geometry.Offset.Zero else pan + offset
                        }
                    }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
            } else Text("Image unavailable on this device", color = Color.White)
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()) {
                Icon(Icons.Default.Close, "Close media", tint = Color.White)
            }
        }
    }
}
