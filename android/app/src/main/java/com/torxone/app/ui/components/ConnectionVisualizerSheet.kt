package com.torxone.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.theme.AstraTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionVisualizerSheet(
    transportType: TransportType,
    peerName: String,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AstraTheme.colors.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AstraTheme.spacing.large)
                .padding(bottom = AstraTheme.spacing.extraLarge),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Live Mesh Route",
                style = AstraTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AstraTheme.colors.onSurface
            )
            Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
            Text(
                text = "Visualizing current delivery path to $peerName",
                style = AstraTheme.typography.bodyMedium,
                color = AstraTheme.colors.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(AstraTheme.spacing.extraLarge))

            if (transportType == TransportType.TOR) {
                TorRouteVisualization(peerName)
            } else if (transportType == TransportType.BLUETOOTH) {
                BluetoothMeshVisualization(peerName)
            } else {
                Text("No active route visualization available.")
            }
            
            Spacer(modifier = Modifier.height(AstraTheme.spacing.extraLarge))
        }
    }
}

@Composable
private fun TorRouteVisualization(peerName: String) {
    val primaryColor = AstraTheme.colors.primary
    val nodes = listOf("You", "Entry Guard", "Middle Relay", "Rendezvous", peerName)
    val icons = listOf(Icons.Rounded.Smartphone, Icons.Rounded.Security, Icons.Rounded.SyncAlt, Icons.Rounded.Hub, Icons.Rounded.Person)
    
    RouteGraph(nodes, icons, primaryColor)
}

@Composable
private fun BluetoothMeshVisualization(peerName: String) {
    val primaryColor = AstraTheme.colors.primary
    val nodes = listOf("You", "Nearby Peer A", peerName)
    val icons = listOf(Icons.Rounded.Smartphone, Icons.Rounded.Bluetooth, Icons.Rounded.Person)
    
    RouteGraph(nodes, icons, primaryColor)
}

@Composable
private fun RouteGraph(nodes: List<String>, icons: List<androidx.compose.ui.graphics.vector.ImageVector>, color: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        nodes.forEachIndexed { index, node ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Node
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icons[index], contentDescription = node, tint = color, modifier = Modifier.size(28.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = node,
                    style = AstraTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AstraTheme.colors.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            
            // Connecting Line
            if (index < nodes.size - 1) {
                Box(modifier = Modifier.height(40.dp).width(56.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxHeight().width(2.dp)) {
                        drawLine(
                            color = color.copy(alpha = 0.5f),
                            start = Offset(size.width / 2, 0f),
                            end = Offset(size.width / 2, size.height),
                            strokeWidth = 4f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )
                    }
                }
            }
        }
    }
}
