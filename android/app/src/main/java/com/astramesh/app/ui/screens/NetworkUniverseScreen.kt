package com.astramesh.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.network.NearbyConnectionManager
import com.astramesh.app.network.TorManager
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NetworkUniverseScreen(
    nearbyManager: NearbyConnectionManager,
    torManager: TorManager,
    db: AppDatabase
) {
    val infiniteTransition = rememberInfiniteTransition(label = "universe")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val breathing by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing"
    )

    // Using collected states for dynamic nodes
    val nearbyPeers by nearbyManager.connectedEndpoints.collectAsState()
    val activeOnionConnections by torManager.activeConnections.collectAsState(initial = emptyList())
    val torReady by torManager.isTorReady.collectAsState()

    // Use exact numbers, no maxOf mocks
    val visualBluetoothNodes = nearbyPeers.size // For simplicity, we treat nearby as a mix
    val visualWifiNodes = nearbyPeers.size 
    val visualOnionNodes = if (torReady) activeOnionConnections.size else 0

    val totalNodes = visualBluetoothNodes + visualWifiNodes + visualOnionNodes

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        if (totalNodes == 0) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Waiting for network data...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = size.width / 4

            // Draw Orbits
            drawCircle(
                color = Color.White.copy(alpha = 0.1f),
                radius = baseRadius * 0.8f,
                center = center,
                style = Stroke(width = 2f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.1f),
                radius = baseRadius * 1.5f,
                center = center,
                style = Stroke(width = 2f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.1f),
                radius = baseRadius * 2.5f,
                center = center,
                style = Stroke(width = 2f)
            )

            // Center Device
            drawCircle(
                color = Color(0xFF9FA8DA),
                radius = 20.dp.toPx() * breathing,
                center = center
            )

            // Bluetooth Nodes (Inner)
            withTransform({ rotate(rotation, center) }) {
                for (i in 0 until visualBluetoothNodes) {
                    val angle = (2 * Math.PI * i) / visualBluetoothNodes
                    val r = baseRadius * 0.8f
                    val x = center.x + (r * cos(angle)).toFloat()
                    val y = center.y + (r * sin(angle)).toFloat()
                    drawCircle(
                        color = Color(0xFF64B5F6),
                        radius = 8.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }

            // Wi-Fi Nodes (Middle)
            withTransform({ rotate(-rotation * 0.8f, center) }) {
                for (i in 0 until visualWifiNodes) {
                    val angle = (2 * Math.PI * i) / visualWifiNodes
                    val r = baseRadius * 1.5f
                    val x = center.x + (r * cos(angle)).toFloat()
                    val y = center.y + (r * sin(angle)).toFloat()
                    drawCircle(
                        color = Color(0xFF81C784),
                        radius = 10.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }

            // Tor Nodes (Outer)
            if (visualOnionNodes > 0) {
                withTransform({ rotate(rotation * 0.5f, center) }) {
                    for (i in 0 until visualOnionNodes) {
                        val angle = (2 * Math.PI * i) / visualOnionNodes
                        val r = baseRadius * 2.5f
                        val x = center.x + (r * cos(angle)).toFloat()
                        val y = center.y + (r * sin(angle)).toFloat()
                        drawCircle(
                            color = Color(0xFFBA68C8),
                            radius = 6.dp.toPx(),
                            center = Offset(x, y)
                        )
                    }
                }
            }
        }
        } // Close else block

        // Overlay Text
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Network Universe",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${nearbyPeers.size} Nearby • ${visualOnionNodes} Tor Connected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
    }
}
