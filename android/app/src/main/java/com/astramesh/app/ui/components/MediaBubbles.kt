package com.astramesh.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astramesh.app.data.MessagePayload
import com.astramesh.app.ui.theme.AccentViolet
import com.astramesh.app.ui.theme.CardSurface
import com.astramesh.app.ui.theme.DimGray
import com.astramesh.app.ui.theme.MutedGray
import com.astramesh.app.ui.theme.SoftWhite
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import java.io.File

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
    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.3f)),
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
            Icon(Icons.Rounded.Image, contentDescription = "Image", tint = MutedGray, modifier = Modifier.size(48.dp))
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
}

@Composable
fun VideoBubble(message: MessagePayload, isSent: Boolean) {
    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.VideoFile, contentDescription = "Video", tint = MutedGray, modifier = Modifier.size(48.dp))
        Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(24.dp)))
    }
}

@Composable
fun AudioBubble(message: MessagePayload, isSent: Boolean) {
    Row(
        modifier = Modifier
            .width(200.dp)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { /* Play */ },
            modifier = Modifier.background(if (isSent) Color.White.copy(alpha = 0.2f) else CardSurface, RoundedCornerShape(24.dp))
        ) {
            Icon(Icons.Rounded.PlayArrow, "Play", tint = if (isSent) Color.White else AccentViolet)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(if (message.messageType == "VOICE") "Voice Note" else (message.fileName ?: "Audio"), color = if (isSent) Color.White else SoftWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 8.dp), color = if (isSent) Color.White else AccentViolet, trackColor = DimGray)
        }
    }
}

@Composable
fun FileBubble(message: MessagePayload, isSent: Boolean) {
    val isApk = message.messageType == "APK"
    
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
                Text("${(message.fileSize ?: 0L) / 1024} KB", color = if (isSent) Color.White.copy(alpha = 0.8f) else MutedGray, fontSize = 12.sp)
            }
        }
        
        if (isApk) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("⚠️ Security Warning: Install at your own risk.", color = Color(0xFFFFB74D), fontSize = 11.sp, lineHeight = 14.sp)
        }
        
        if (message.localUri != null && message.transferProgress == 100) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val context = androidx.compose.ui.platform.LocalContext.current
                TextButton(onClick = { 
                    try {
                        val uri = android.net.Uri.parse(message.localUri)
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, message.mimeType ?: "*/*")
                            flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Cannot open file", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (isApk) "INSTALL" else "OPEN", color = if (isSent) Color.White else AccentViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { 
                    android.widget.Toast.makeText(context, "Saved to Downloads", android.widget.Toast.LENGTH_SHORT).show()
                }, contentPadding = PaddingValues(0.dp)) {
                    Text("SAVE", color = if (isSent) Color.White else AccentViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            val progress = message.transferProgress
            if (progress != null && progress < 100) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 8.dp), color = if (isSent) Color.White else AccentViolet, trackColor = DimGray)
            }
        }
    }
}
