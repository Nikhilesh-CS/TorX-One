package com.astramesh.app.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.astramesh.app.engine.MessagePayload
import com.astramesh.app.ui.theme.AccentViolet
import com.astramesh.app.ui.theme.CardSurface
import com.astramesh.app.ui.theme.DimGray
import com.astramesh.app.ui.theme.MutedGray
import com.astramesh.app.ui.theme.SoftWhite
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import java.io.File
import java.io.FileInputStream

@Composable
fun MediaContent(message: MessagePayload, isSent: Boolean) {
    when (message.messageType) {
        "IMAGE" -> ImageBubble(message, isSent)
        "VIDEO" -> VideoBubble(message, isSent)
        "AUDIO", "VOICE" -> AudioBubble(message, isSent)
        "DOCUMENT", "APK" -> FileBubble(message, isSent)
        "TEXT" -> Text(
            message.text,
            fontSize = 16.sp,
            color = if (isSent) Color.White else SoftWhite,
            lineHeight = 24.sp
        )
        else -> Text(
            "Unsupported message type.\nPlease update AstraMesh.",
            fontSize = 14.sp,
            color = if (isSent) Color.White.copy(alpha = 0.8f) else Color(0xFFFFB74D), // Warning color
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ImageBubble(message: MessagePayload, isSent: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showViewer by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(enabled = message.localUri != null) { showViewer = true },
        contentAlignment = Alignment.Center
    ) {
        if (message.localUri != null) {
            AsyncImage(
                model = File(message.localUri),
                contentDescription = "Image attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            MediaUnavailablePlaceholder(
                icon = { Icon(Icons.Rounded.Image, contentDescription = null, tint = MutedGray, modifier = Modifier.size(42.dp)) },
                title = "Image",
                subtitle = mediaStatusText(message)
            )
        }
        
        val progress = message.transferProgress
        if (progress != null && progress < 100) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { progress / 100f },
                    color = AccentViolet,
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }

    if (showViewer && message.localUri != null) {
        FullscreenImageViewer(
            message = message,
            onDismiss = { showViewer = false }
        )
    }
}

@Composable
fun VideoBubble(message: MessagePayload, isSent: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(enabled = message.localUri != null) { openAttachment(context, message) },
        contentAlignment = Alignment.Center
    ) {
        if (message.localUri != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play video", tint = Color.White, modifier = Modifier.size(38.dp))
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(message.fileName ?: "Video", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                Text(formatFileSize(message.fileSize), color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
            }
        } else {
            MediaUnavailablePlaceholder(
                icon = { Icon(Icons.Rounded.VideoFile, contentDescription = null, tint = MutedGray, modifier = Modifier.size(42.dp)) },
                title = "Video",
                subtitle = mediaStatusText(message)
            )
        }
    }
}

@Composable
fun AudioBubble(message: MessagePayload, isSent: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    Row(
        modifier = Modifier
            .widthIn(min = 220.dp, max = 280.dp)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                val file = message.localUri?.let { File(it) }
                if (file == null || !file.exists()) {
                    Toast.makeText(context, "Audio file missing", Toast.LENGTH_SHORT).show()
                    return@IconButton
                }
                try {
                    if (isPlaying) {
                        player?.pause()
                        isPlaying = false
                    } else {
                        val existing = player
                        if (existing == null) {
                            player = MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                setOnCompletionListener { isPlaying = false }
                                prepare()
                                start()
                            }
                        } else {
                            existing.start()
                        }
                        isPlaying = true
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Cannot play audio", Toast.LENGTH_SHORT).show()
                    player?.release()
                    player = null
                    isPlaying = false
                }
            },
            modifier = Modifier.background(if (isSent) Color.White.copy(alpha = 0.2f) else CardSurface, RoundedCornerShape(24.dp))
        ) {
            Icon(Icons.Rounded.PlayArrow, "Play", tint = if (isSent) Color.White else AccentViolet)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (message.messageType == "VOICE") "Voice Note" else (message.fileName ?: "Audio"), color = if (isSent) Color.White else SoftWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(8.dp))
            AudioWaveformStub(isSent = isSent, isPlaying = isPlaying)
            Text(
                text = if (message.localUri == null) mediaStatusText(message) else if (isPlaying) "Playing" else "Tap to play",
                color = if (isSent) Color.White.copy(alpha = 0.72f) else MutedGray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun FileBubble(message: MessagePayload, isSent: Boolean) {
    val isApk = message.messageType == "APK"
    val context = androidx.compose.ui.platform.LocalContext.current
    
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .padding(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(if (isSent) Color.White.copy(alpha = 0.2f) else CardSurface, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Description, "File", tint = if (isSent) Color.White else AccentViolet, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(message.fileName ?: if (isApk) "App Package" else "Document", color = if (isSent) Color.White else SoftWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${fileExtensionLabel(message.fileName, message.mimeType)} • ${formatFileSize(message.fileSize)}", color = if (isSent) Color.White.copy(alpha = 0.8f) else MutedGray, fontSize = 12.sp)
            }
        }
        
        if (isApk) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("⚠️ Security Warning: Install at your own risk.", color = Color(0xFFFFB74D), fontSize = 11.sp, lineHeight = 14.sp)
        }
        
        if (message.localUri != null && message.transferProgress == 100) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { 
                    openAttachment(context, message)
                }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (isApk) "INSTALL" else "OPEN", color = if (isSent) Color.White else AccentViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { 
                    saveAttachmentToDownloads(context, message)
                }, contentPadding = PaddingValues(0.dp)) {
                    Text("SAVE", color = if (isSent) Color.White else AccentViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = {
                    message.localUri?.let { shareAttachment(context, it, message.mimeType, message.fileName ?: "Share file") }
                }, contentPadding = PaddingValues(0.dp)) {
                    Text("SHARE", color = if (isSent) Color.White else AccentViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            val progress = message.transferProgress
            if (progress != null && progress < 100) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 8.dp), color = if (isSent) Color.White else AccentViolet, trackColor = DimGray)
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = if (isSent) Color.White.copy(alpha = 0.8f) else MutedGray, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(mediaStatusText(message), color = if (isSent) Color.White.copy(alpha = 0.8f) else MutedGray, fontSize = 12.sp)
                }
            }
        }
    }

}

@Composable
private fun AudioWaveformStub(isSent: Boolean, isPlaying: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val color = if (isSent) Color.White.copy(alpha = 0.84f) else AccentViolet
        repeat(18) { index ->
            val height = if (isPlaying) {
                listOf(8, 16, 11, 22, 14, 19)[index % 6]
            } else {
                listOf(7, 12, 9, 17, 10, 14)[index % 6]
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = if (index < 5 && isPlaying) 1f else 0.48f))
            )
        }
    }
}

@Composable
private fun MediaUnavailablePlaceholder(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        icon()
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, color = SoftWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(subtitle, color = MutedGray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun mediaStatusText(message: MessagePayload): String {
    val progress = message.transferProgress
    return when {
        progress != null && progress in 0..99 -> "Receiving... $progress%"
        message.localUri.isNullOrBlank() -> "Waiting for media data"
        else -> "Tap to open"
    }
}

private fun formatFileSize(bytes: Long?): String {
    val value = bytes ?: 0L
    return when {
        value <= 0L -> "Size unknown"
        value < 1024L * 1024L -> "${value / 1024L} KB"
        value < 1024L * 1024L * 1024L -> String.format("%.1f MB", value / (1024f * 1024f))
        else -> String.format("%.1f GB", value / (1024f * 1024f * 1024f))
    }
}

private fun fileExtensionLabel(fileName: String?, mimeType: String?): String {
    val ext = fileName?.substringAfterLast('.', missingDelimiterValue = "")?.uppercase()?.takeIf { it.isNotBlank() }
    return ext ?: mimeType?.substringAfterLast('/')?.uppercase()?.takeIf { it.isNotBlank() } ?: "FILE"
}

@Composable
private fun FullscreenImageViewer(message: MessagePayload, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
        ) {
            AsyncImage(
                model = message.localUri?.let { File(it) },
                contentDescription = message.fileName ?: "Image attachment",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.48f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close image", tint = Color.White)
                    }
                    Text(
                        text = message.fileName ?: "Image",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { saveAttachmentToDownloads(context, message) }) {
                        Icon(Icons.Rounded.SaveAlt, contentDescription = "Save image", tint = Color.White)
                    }
                    IconButton(onClick = {
                        message.localUri?.let { shareAttachment(context, it, message.mimeType ?: "image/*", message.fileName ?: "Share image") }
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share image", tint = Color.White)
                    }
                }
            }
        }
    }
}

fun openAttachment(context: Context, message: MessagePayload) {
    val file = message.localUri?.let { File(it) }
    if (file == null || !file.exists()) {
        Toast.makeText(context, "File missing", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, message.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
    }
}

fun shareAttachment(context: Context, localUri: String, mimeType: String?, title: String) {
    val file = File(localUri)
    if (!file.exists()) {
        Toast.makeText(context, "File missing", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share file", Toast.LENGTH_SHORT).show()
    }
}

fun saveAttachmentToDownloads(context: Context, message: MessagePayload) {
    val source = message.localUri?.let { File(it) }
    if (source == null || !source.exists()) {
        Toast.makeText(context, "File missing", Toast.LENGTH_SHORT).show()
        return
    }

    val fileName = message.fileName ?: source.name
    val mimeType = message.mimeType ?: "application/octet-stream"
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create download")
            context.contentResolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            source.copyTo(File(downloads, fileName), overwrite = true)
        }
        Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not save file", Toast.LENGTH_SHORT).show()
    }
}
