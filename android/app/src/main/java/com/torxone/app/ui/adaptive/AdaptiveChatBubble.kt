package com.torxone.app.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.torxone.app.engine.MessageLifecycleState
import com.torxone.app.ui.theme.AstraTheme
import com.torxone.app.ui.theme.ErrorRed
import com.torxone.app.ui.theme.rememberWindowSizeClass
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role

@Composable
fun AdaptiveChatBubble(
    isMine: Boolean,
    timestamp: String,
    modifier: Modifier = Modifier,
    lifecycleState: MessageLifecycleState = MessageLifecycleState.DELIVERED,
    showTail: Boolean = true,
    isEncrypted: Boolean = true,
    replyContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val windowSizeClass = rememberWindowSizeClass()
    val widthFraction = when (windowSizeClass?.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 0.5f // 50% max width on tablets
        WindowWidthSizeClass.Medium -> 0.6f   // 60% max width on foldables
        else -> 0.8f                          // 80% max width on phones
    }

    val bubbleShape = if (isMine) {
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
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                // Group the entire bubble so TalkBack reads it as a single unit
                val sender = if (isMine) "You" else "Contact"
                contentDescription = "Message from $sender at $timestamp. Status: $lifecycleState"
                role = Role.Button
            },
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        BoxWithConstraints {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth * widthFraction)
                    .shadow(
                        elevation = 2.dp,
                        shape = bubbleShape,
                        spotColor = Color.Black.copy(alpha = 0.1f)
                    )
                    .clip(bubbleShape)
                    .background(if (isFailed) ErrorRed.copy(alpha = 0.2f) else bubbleColor)
                    .padding(horizontal = AstraTheme.spacing.medium, vertical = AstraTheme.spacing.small),
                horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
            ) {
                if (replyContent != null) {
                    replyContent()
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                }

                content()

                Spacer(modifier = Modifier.height(AstraTheme.spacing.tiny))

                ChatStatusRow(
                    timestamp = timestamp,
                    isMine = isMine,
                    isEncrypted = isEncrypted,
                    lifecycleState = lifecycleState,
                    onBubbleColor = onBubbleColor
                )
            }
        }
    }
}
