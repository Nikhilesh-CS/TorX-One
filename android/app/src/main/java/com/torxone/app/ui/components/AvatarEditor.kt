package com.torxone.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.torxone.app.profile.ProfileAvatarStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AvatarEditor(uri: Uri, onSaved: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 10 * 1024 * 1024) { "Profile image exceeds 10 MB" }; output.write(buffer, 0, n) }
                output.toByteArray()
            } ?: error("Cannot read image")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image" }
            options.inSampleSize = 1
            while (options.outWidth / options.inSampleSize > 2048 || options.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Cannot decode image")
            val orientation = ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = android.graphics.Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
                }
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            error = "This image could not be opened. Choose a supported image under 10 MB."
        }.getOrNull() }
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var circle by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Edit profile photo") },
        text = { Column {
            bitmap?.let { source ->
                Box(Modifier.fillMaxWidth().aspectRatio(1f).onSizeChanged { width = it.width }
                    .clip(if (circle) CircleShape else RoundedCornerShape(0.dp))) {
                    Image(source.asImageBitmap(), "Avatar crop preview", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().pointerInput(source, zoom) {
                            detectTransformGestures { _, offset, factor, _ ->
                                zoom = (zoom * factor).coerceIn(1f, 4f)
                                val limit = width * (zoom - 1f) / 2f
                                pan = Offset((pan.x + offset.x).coerceIn(-limit, limit), (pan.y + offset.y).coerceIn(-limit, limit))
                            }
                        }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y; rotationZ = rotation })
                }
                Text("Pinch to zoom; drag to reposition")
                Slider(zoom, { zoom = it; val limit = width * (zoom - 1f) / 2f; pan = Offset(pan.x.coerceIn(-limit, limit), pan.y.coerceIn(-limit, limit)) }, valueRange = 1f..4f)
                Row {
                    TextButton(onClick = { rotation = (rotation + 90f) % 360; pan = Offset.Zero }) { Text("Rotate") }
                    TextButton(onClick = { circle = !circle }) { Text(if (circle) "Square preview" else "Circle preview") }
                    TextButton(onClick = { zoom = 1f; pan = Offset.Zero; rotation = 0f }) { Text("Reset") }
                }
            } ?: run { if (error == null) CircularProgressIndicator() }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = {
            TextButton(enabled = bitmap != null && !saving, onClick = {
                saving = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) {
                        val source = requireNotNull(bitmap)
                        val output = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(output)
                        canvas.drawColor(android.graphics.Color.BLACK)
                        canvas.translate(256f + pan.x * 512 / width, 256f + pan.y * 512 / width)
                        canvas.rotate(rotation)
                        val scale = maxOf(512f / source.width, 512f / source.height) * zoom
                        canvas.scale(scale, scale)
                        canvas.drawBitmap(source, -source.width / 2f, -source.height / 2f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                        val bytes = ProfileAvatarStorage.compress(output); output.recycle()
                        val hash = ProfileAvatarStorage.save(context, bytes)
                        Uri.fromFile(requireNotNull(ProfileAvatarStorage.resolve(context, hash))).toString()
                    } }.onSuccess(onSaved).onFailure { error = it.message ?: "Unable to save photo" }
                    saving = false
                }
            }) { Text(if (saving) "Saving…" else "Use photo") }
        }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}
