package com.torxone.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.torxone.app.engine.MessageLifecycleState
import com.torxone.app.ui.theme.AstraTheme
import com.torxone.app.ui.theme.ErrorRed
import com.torxone.app.ui.theme.InfoBlue

@Composable
fun AstraMessageBubbleFoundation(
    isMine: Boolean,
    timestamp: String,
    modifier: Modifier = Modifier,
    lifecycleState: MessageLifecycleState = MessageLifecycleState.DELIVERED,
    showTail: Boolean = true,
    isEncrypted: Boolean = true,
    content: @Composable () -> Unit
) {
    val bubbleShape: Shape = if (isMine) {
        RoundedCornerShape(
            topStart = AstraTheme.radii.card,
            topEnd = AstraTheme.radii.card,
            bottomStart = AstraTheme.radii.card,
            bottomEnd = if (showTail) AstraTheme.radii.small else AstraTheme.radii.card
        )
    } else {
        RoundedCornerShape(
            topStart = AstraTheme.radii.card,
            topEnd = AstraTheme.radii.card,
            bottomStart = if (showTail) AstraTheme.radii.small else AstraTheme.radii.card,
            bottomEnd = AstraTheme.radii.card
        )
    }

    val bubbleColor = if (isMine) AstraTheme.colors.primary else AstraTheme.colors.surfaceVariant
    val onBubbleColor = if (isMine) AstraTheme.colors.onPrimary else AstraTheme.colors.onSurfaceVariant

    val isFailed = lifecycleState == MessageLifecycleState.FAILED || lifecycleState == MessageLifecycleState.EXPIRED

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .wrapContentWidth()
                .clip(bubbleShape)
                .background(if (isFailed) ErrorRed.copy(alpha = 0.2f) else bubbleColor)
                .padding(horizontal = AstraTheme.spacing.medium, vertical = AstraTheme.spacing.small),
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
        ) {
            content()
            
            Spacer(modifier = Modifier.height(AstraTheme.spacing.tiny))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (isEncrypted) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Encrypted",
                        tint = onBubbleColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(AstraTheme.iconSizes.tiny)
                    )
                    Spacer(modifier = Modifier.width(AstraTheme.spacing.tiny))
                }

                Text(
                    text = timestamp,
                    style = AstraTheme.typography.labelSmall,
                    color = onBubbleColor.copy(alpha = 0.7f)
                )
                
                if (isMine) {
                    Spacer(modifier = Modifier.width(AstraTheme.spacing.tiny))
                    LifecycleStateIcon(
                        state = lifecycleState,
                        color = onBubbleColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LifecycleStateIcon(state: MessageLifecycleState, color: Color) {
    val icon = when (state) {
        MessageLifecycleState.DRAFT, MessageLifecycleState.QUEUED, MessageLifecycleState.ENCRYPTING, 
        MessageLifecycleState.SENDING, MessageLifecycleState.TRANSPORT_SELECTED,
        MessageLifecycleState.RETRYING ->
            Icons.Default.Schedule
        MessageLifecycleState.IN_TRANSIT, MessageLifecycleState.ARCHIVED ->
            Icons.Default.Check
        MessageLifecycleState.DELIVERED ->
            Icons.Default.DoneAll
        MessageLifecycleState.READ -> 
            Icons.Default.DoneAll
        MessageLifecycleState.FAILED,
        MessageLifecycleState.CANCELLED, MessageLifecycleState.EXPIRED -> 
            Icons.Default.Error
    }
    
    val tint = when (state) {
        MessageLifecycleState.READ -> InfoBlue
        MessageLifecycleState.FAILED, MessageLifecycleState.EXPIRED -> ErrorRed
        MessageLifecycleState.RETRYING -> Color(0xFFF59E0B) // Amber
        else -> color
    }

    Icon(
        imageVector = icon,
        contentDescription = state.name,
        tint = tint,
        modifier = Modifier.size(AstraTheme.iconSizes.tiny)
    )
}
