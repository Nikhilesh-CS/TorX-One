package com.torxone.app.ui.screens


import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.torxone.app.ui.theme.AstraTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.network.NearbyConnectionManager
import com.torxone.app.network.TorManager
import com.torxone.app.network.TorState
import com.torxone.app.ui.theme.*

@Composable
fun DiagnosticsDialog(
    torManager: TorManager,
    nearbyManager: NearbyConnectionManager,
    onDismiss: () -> Unit
) {
    val torState by torManager.torState.collectAsStateWithLifecycle()
    val torStatus by torManager.torStatus.collectAsStateWithLifecycle()
    val onionAddress by torManager.onionAddress.collectAsStateWithLifecycle()

    val connectedEndpoints by nearbyManager.connectedEndpoints.collectAsStateWithLifecycle()
    val connectionStatus by nearbyManager.connectionStatus.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xE6111827),
        title = {
            Text("Network Diagnostics", color = SoftWhite, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Tor Section
                Text("Tor Network", color = AccentViolet, fontWeight = FontWeight.SemiBold, fontSize = AstraTheme.typography.bodyMedium.fontSize)
                Spacer(modifier = Modifier.height(AstraTheme.spacing.small))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (torState) {
                                    is TorState.Connected -> NeonGreen
                                    is TorState.Starting -> AccentCyan
                                    is TorState.Reconnecting -> AccentViolet
                                    is TorState.Failed -> AccentPink
                                    else -> DimGray
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
                    Text("Status: $torStatus", color = MutedGray, fontSize = AstraTheme.typography.bodySmall.fontSize)
                }

                Spacer(modifier = Modifier.height(AstraTheme.spacing.tiny))
                Text("Address: ${if (onionAddress.isNotBlank()) onionAddress else "Not available"}", color = MutedGray, fontSize = AstraTheme.typography.bodySmall.fontSize)

                if (torState is TorState.Starting) {
                    val progress = (torState as TorState.Starting).progress
                    Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                    Text("Bootstrap: $progress%", color = AccentCyan, fontSize = AstraTheme.typography.labelMedium.fontSize)
                    LinearProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier.fillMaxWidth().height(AstraTheme.spacing.tiny),
                        color = AccentCyan,
                        trackColor = DarkSurface
                    )
                }

                Spacer(modifier = Modifier.height(AstraTheme.spacing.extraLarge))

                // Mesh Section
                Text("Mesh Network", color = AccentBlue, fontWeight = FontWeight.SemiBold, fontSize = AstraTheme.typography.bodyMedium.fontSize)
                Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                Text("Connected Peers: ${connectedEndpoints.size}", color = MutedGray, fontSize = AstraTheme.typography.bodySmall.fontSize)
                Spacer(modifier = Modifier.height(AstraTheme.spacing.tiny))
                Text("Discovery: $connectionStatus", color = MutedGray, fontSize = AstraTheme.typography.bodySmall.fontSize)

                Spacer(modifier = Modifier.height(AstraTheme.spacing.extraLarge))

                // Device Info
                Text("Device Info", color = DimGray, fontWeight = FontWeight.SemiBold, fontSize = AstraTheme.typography.bodyMedium.fontSize)
                Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                Text("App Version: 2.0-mesh", color = MutedGray, fontSize = AstraTheme.typography.bodySmall.fontSize)
                Text("Protocol: V2", color = MutedGray, fontSize = AstraTheme.typography.bodySmall.fontSize)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AccentViolet)
            }
        },
        shape = RoundedCornerShape(30.dp),
        titleContentColor = Color(0xFFF6F7FF),
        textContentColor = Color(0xFFB9C3D4),
        tonalElevation = 0.dp
    )
}
