package com.torxone.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.theme.*

enum class MeshNetworkState {
    CONNECTING, CONNECTED, OFFLINE, SYNCING, SEARCHING
}

@Composable
fun NetworkStateBanner(
    state: MeshNetworkState,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        val backgroundColor: Color
        val textColor: Color
        val text: String
        val iconColor: Color

        when (state) {
            MeshNetworkState.CONNECTED -> {
                backgroundColor = NeonGreen.copy(alpha = 0.2f)
                textColor = NeonGreen
                text = "Connected to Mesh"
                iconColor = NeonGreen
            }
            MeshNetworkState.CONNECTING -> {
                backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.2f)
                textColor = Color(0xFFF59E0B)
                text = "Connecting..."
                iconColor = Color(0xFFF59E0B)
            }
            MeshNetworkState.OFFLINE -> {
                backgroundColor = Color(0xFFEF4444).copy(alpha = 0.2f)
                textColor = Color(0xFFEF4444)
                text = "Offline"
                iconColor = Color(0xFFEF4444)
            }
            MeshNetworkState.SYNCING -> {
                backgroundColor = AccentViolet.copy(alpha = 0.2f)
                textColor = AccentViolet
                text = "Syncing Data..."
                iconColor = AccentViolet
            }
            MeshNetworkState.SEARCHING -> {
                backgroundColor = AccentCyan.copy(alpha = 0.2f)
                textColor = AccentCyan
                text = "Searching for Peers..."
                iconColor = AccentCyan
            }
        }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .padding(horizontal = AstraTheme.spacing.standard, vertical = AstraTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (state == MeshNetworkState.SEARCHING || state == MeshNetworkState.CONNECTING || state == MeshNetworkState.SYNCING) {
                PulsingDot(color = iconColor, size = 8.dp)
                Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
            } else if (state == MeshNetworkState.CONNECTED) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(iconColor, androidx.compose.foundation.shape.CircleShape)
                )
                Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(iconColor, androidx.compose.foundation.shape.CircleShape)
                )
                Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
            }
            Text(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
