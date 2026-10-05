package com.torxone.app.ui.components

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.appearance.ChatAppearance
import com.torxone.app.ui.appearance.WallpaperAssetStorage
import com.torxone.app.ui.appearance.rememberDepthMotion
import com.torxone.app.ui.theme.TorXAccent

/** Render separately behind the message list; motion never reaches message item state. */
@Composable
fun ChatBackground(appearance: ChatAppearance, modifier: Modifier = Modifier, preview: Boolean = false) {
    val config = remember(appearance) { appearance.normalized() }
    val colors = MaterialTheme.colorScheme
    val base = when (config.wallpaperType) {
        "WARM" -> lerp(colors.background, Color(0xFFC88845), 0.10f)
        "COOL" -> lerp(colors.background, Color(0xFF397A9C), 0.10f)
        else -> colors.background
    }
    val accent = config.effectiveAccentId?.let { Color(TorXAccent.resolve(it).darkArgb) } ?: colors.primary
    val context = LocalContext.current
    val assets = remember(context) { WallpaperAssetStorage(context.applicationContext) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, config.wallpaperType, config.wallpaperAssetId) {
        value = try { if (config.wallpaperType == "PHOTO") assets.decode(config.wallpaperAssetId) else null }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    val motion = rememberDepthMotion(if (preview) config.copy(parallaxStrength = 0f) else config)
    Box(modifier.background(base).clipToBounds()) {
        bitmap?.let { image ->
            var viewport by remember { mutableStateOf(IntSize.Zero) }
            val blur = if (Build.VERSION.SDK_INT >= 31 && !motion.reduced.value) Modifier.blur(config.blur.dp) else Modifier
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it }.then(blur).graphicsLayer {
                    scaleX = config.photoZoom; scaleY = config.photoZoom
                    val scale = maxOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
                    translationX = config.photoPanX * ((image.width * scale * config.photoZoom - viewport.width) / 2f).coerceAtLeast(0f)
                    translationY = config.photoPanY * ((image.height * scale * config.photoZoom - viewport.height) / 2f).coerceAtLeast(0f)
                })
        }
        if (config.preset == "DEPTH") {
            if (config.motionMode == "FULL" && !motion.reduced.value) Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(accent.copy(alpha = 0.08f), Color.Transparent))))
            for (layer in 1..2) Canvas(Modifier.fillMaxSize().graphicsLayer {
                translationX = motion.offset.value.x * 8.dp.toPx() * config.parallaxStrength * layer
                translationY = motion.offset.value.y * 8.dp.toPx() * config.parallaxStrength * layer
            }) {
                val spacing = (96 + layer * 24).dp.toPx()
                val color = accent.copy(alpha = if (layer == 1) 0.055f else 0.035f)
                var y = -spacing
                while (y < size.height + spacing) {
                    var x = -spacing + if ((y / spacing).toInt() % 2 == 0) spacing / 2 else 0f
                    while (x < size.width + spacing) {
                        drawCircle(color, 2.dp.toPx(), Offset(x, y))
                        drawLine(color, Offset(x, y), Offset(x + spacing / 3, y + spacing / 3), 1.dp.toPx())
                        drawCircle(color, 7.dp.toPx(), Offset(x + spacing / 3, y + spacing / 3), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        x += spacing
                    }
                    y += spacing
                }
            }
        }
        if (config.wallpaperType == "PHOTO") Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = config.dim)))
    }
}
