package com.torxone.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.engine.MessageDeliveryState
import com.torxone.app.engine.MessageLifecycleState
import com.torxone.app.ui.theme.ErrorRed
import com.torxone.app.ui.theme.InfoBlue
import com.torxone.app.ui.theme.TextMuted

/**
 * MessageDeliveryStatusView
 *
 * Renders verified protocol delivery status animations for chat messages:
 * - SENDING: Rocket animation with subtle launch bobbing
 * - SENT: Animated single check ✓
 * - DELIVERED: Animated double check ✓✓
 * - SEEN: Animated eye icon 👁 ("Seen")
 * - FAILED: Error icon !
 *
 * Historical messages loaded from storage render their final static state
 * without replaying entry transitions.
 */
@Composable
fun MessageDeliveryStatusView(
    state: MessageDeliveryState,
    modifier: Modifier = Modifier,
    tint: Color = TextMuted,
    size: Dp = 14.dp,
    showSeenText: Boolean = false
) {
    // Detect whether this is the first composition (restored from DB) or an active transition
    var previousState by remember { mutableStateOf<MessageDeliveryState?>(null) }
    val isRealtimeTransition = previousState != null && previousState != state

    LaunchedEffect(state) {
        previousState = state
    }

    val scaleAnim = remember { Animatable(1f) }

    LaunchedEffect(state) {
        if (isRealtimeTransition) {
            // Smooth 250ms scale-pop on state transition
            scaleAnim.snapTo(0.75f)
            scaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
            )
        } else {
            scaleAnim.snapTo(1f)
        }
    }

    val statusDescription = when (state) {
        MessageDeliveryState.SENDING -> "Sending"
        MessageDeliveryState.SENT -> "Sent"
        MessageDeliveryState.DELIVERED -> "Delivered"
        MessageDeliveryState.SEEN -> "Seen"
        MessageDeliveryState.FAILED -> "Failed to send"
    }

    Row(
        modifier = modifier
            .semantics { contentDescription = statusDescription }
            .scale(scaleAnim.value),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Crossfade(
            targetState = state,
            animationSpec = tween(
                durationMillis = if (isRealtimeTransition) 250 else 0,
                easing = FastOutSlowInEasing
            ),
            label = "delivery_status_crossfade"
        ) { targetState ->
            when (targetState) {
                MessageDeliveryState.SENDING -> {
                    // Subtle lift animation for rocket
                    val infiniteTransition = rememberInfiniteTransition(label = "rocket_bob")
                    val yBob by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = -1.5f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "rocket_y"
                    )
                    Icon(
                        imageVector = Icons.Rounded.RocketLaunch,
                        contentDescription = null,
                        tint = tint.copy(alpha = 0.8f),
                        modifier = Modifier
                            .size(size)
                            .offset(y = yBob.dp)
                    )
                }

                MessageDeliveryState.SENT -> {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(size)
                    )
                }

                MessageDeliveryState.DELIVERED -> {
                    Icon(
                        imageVector = Icons.Rounded.DoneAll,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(size)
                    )
                }

                MessageDeliveryState.SEEN -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Visibility,
                            contentDescription = null,
                            tint = InfoBlue,
                            modifier = Modifier.size(size)
                        )
                        if (showSeenText) {
                            Text(
                                text = "Seen",
                                fontSize = (size.value * 0.75f).sp,
                                color = InfoBlue,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                MessageDeliveryState.FAILED -> {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error.takeOrElse { ErrorRed },
                        modifier = Modifier.size(size)
                    )
                }
            }
        }
    }
}

/**
 * Convenience helper to map legacy MessageLifecycleState directly to MessageDeliveryState.
 */
fun MessageLifecycleState.toDeliveryState(): MessageDeliveryState {
    return when (this) {
        MessageLifecycleState.DRAFT,
        MessageLifecycleState.QUEUED,
        MessageLifecycleState.ENCRYPTING,
        MessageLifecycleState.SENDING,
        MessageLifecycleState.TRANSPORT_SELECTED,
        MessageLifecycleState.RETRYING -> MessageDeliveryState.SENDING

        MessageLifecycleState.IN_TRANSIT,
        MessageLifecycleState.ARCHIVED -> MessageDeliveryState.SENT

        MessageLifecycleState.DELIVERED -> MessageDeliveryState.DELIVERED

        MessageLifecycleState.READ -> MessageDeliveryState.SEEN

        MessageLifecycleState.FAILED,
        MessageLifecycleState.CANCELLED,
        MessageLifecycleState.EXPIRED -> MessageDeliveryState.FAILED
    }
}

private fun Color.takeOrElse(fallback: () -> Color): Color {
    return if (this != Color.Unspecified) this else fallback()
}
