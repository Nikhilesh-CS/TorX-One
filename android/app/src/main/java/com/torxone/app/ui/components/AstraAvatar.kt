package com.torxone.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.theme.AstraTheme

@Composable
fun AstraAvatar(
    model: Any?,
    modifier: Modifier = Modifier,
    name: String? = null,
    size: Dp = AstraTheme.avatarSizes.medium,
    isOnline: Boolean = false,
    isVerified: Boolean = false,
    contentDescription: String? = null
) {
    val indicatorSize = size * 0.25f
    val badgeSize = size * 0.3f
    
    Box(modifier = modifier.size(size)) {
        if (model != null) {
            AstraImage(
                model = model,
                contentDescription = contentDescription ?: name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(AstraTheme.colors.surfaceVariant)
            )
        } else {
            // Placeholder
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(AstraTheme.colors.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!name.isNullOrBlank()) {
                    Text(
                        text = name.take(1).uppercase(),
                        style = AstraTheme.typography.titleMedium,
                        color = AstraTheme.colors.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Default Avatar",
                        tint = AstraTheme.colors.onPrimaryContainer,
                        modifier = Modifier.size(size * 0.5f)
                    )
                }
            }
        }
        
        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(indicatorSize)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-2).dp, y = (-2).dp)
                    .clip(CircleShape)
                    .background(Color.Green)
                    .border(2.dp, AstraTheme.colors.background, CircleShape)
            )
        }
        
        if (isVerified) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Verified",
                tint = AstraTheme.colors.primary,
                modifier = Modifier
                    .size(badgeSize)
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .background(AstraTheme.colors.background, CircleShape)
                    .border(1.dp, AstraTheme.colors.background, CircleShape)
            )
        }
    }
}
