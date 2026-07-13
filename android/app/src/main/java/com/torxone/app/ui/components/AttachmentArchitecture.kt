package com.torxone.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.torxone.app.ui.theme.AstraTheme

enum class AttachmentType {
    IMAGE, VIDEO, AUDIO, DOCUMENT, APK, LOCATION, CONTACT
}

data class AttachmentMetadata(
    val uri: String,
    val type: AttachmentType,
    val mimeType: String,
    val sizeBytes: Long,
    val name: String
)

@Composable
fun AttachmentPreview(
    attachment: AttachmentMetadata,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AstraTheme.radii.small))
            .background(AstraTheme.colors.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onClick() }
            .padding(AstraTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (attachment.type) {
            AttachmentType.IMAGE, AttachmentType.VIDEO -> Icons.Default.Image
            AttachmentType.AUDIO -> Icons.Default.Mic
            else -> Icons.Default.Description
        }
        
        Box(
            modifier = Modifier
                .size(AstraTheme.iconSizes.large)
                .clip(RoundedCornerShape(AstraTheme.radii.small))
                .background(AstraTheme.colors.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = attachment.type.name,
                tint = AstraTheme.colors.primary,
                modifier = Modifier.size(AstraTheme.iconSizes.medium)
            )
        }
        
        Spacer(modifier = Modifier.width(AstraTheme.spacing.standard))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = AstraTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = AstraTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Text(
                text = formatFileSize(attachment.sizeBytes),
                style = AstraTheme.typography.labelSmall,
                color = AstraTheme.colors.onSurfaceVariant
            )
        }
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
