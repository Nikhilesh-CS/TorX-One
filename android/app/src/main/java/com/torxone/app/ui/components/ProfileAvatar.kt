package com.torxone.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProfileAvatar(name: String, avatar: String?, modifier: Modifier = Modifier, previewOnClick: Boolean = false) {
    val context = LocalContext.current
    var showPhoto by remember(avatar) { mutableStateOf(false) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, avatar) {
        value = null
        value = withContext(Dispatchers.IO) {
            try { LocalImagePreview.avatar(context, avatar, 256)?.asImageBitmap() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }
    val interaction = if (previewOnClick && bitmap != null && avatar != null)
        Modifier.clickable(onClickLabel = "View profile photo of $name") { showPhoto = true } else Modifier
    Box(modifier.then(interaction).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, "Profile photo of $name", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase().ifEmpty { "?" }, color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleLarge)
    }
    if (showPhoto && avatar != null) ProfilePhotoViewer(name, avatar) { showPhoto = false }
}
