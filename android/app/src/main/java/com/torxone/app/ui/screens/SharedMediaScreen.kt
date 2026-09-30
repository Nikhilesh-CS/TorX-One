package com.torxone.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.torxone.app.chat.MediaUiModel
import com.torxone.app.data.entity.MediaEntity
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.media.MediaStatus
import com.torxone.app.media.MediaType
import com.torxone.app.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedMediaScreen(media: List<MediaEntity>, messages: List<MessageEntity>, onDownload: ((String) -> Unit)? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<MediaUiModel?>(null) }
    val links = remember(messages.map { it.body to it.deletedAt }) { messages.filter { it.deletedAt == null }.flatMap {
        Regex("https?://[^\\s<>]+").findAll(it.body.orEmpty()).map { it.value.trimEnd('.', ',', ')') }.toList()
    }.distinct() }
    Scaffold(topBar = { TopAppBar(title = { Text("Media, links and docs") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            if (media.isEmpty() && links.isEmpty()) item { Text("No shared media or links yet") }
            items(media, key = { it.mediaId }) { item ->
                val model = MediaUiModel(item.mediaId, MediaType.valueOf(item.mediaType), item.fileName,
                    item.mimeType, item.fileSize, item.localPath, item.thumbnailData, item.durationMs,
                    item.waveformData, MediaStatus.valueOf(item.status), item.transferProgress)
                if (model.type == MediaType.AUDIO || model.type == MediaType.VOICE_NOTE) AudioPlayback(model, onDownload = onDownload?.let { { it(model.mediaId) } })
                else ListItem(headlineContent = { Text(model.fileName) }, supportingContent = { Text("${model.fileSize / 1024} KB • ${model.status.name.lowercase()}") },
                    modifier = Modifier.clickable {
                        if (model.localPath != null && model.type in setOf(MediaType.IMAGE, MediaType.VIDEO)) selected = model
                        else openMedia(context, model, onDownload?.let { { it(model.mediaId) } })
                    })
                HorizontalDivider()
            }
            items(links) { url -> TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { android.widget.Toast.makeText(context, "No browser available", android.widget.Toast.LENGTH_SHORT).show() }
            }) { Text(url) } }
        }
    }
    selected?.let { MediaViewer(it) { selected = null } }
}
