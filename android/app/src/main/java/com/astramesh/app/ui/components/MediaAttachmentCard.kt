package com.astramesh.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.astramesh.app.engine.MessagePayload
import com.astramesh.app.ui.theme.AstraTheme
import java.io.File

@Composable
fun MediaAttachmentCard(
    message: MessagePayload,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val cornerRadius = AstraTheme.spacing.medium
    
    Card(
        modifier = modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .padding(bottom = if (message.text.isNotBlank()) AstraTheme.spacing.small else 0.dp),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        onClick = onClick
    ) {
        when (message.messageType) {
            "IMAGE" -> ImageAttachmentCard(message)
            "VIDEO" -> VideoAttachmentCard(message)
            "AUDIO", "VOICE" -> AudioAttachmentCard(message)
            else -> DocumentAttachmentCard(message) // PDF, APK, DOC, etc.
        }
    }
}

@Composable
private fun ImageAttachmentCard(message: MessagePayload) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(AstraTheme.spacing.medium))
            .background(Color.DarkGray.copy(alpha = 0.3f))
    ) {
        val model = message.localUri?.let { File(it) } ?: message.thumbnailUri?.let { File(it) }
        
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(model)
                .crossfade(true)
                .build(),
            contentDescription = "Image attachment",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 300.dp)
        )

        // Progress Overlay
        TransferProgressOverlay(message)
    }
}

@Composable
private fun VideoAttachmentCard(message: MessagePayload) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(AstraTheme.spacing.medium))
            .background(Color.DarkGray.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        val model = message.localUri?.let { File(it) } ?: message.thumbnailUri?.let { File(it) }
        
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(model)
                .crossfade(true)
                .build(),
            contentDescription = "Video thumbnail",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 300.dp)
        )
        
        // Play Button Overlay
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play Video",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
        
        // Duration/Size badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(AstraTheme.spacing.small)
                .clip(RoundedCornerShape(AstraTheme.spacing.small))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = formatFileSize(message.fileSize),
                color = Color.White,
                style = AstraTheme.typography.labelSmall
            )
        }
        
        TransferProgressOverlay(message)
    }
}

@Composable
private fun AudioAttachmentCard(message: MessagePayload) {
    var isPlaying by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AstraTheme.spacing.medium))
            .background(Color.DarkGray.copy(alpha = 0.1f))
            .padding(AstraTheme.spacing.small)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AstraTheme.colors.primary)
                    .clickable { isPlaying = !isPlaying },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = AstraTheme.colors.onPrimary)
            }
            Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
            Column(modifier = Modifier.weight(1f)) {
                // Fake Waveform
                Row(
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val heights = listOf(0.4f, 0.7f, 0.3f, 1f, 0.5f, 0.2f, 0.8f, 0.4f, 0.9f, 0.3f, 0.6f, 0.2f)
                    heights.forEachIndexed { index, h ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(h)
                                .clip(CircleShape)
                                .background(if (index < 4) AstraTheme.colors.primary else AstraTheme.colors.onSurfaceVariant.copy(alpha = 0.3f))
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0:04 / 0:12", style = AstraTheme.typography.labelSmall, color = AstraTheme.colors.onSurfaceVariant)
                    Text("${playbackSpeed}x", style = AstraTheme.typography.labelSmall, color = AstraTheme.colors.primary, modifier = Modifier.clickable {
                        playbackSpeed = if (playbackSpeed == 1f) 1.5f else if (playbackSpeed == 1.5f) 2f else 1f
                    })
                }
            }
        }
    }
}

@Composable
private fun DocumentAttachmentCard(message: MessagePayload) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AstraTheme.spacing.medium))
            .background(Color.DarkGray.copy(alpha = 0.1f))
            .padding(AstraTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AstraTheme.colors.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.InsertDriveFile, "Document", tint = AstraTheme.colors.primary, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.fileName ?: "Document",
                style = AstraTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = AstraTheme.colors.onSurface,
                maxLines = 1
            )
            Text(
                text = "${formatFileSize(message.fileSize)} • ${message.fileName?.substringAfterLast('.', "PDF")?.uppercase()}",
                style = AstraTheme.typography.labelSmall,
                color = AstraTheme.colors.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = { /* Save */ }) {
            Icon(Icons.Rounded.SaveAlt, "Save", tint = AstraTheme.colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun BoxScope.TransferProgressOverlay(message: MessagePayload) {
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
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(48.dp)
            )
            // Cancel button inside progress ring could go here
        }
    } else if (message.localUri == null && message.senderId != "me") {
        // Needs download
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            // Download Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Text("↓", color = Color.White, fontSize = 24.sp)
            }
        }
    }
}

private fun formatFileSize(bytes: Long?): String {
    if (bytes == null || bytes == 0L) return "Unknown size"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format("%.1f MB", mb)
    } else {
        String.format("%.0f KB", kb)
    }
}
