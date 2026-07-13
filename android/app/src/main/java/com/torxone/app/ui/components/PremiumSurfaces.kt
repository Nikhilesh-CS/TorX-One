package com.torxone.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.theme.AstraTheme

@Composable
fun PremiumAuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val transition = rememberInfiniteTransition(label = "premiumAurora")
    val glow by transition.animateFloat(
        initialValue = 0.035f,
        targetValue = 0.085f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "auroraGlow"
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF05070C),
                        Color(0xFF0B1020),
                        Color(0xFF071512),
                        Color(0xFF05070C)
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF00E5A8).copy(alpha = glow),
                            Color.Transparent,
                            Color(0xFF8B5CF6).copy(alpha = glow * 0.85f),
                            Color(0xFF38BDF8).copy(alpha = glow * 0.65f)
                        )
                    )
                )
        )
        content()
    }
}

fun Modifier.premiumGlass(
    radius: Dp = 28.dp,
    alpha: Float = 0.16f,
    borderAlpha: Float = 0.11f
): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(Color.White.copy(alpha = alpha))
    .border(1.dp, Color.White.copy(alpha = borderAlpha), RoundedCornerShape(radius))

@Composable
fun PremiumHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AstraTheme.spacing.large, vertical = AstraTheme.spacing.small)
            .premiumGlass(radius = 28.dp, alpha = 0.095f)
            .padding(horizontal = AstraTheme.spacing.standard, vertical = AstraTheme.spacing.standard),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = Color(0xFFF6F7FF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFB9C3D4),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun PremiumPulseDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 9.dp
) {
    val transition = rememberInfiniteTransition(label = "premiumPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}
