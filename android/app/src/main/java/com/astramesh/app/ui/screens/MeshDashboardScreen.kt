package com.astramesh.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astramesh.app.ui.theme.AstraTheme
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshDashboardScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = AstraTheme.colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Mesh Network Health", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AstraTheme.colors.surface,
                    titleContentColor = AstraTheme.colors.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AstraTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(AstraTheme.spacing.medium)
        ) {
            // Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AstraTheme.spacing.medium)
            ) {
                StatCard(Modifier.weight(1f), "Active Peers", "14", Icons.Rounded.People, AstraTheme.colors.primary)
                StatCard(Modifier.weight(1f), "Tor Nodes", "3", Icons.Rounded.Security, AstraTheme.colors.secondary)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AstraTheme.spacing.medium)
            ) {
                StatCard(Modifier.weight(1f), "Data Relayed", "42.5 MB", Icons.Rounded.DataUsage, AstraTheme.colors.primary)
                StatCard(Modifier.weight(1f), "Packet Drops", "0.01%", Icons.Rounded.Warning, Color(0xFFF59E0B))
            }

            Spacer(modifier = Modifier.height(AstraTheme.spacing.medium))

            // Topology Graph
            Text("Live Topology Map", style = AstraTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AstraTheme.colors.onSurface)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(AstraTheme.spacing.medium))
                    .background(Color.Black.copy(alpha = 0.2f))
            ) {
                MeshTopologyCanvas()
            }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AstraTheme.colors.surface),
        shape = RoundedCornerShape(AstraTheme.spacing.medium)
    ) {
        Column(
            modifier = Modifier.padding(AstraTheme.spacing.medium)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
                Text(title, style = AstraTheme.typography.labelMedium, color = AstraTheme.colors.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
            Text(value, style = AstraTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = AstraTheme.colors.onSurface)
        }
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

        // Draw connections
        nodes.forEachIndexed { i, n1 ->
            nodes.forEachIndexed { j, n2 ->
                if (i < j && Random.nextFloat() > 0.7f) {
                    drawLine(
                        color = primaryColor.copy(alpha = 0.2f),
                        start = Offset(n1.x * w, n1.y * h),
                        end = Offset(n2.x * w, n2.y * h),
                        strokeWidth = 2f
                    )
                }
            }
        }

        // Draw nodes
        nodes.forEachIndexed { index, n ->
            val isMe = index == 0
            val color = if (isMe) Color.White else primaryColor
            val radius = if (isMe) 16f else 8f
            
            drawCircle(
                color = color.copy(alpha = 0.2f),
                radius = radius * 2f,
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
