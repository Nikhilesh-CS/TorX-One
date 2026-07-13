package com.torxone.app.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.theme.AstraTheme

@Composable
fun ReplyPreview(
    senderName: String,
    messageText: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AstraTheme.radii.small))
            .background(Color.Black.copy(alpha = 0.1f))
            .padding(end = AstraTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vertical indicator bar
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(IntrinsicSize.Min)
                .background(accentColor)
        ) {
            Spacer(modifier = Modifier.height(36.dp)) // Fixed vertical rhythm
        }

        Spacer(modifier = Modifier.width(AstraTheme.spacing.small))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = AstraTheme.spacing.tiny),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = senderName,
                color = accentColor,
                style = AstraTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = messageText,
                color = AstraTheme.colors.onSurfaceVariant,
                style = AstraTheme.typography.bodySmall,
                maxLines = 1, // Single-line ellipsis truncation as requested
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
