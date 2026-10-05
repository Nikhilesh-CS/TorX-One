package com.torxone.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Separately reads the underlying avatar, never reuses the small list/header bitmap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePhotoViewer(name: String, avatar: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var loaded by remember(avatar) { mutableStateOf(false) }
    var scale by remember(avatar) { mutableFloatStateOf(1f) }
    var x by remember(avatar) { mutableFloatStateOf(0f) }
    var y by remember(avatar) { mutableFloatStateOf(0f) }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, avatar) {
        value = withContext(Dispatchers.IO) {
            try { LocalImagePreview.avatar(context, avatar, 2048)?.asImageBitmap() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        loaded = true
    }
    // Caller only opens for a decoded real photo. If it disappeared, close instead of showing initials.
    LaunchedEffect(loaded, bitmap) { if (loaded && bitmap == null) onDismiss() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            TopAppBar(title = { Text(name, color = Color.White) }, navigationIcon = {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close profile photo", tint = Color.White) }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black))
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().pointerInput(avatar) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale == 1f) { x = 0f; y = 0f }
                    else {
                        val maxX = size.width * (scale - 1f) / 2f
                        val maxY = size.height * (scale - 1f) / 2f
                        x = (x + pan.x).coerceIn(-maxX, maxX); y = (y + pan.y).coerceIn(-maxY, maxY)
                    }
                }
            }, contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it, "Profile photo of $name", Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = x; translationY = y
                }, contentScale = ContentScale.Fit) }
                if (!loaded) CircularProgressIndicator(color = Color.White)
            }
            TextButton(onClick = { scale = 1f; x = 0f; y = 0f }, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.CenterHorizontally)) {
                Text("Reset zoom", color = Color.White)
            }
        }
    }
}
