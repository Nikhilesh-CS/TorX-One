package com.astramesh.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

sealed class ChatBackgroundType {
    data class SolidColor(val color: Color) : ChatBackgroundType()
    data class Gradient(val colors: List<Color>) : ChatBackgroundType()
    data class ImageResource(val resId: Int) : ChatBackgroundType() // e.g. AMOLED mesh pattern
}

@Composable
fun ChatBackground(
    backgroundType: ChatBackgroundType,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Draw Background
        when (backgroundType) {
            is ChatBackgroundType.SolidColor -> {
                Box(modifier = Modifier.fillMaxSize().background(backgroundType.color))
            }
            is ChatBackgroundType.Gradient -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(backgroundType.colors))
                )
            }
            is ChatBackgroundType.ImageResource -> {
                Image(
                    painter = painterResource(id = backgroundType.resId),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Optional dimming overlay for better text contrast if needed
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            }
        }
        
        // Draw content on top
        content()
    }
}
