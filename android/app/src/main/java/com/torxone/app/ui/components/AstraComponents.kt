package com.torxone.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.ui.theme.*

@Composable
fun AstraAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isOnline: Boolean = false,
    model: Any? = null
) {
    val initial = name.firstOrNull()?.uppercase() ?: "?"
    val colors = listOf(
        listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
        listOf(Color(0xFFEC4899), Color(0xFF8B5CF6)),
        listOf(Color(0xFF22D3EE), Color(0xFF6366F1)),
        listOf(Color(0xFF10B981), Color(0xFF22D3EE)),
        listOf(Color(0xFFF59E0B), Color(0xFFEC4899))
    )
    val colorPair = colors[name.hashCode().mod(colors.size).let { if (it < 0) it + colors.size else it }]

    Box(modifier = modifier.size(size)) {
        if (model != null) {
            AstraImage(
                model = model,
                contentDescription = "$name profile photo",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brush.linearGradient(colorPair))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brush.linearGradient(colorPair)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.4).sp
                )
            }
        }
        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(size * 0.28f)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(DeepSpace)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(NeonGreen)
            )
        }
    }
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.White.copy(alpha = alpha))
    )
}

@Composable
fun ShimmerContactCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShimmerBox(modifier = Modifier.size(52.dp), cornerRadius = 26.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.5f).height(16.dp))
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.8f).height(12.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        ShimmerBox(modifier = Modifier.width(36.dp).height(12.dp))
    }
}

@Composable
fun PulsingDot(
    color: Color = AccentCyan,
    size: Dp = 8.dp
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .size(size * scale)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun DiscoveryStatusChip(
    status: String,
    modifier: Modifier = Modifier
) {
    val (color, label) = when {
        status.contains("search", ignoreCase = true) || status.contains("discover", ignoreCase = true) ->
            AccentCyan to "Searching Nearby..."
        status.contains("connect", ignoreCase = true) ->
            Color(0xFFF59E0B) to "Connecting..."
        status.contains("ready", ignoreCase = true) || status.contains("advertis", ignoreCase = true) ->
            NeonGreen to "Ready"
        status.contains("fail", ignoreCase = true) || status.contains("error", ignoreCase = true) ->
            Color(0xFFEF4444) to "Offline"
        else -> MutedGray to status
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PulsingDot(color = color, size = 6.dp)
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun UnreadBadge(count: Int) {
    if (count <= 0) return
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(AccentViolet),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}


@Composable
fun AstraPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(AstraTheme.radii.button),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = AstraTheme.opacities.disabled),
            disabledContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary.copy(alpha = AstraTheme.opacities.disabled)
        ),
        contentPadding = PaddingValues(horizontal = AstraTheme.spacing.standard, vertical = AstraTheme.spacing.medium)
    ) {
        Text(text = text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AstraSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(AstraTheme.radii.button),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            disabledContentColor = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = AstraTheme.opacities.disabled)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, 
            if (enabled) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = AstraTheme.opacities.disabled)
        ),
        contentPadding = PaddingValues(horizontal = AstraTheme.spacing.standard, vertical = AstraTheme.spacing.medium)
    ) {
        Text(text = text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AstraCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        androidx.compose.material3.Card(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(AstraTheme.radii.card),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
            ),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(
                defaultElevation = AstraTheme.elevations.cardResting
            ),
            content = content
        )
    } else {
        androidx.compose.material3.Card(
            modifier = modifier,
            shape = RoundedCornerShape(AstraTheme.radii.card),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
            ),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(
                defaultElevation = AstraTheme.elevations.cardResting
            ),
            content = content
        )
    }
}

@Composable
fun AstraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        enabled = enabled,
        isError = isError,
        shape = RoundedCornerShape(AstraTheme.radii.card),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.outline,
            focusedBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        )
    )
}

@Composable
fun AstraDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmButtonText: String,
    onConfirm: () -> Unit,
    dismissButtonText: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            AstraPrimaryButton(
                text = confirmButtonText,
                onClick = onConfirm
            )
        },
        dismissButton = dismissButtonText?.let {
            {
                AstraSecondaryButton(
                    text = it,
                    onClick = onDismiss ?: onDismissRequest
                )
            }
        },
        shape = RoundedCornerShape(30.dp),
        containerColor = Color(0xE6111827),
        titleContentColor = Color(0xFFF6F7FF),
        textContentColor = Color(0xFFB9C3D4),
        tonalElevation = 0.dp
    )
}
