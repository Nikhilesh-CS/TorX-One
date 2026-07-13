package com.torxone.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.torxone.app.ui.theme.AstraTheme
import com.torxone.app.ui.theme.AstraMotion
import com.torxone.app.ui.theme.DeepBlack
import com.torxone.app.ui.theme.NeonGreen
import com.torxone.app.ui.theme.AccentCyan
import com.torxone.app.ui.theme.AccentViolet

enum class TransportType {
    BLUETOOTH,
    WIFI_DIRECT,
    TOR,
    SWITCHING,
    OFFLINE
}

@Composable
fun ConnectionStatusPill(
    transportType: TransportType,
    details: String,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = when (transportType) {
            TransportType.BLUETOOTH -> NeonGreen.copy(alpha = 0.15f)
            TransportType.WIFI_DIRECT -> AccentCyan.copy(alpha = 0.15f)
            TransportType.TOR -> AccentViolet.copy(alpha = 0.15f)
            TransportType.SWITCHING -> Color(0xFFF59E0B).copy(alpha = 0.15f)
            TransportType.OFFLINE -> Color(0xFFEF4444).copy(alpha = 0.15f)
        },
        animationSpec = tween(AstraMotion.Durations.Medium),
        label = "pillBgColor"
    )

    val contentColor by animateColorAsState(
        targetValue = when (transportType) {
            TransportType.BLUETOOTH -> NeonGreen
            TransportType.WIFI_DIRECT -> AccentCyan
            TransportType.TOR -> AccentViolet
            TransportType.SWITCHING -> Color(0xFFF59E0B)
            TransportType.OFFLINE -> Color(0xFFEF4444)
        },
        animationSpec = tween(AstraMotion.Durations.Medium),
        label = "pillContentColor"
    )

    val icon: ImageVector = when (transportType) {
        TransportType.BLUETOOTH -> Icons.Default.Bluetooth
        TransportType.WIFI_DIRECT -> Icons.Default.Wifi
        TransportType.TOR -> Icons.Default.Lock
        TransportType.SWITCHING -> Icons.Default.Warning
        TransportType.OFFLINE -> Icons.Default.Warning
    }

    val label = when (transportType) {
        TransportType.BLUETOOTH -> "Bluetooth • $details"
        TransportType.WIFI_DIRECT -> "Wi-Fi Direct • $details"
        TransportType.TOR -> "Tor" + if (details.isNotBlank()) " • $details" else ""
        TransportType.SWITCHING -> "Syncing…"
        TransportType.OFFLINE -> "Offline"
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AstraTheme.spacing.standard))
            .background(backgroundColor)
            .padding(horizontal = AstraTheme.spacing.medium, vertical = AstraTheme.spacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AstraTheme.spacing.small)
    ) {
        if (AstraTheme.showTransportIcons) {
            Icon(
                imageVector = icon,
                contentDescription = "Transport Icon",
                tint = contentColor,
                modifier = Modifier.size(AstraTheme.iconSizes.tiny)
            )
        }
        Text(
            text = label,
            color = contentColor,
            style = AstraTheme.typography.labelSmall
        )
    }
}
