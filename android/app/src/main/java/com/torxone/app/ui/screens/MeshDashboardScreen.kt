package com.torxone.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.components.PremiumHeader
import com.torxone.app.ui.theme.AppBackground
import com.torxone.app.ui.theme.AstraTheme
import com.torxone.app.ui.theme.BorderColor
import com.torxone.app.ui.theme.PrimaryText
import com.torxone.app.ui.theme.SecondaryText
import com.torxone.app.ui.theme.SuccessGreen
import com.torxone.app.ui.theme.SurfaceCard
import com.torxone.app.ui.theme.SurfaceSecondary
import com.torxone.app.ui.theme.TorAccent
import com.torxone.app.ui.theme.TorXPrimary
import com.torxone.app.ui.theme.TorXPrimarySoft
import kotlin.random.Random

@Composable
fun MeshDashboardScreen(
    onNavigateBack: () -> Unit
) {
    var showAdvancedTopology by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            PremiumHeader(
                title = "Mesh Health",
                subtitle = "Route quality, privacy, relay and topology",
                trailing = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, "Back", tint = PrimaryText)
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
            verticalArrangement = Arrangement.spacedBy(AstraTheme.spacing.medium)
        ) {
            DashboardRow {
                StatCard(Modifier.weight(1f), "Connection Quality", "Strong", Icons.Rounded.SignalCellularAlt, SuccessGreen)
                StatCard(Modifier.weight(1f), "Current Route", "Mesh + Tor", Icons.Rounded.Route, TorAccent)
            }
            DashboardRow {
                StatCard(Modifier.weight(1f), "Encryption", "Protected", Icons.Rounded.Lock, SuccessGreen)
                StatCard(Modifier.weight(1f), "Privacy Level", "High", Icons.Rounded.Security, TorXPrimary)
            }
            DashboardRow {
                StatCard(Modifier.weight(1f), "Relay Availability", "Available", Icons.Rounded.Hub, TorXPrimary)
                StatCard(Modifier.weight(1f), "Internet Status", "Ready", Icons.Rounded.Public, SuccessGreen)
            }
            DashboardRow {
                StatCard(Modifier.weight(1f), "Transfer Speed", "Adaptive", Icons.Rounded.Speed, SuccessGreen)
                StatCard(Modifier.weight(1f), "Latency", "Optimizing", Icons.Rounded.Timeline, TorXPrimary)
            }
            DashboardRow {
                StatCard(Modifier.weight(1f), "Nearby Devices", "Scanning", Icons.Rounded.People, TorXPrimary)
                StatCard(Modifier.weight(1f), "Mesh Strength", "Healthy", Icons.Rounded.Share, SuccessGreen)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                    .padding(AstraTheme.spacing.standard),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(TorXPrimarySoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = TorXPrimary, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(AstraTheme.spacing.standard))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Advanced topology", color = PrimaryText, fontWeight = FontWeight.SemiBold)
                    Text("Developer mode network graph", color = SecondaryText, style = AstraTheme.typography.labelMedium)
                }
                Switch(
                    checked = showAdvancedTopology,
                    onCheckedChange = { showAdvancedTopology = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = TorXPrimary)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (showAdvancedTopology) 380.dp else 240.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
            ) {
                MeshTopologyCanvas()
                Text(
                    if (showAdvancedTopology) "Live Topology Map" else "Route Preview",
                    modifier = Modifier.padding(AstraTheme.spacing.standard),
                    color = PrimaryText,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun DashboardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AstraTheme.spacing.medium),
        content = content
    )
}

@Composable
private fun StatCard(modifier: Modifier, title: String, value: String, icon: ImageVector, color: Color) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            .padding(AstraTheme.spacing.medium)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
            Text(title, style = AstraTheme.typography.labelMedium, color = SecondaryText, maxLines = 2)
        }
        Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
        Text(value, style = AstraTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = PrimaryText)
    }
}

@Composable
private fun MeshTopologyCanvas() {
    val primaryColor = TorXPrimary
    val nodes = remember {
        List(15) {
            Offset(Random.nextFloat(), Random.nextFloat())
        }
    }

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val w = size.width
        val h = size.height

        nodes.forEachIndexed { i, n1 ->
            nodes.forEachIndexed { j, n2 ->
                if (i < j && Random.nextFloat() > 0.7f) {
                    drawLine(
                        color = BorderColor,
                        start = Offset(n1.x * w, n1.y * h),
                        end = Offset(n2.x * w, n2.y * h),
                        strokeWidth = 2f
                    )
                }
            }
        }

        nodes.forEachIndexed { index, n ->
            val isMe = index == 0
            val nodeColor = if (isMe) TorXPrimary else Color(0xFF94A3B8)
            val radius = if (isMe) 10f else 6f

            if (isMe) {
                drawCircle(
                    color = TorXPrimarySoft,
                    radius = radius * 2.5f,
                    center = Offset(n.x * w, n.y * h)
                )
            }
            drawCircle(
                color = nodeColor,
                radius = radius,
                center = Offset(n.x * w, n.y * h)
            )
        }
    }
}
