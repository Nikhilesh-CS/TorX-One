package com.torxone.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.components.PremiumAuroraBackground
import com.torxone.app.ui.components.PremiumHeader
import com.torxone.app.ui.theme.AstraTheme
import kotlin.random.Random

@Composable
fun MeshDashboardScreen(
    onNavigateBack: () -> Unit
) {
    var showAdvancedTopology by remember { mutableStateOf(false) }

    PremiumAuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                PremiumHeader(
                    title = "Mesh Health",
                    subtitle = "Route quality, privacy, relay and topology",
                    trailing = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Rounded.ArrowBack, "Back", tint = Color(0xFFF6F7FF))
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(AstraTheme.spacing.medium),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AstraTheme.spacing.medium)
            ) {
                DashboardRow {
                    StatCard(Modifier.weight(1f), "Connection Quality", "Strong", Icons.Rounded.SignalCellularAlt, Color(0xFF00E5A8))
                    StatCard(Modifier.weight(1f), "Current Route", "Mesh + Tor", Icons.Rounded.Route, Color(0xFF8B5CF6))
                }
                DashboardRow {
                    StatCard(Modifier.weight(1f), "Encryption", "Protected", Icons.Rounded.Lock, Color(0xFF00E5A8))
                    StatCard(Modifier.weight(1f), "Privacy Level", "High", Icons.Rounded.Security, Color(0xFF38BDF8))
                }
                DashboardRow {
                    StatCard(Modifier.weight(1f), "Relay Availability", "Available", Icons.Rounded.Hub, Color(0xFF2563EB))
                    StatCard(Modifier.weight(1f), "Internet Status", "Ready", Icons.Rounded.Public, Color(0xFF38BDF8))
                }
                DashboardRow {
                    StatCard(Modifier.weight(1f), "Transfer Speed", "Adaptive", Icons.Rounded.Speed, Color(0xFF00E5A8))
                    StatCard(Modifier.weight(1f), "Latency", "Optimizing", Icons.Rounded.Timeline, Color(0xFF8B5CF6))
                }
                DashboardRow {
                    StatCard(Modifier.weight(1f), "Nearby Devices", "Scanning", Icons.Rounded.People, Color(0xFF38BDF8))
                    StatCard(Modifier.weight(1f), "Mesh Strength", "Healthy", Icons.Rounded.Share, Color(0xFF00E5A8))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.White.copy(alpha = 0.11f))
                        .border(1.dp, Color.White.copy(alpha = 0.11f), RoundedCornerShape(28.dp))
                        .padding(AstraTheme.spacing.standard),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = Color(0xFF38BDF8))
                    Spacer(modifier = Modifier.width(AstraTheme.spacing.standard))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Advanced topology", color = Color(0xFFF6F7FF), fontWeight = FontWeight.Black)
                        Text("Developer mode network graph", color = Color(0xFFB9C3D4), style = AstraTheme.typography.labelMedium)
                    }
                    Switch(checked = showAdvancedTopology, onCheckedChange = { showAdvancedTopology = it })
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (showAdvancedTopology) 420.dp else 260.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(Color(0xCC05070C))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(30.dp))
                ) {
                    MeshTopologyCanvas()
                    Text(
                        if (showAdvancedTopology) "Live Topology Map" else "Route Preview",
                        modifier = Modifier.padding(AstraTheme.spacing.standard),
                        color = Color(0xFFF6F7FF),
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AstraTheme.spacing.medium),
        content = content
    )
}

@Composable
private fun StatCard(modifier: Modifier, title: String, value: String, icon: ImageVector, color: Color) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.12f),
                        Color(0xFF0B1020).copy(alpha = 0.76f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(26.dp))
            .padding(AstraTheme.spacing.medium)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
            Text(title, style = AstraTheme.typography.labelMedium, color = Color(0xFFB9C3D4), maxLines = 2)
        }
        Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
        Text(value, style = AstraTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color(0xFFF6F7FF))
    }
}

@Composable
private fun MeshTopologyCanvas() {
    val primaryColor = AstraTheme.colors.primary
    val nodes = remember {
        List(15) {
            Offset(Random.nextFloat(), Random.nextFloat())
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        nodes.forEachIndexed { i, n1 ->
            nodes.forEachIndexed { j, n2 ->
                if (i < j && Random.nextFloat() > 0.7f) {
                    drawLine(
                        color = primaryColor.copy(alpha = 0.18f),
                        start = Offset(n1.x * w, n1.y * h),
                        end = Offset(n2.x * w, n2.y * h),
                        strokeWidth = 2f
                    )
                }
            }
        }

        nodes.forEachIndexed { index, n ->
            val isMe = index == 0
            val color = if (isMe) Color.White else primaryColor
            val radius = if (isMe) 16f else 8f

            drawCircle(
                color = color.copy(alpha = 0.20f),
                radius = radius * 2.4f,
                center = Offset(n.x * w, n.y * h)
            )
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(n.x * w, n.y * h)
            )
        }
    }
}
