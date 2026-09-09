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
import com.torxone.app.ui.components.MessageDeliveryStatusView
import com.torxone.app.ui.components.toDeliveryState
import com.torxone.app.ui.theme.AstraTheme

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
            MessageDeliveryStatusView(
                state = lifecycleState.toDeliveryState(),
                tint = onBubbleColor.copy(alpha = 0.7f),
                size = AstraTheme.iconSizes.tiny
            )
        }
    }
}
