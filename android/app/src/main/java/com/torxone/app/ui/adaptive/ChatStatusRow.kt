package com.torxone.app.ui.adaptive

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.torxone.app.engine.MessageLifecycleState
import com.torxone.app.ui.theme.AstraTheme
import com.torxone.app.ui.theme.ErrorRed
import com.torxone.app.ui.theme.InfoBlue

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatStatusRow(
    timestamp: String,
    isMine: Boolean,
    isEncrypted: Boolean,
    lifecycleState: MessageLifecycleState,
    onBubbleColor: Color
) {
    FlowRow(
        horizontalArrangement = Arrangement.End,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.wrapContentWidth()
    ) {
        if (isEncrypted && AstraTheme.showTransportIcons) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Encrypted",
                tint = onBubbleColor.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(AstraTheme.iconSizes.tiny)
                    .padding(end = AstraTheme.spacing.tiny)
            )
        }

        Text(
            text = timestamp,
            style = AstraTheme.typography.labelSmall,
            color = onBubbleColor.copy(alpha = 0.7f),
            modifier = Modifier.padding(end = if (isMine) AstraTheme.spacing.tiny else 0.dp)
        )

        if (isMine) {
            LifecycleStateIcon(
                state = lifecycleState,
                color = onBubbleColor.copy(alpha = 0.7f)
            )
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
