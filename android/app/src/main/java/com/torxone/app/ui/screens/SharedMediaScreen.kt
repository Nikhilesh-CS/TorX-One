package com.torxone.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.torxone.app.chat.MediaUiModel
import com.torxone.app.data.entity.MediaEntity
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.entity.MessageLinkEntity
import com.torxone.app.media.MediaStatus
import com.torxone.app.media.MediaType
import com.torxone.app.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedMediaScreen(media: List<MediaEntity>, messages: List<MessageEntity>, onDownload: ((String) -> Unit)? = null,
    cachedLinks: List<MessageLinkEntity>? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<MediaUiModel?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var linkQuery by remember { mutableStateOf("") }
    val tabs = listOf("Media", "Links", "Docs", "Voice")
    val itemsForTab = media.filter { when (tab) {
        0 -> it.mediaType in setOf("IMAGE", "VIDEO")
        2 -> it.mediaType == "DOCUMENT"
        3 -> it.mediaType in setOf("VOICE_NOTE", "AUDIO")
        else -> false
    } }.sortedByDescending { it.createdAt }
    fun open(item: MediaEntity) {
        val model = item.toUi()
        if (model.localPath?.let { java.io.File(it).isFile } == true && model.type in setOf(MediaType.IMAGE, MediaType.VIDEO)) selected = model
        else openMedia(context, model, onDownload?.let { { it(model.mediaId) } })
    }
    val links = cachedLinks?.map { it.url to it.host }?.distinct()
        ?: messages.filter { it.deletedAt == null }.flatMap { com.torxone.app.chat.MessageLinks.extract(it.body.orEmpty()) }.distinct()
    val shownLinks = links.filter { (url, host) -> url.contains(linkQuery, ignoreCase = true) || host.contains(linkQuery, ignoreCase = true) }
    Scaffold(topBar = { TopAppBar(title = { Text("Media, links and docs") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        TabRow(tab) { tabs.forEachIndexed { index, name -> Tab(tab == index, { tab = index }, text = { Text(name) }) } }
        if (tab == 1) OutlinedTextField(linkQuery, { linkQuery = it.take(256) }, Modifier.fillMaxWidth().padding(8.dp),
            label = { Text("Search saved links") }, singleLine = true)
        if (media.isEmpty() && links.isEmpty()) Text("No shared media or links yet", Modifier.padding(16.dp))
        else if (tab == 0) LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(itemsForTab, key = { it.mediaId }) { item -> Card(Modifier.clickable { open(item) }) {
                MediaThumbnail(item)
                Text(item.fileName, Modifier.padding(4.dp), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                Text(item.status.lowercase(), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall)
            } }
            if (itemsForTab.isEmpty()) item { Text("No shared photos or videos yet", Modifier.padding(16.dp)) }
        }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            if (tab != 1 && itemsForTab.isEmpty()) item { Text("No shared ${tabs[tab].lowercase()} yet") }
            items(itemsForTab, key = { it.mediaId }) { item ->
                val model = item.toUi()
                if (model.type == MediaType.AUDIO || model.type == MediaType.VOICE_NOTE) AudioPlayback(model, onDownload = onDownload?.let { { it(model.mediaId) } })
                else ListItem(headlineContent = { Text(model.fileName) }, supportingContent = { Text("${model.fileSize / 1024} KB • ${java.text.DateFormat.getDateInstance().format(java.util.Date(item.createdAt))} • ${model.status.name.lowercase()}") },
                    modifier = Modifier.clickable { open(item) })
                HorizontalDivider()
            }
            if (tab == 1 && shownLinks.isEmpty()) item { Text(if (linkQuery.isBlank()) "No shared links yet" else "No matching links") }
            if (tab == 1) items(shownLinks) { (url, host) -> TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { android.widget.Toast.makeText(context, "No browser available", android.widget.Toast.LENGTH_SHORT).show() }
            }) { Column { Text(host, style = MaterialTheme.typography.labelMedium); Text(url) } } }
        }
        }
    }
    selected?.let { MediaViewer(it) { selected = null } }
}

private fun MediaEntity.toUi() = MediaUiModel(mediaId, runCatching { MediaType.valueOf(mediaType) }.getOrDefault(MediaType.DOCUMENT),
    fileName, mimeType, fileSize, localPath, thumbnailData, durationMs, waveformData,
    runCatching { MediaStatus.valueOf(status) }.getOrDefault(MediaStatus.FAILED), transferProgress)

@Composable
private fun MediaThumbnail(item: MediaEntity) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.localPath, item.thumbnailData?.contentHashCode()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val thumbnail = item.thumbnailData
                if (thumbnail != null || (item.mediaType == "IMAGE" && item.localPath != null)) {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    if (thumbnail != null) android.graphics.BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size, bounds)
                    else android.graphics.BitmapFactory.decodeFile(item.localPath, bounds)
                    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }
                    while (bounds.outWidth / options.inSampleSize > 384 || bounds.outHeight / options.inSampleSize > 384) options.inSampleSize *= 2
                    if (thumbnail != null) android.graphics.BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size, options)
                    else android.graphics.BitmapFactory.decodeFile(item.localPath, options)
                } else null
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), item.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text(if (item.mediaType == "VIDEO") "Video" else "Image")
    }
}
