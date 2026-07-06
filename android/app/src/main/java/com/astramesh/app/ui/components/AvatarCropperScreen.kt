package com.astramesh.app.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.astramesh.app.ui.theme.AstraTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarCropperScreen(
    imageUri: Uri,
    onCropComplete: (Bitmap?) -> Unit,
    onCancel: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        // Adjust pan by scale to keep movement natural
        offset += panChange
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop Profile Photo") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // Note: Implement actual bitmap cropping based on scale and offset in future iteration
                        // For now we simulate success and return null to let the caller handle it or use the original
                        onCropComplete(null) 
                    }) {
                        Icon(Icons.Rounded.Check, "Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            // Image with pan and zoom
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUri)
                    .crossfade(true)
                    .build(),
                contentDescription = "Image to crop",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(state = state)
            )

            // Crop Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val circleRadius = size.minDimension * 0.45f
                val circleCenter = center

                // Path for the darkened background with a hole
                val path = Path().apply {
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addOval(Rect(
                        left = circleCenter.x - circleRadius,
                        top = circleCenter.y - circleRadius,
                        right = circleCenter.x + circleRadius,
                        bottom = circleCenter.y + circleRadius
                    ))
                    // FillType.EvenOdd creates the hole
                    fillType = PathFillType.EvenOdd
                }

                drawPath(
                    path = path,
                    color = Color.Black.copy(alpha = 0.6f)
                )

                // Draw a white border for the crop area
                drawCircle(
                    color = Color.White,
                    radius = circleRadius,
                    center = circleCenter,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}
