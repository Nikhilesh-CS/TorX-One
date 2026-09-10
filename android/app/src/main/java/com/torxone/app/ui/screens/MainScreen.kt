package com.torxone.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.torxone.app.data.AppDatabase
import com.torxone.app.identity.IdentityManager
import com.torxone.app.network.MessageRouter
import com.torxone.app.network.NearbyConnectionManager
import com.torxone.app.network.TorManager
import com.torxone.app.ui.theme.AstraTheme
import com.torxone.app.ui.theme.BorderColor
import com.torxone.app.ui.theme.SecondaryText
import com.torxone.app.ui.theme.SurfaceCard
import com.torxone.app.ui.theme.TorXPrimary
import com.torxone.app.ui.theme.TorXPrimarySoft

@Composable
fun MainScreen(
    identityManager: IdentityManager,
    rootNavController: NavHostController,
    db: AppDatabase,
    nearbyManager: NearbyConnectionManager,
    torManager: TorManager,
    messageRouter: MessageRouter,
    settingsManager: com.torxone.app.data.SettingsManager
) {
    val bottomNavController = rememberNavController()
    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    if (currentRoute != null && currentRoute != "chats") {
        BackHandler {
            bottomNavController.navigate("chats") {
                popUpTo(bottomNavController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in listOf("chats", "contacts", "security", "settings")) {
                AstraBottomNavigation(
                    currentRoute = currentRoute ?: "chats",
                    onNavigate = { route ->
                        bottomNavController.navigate(route) {
                            popUpTo(bottomNavController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        NavHost(
            navController = bottomNavController,
            startDestination = "chats",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("chats") {
                ChatListScreen(
                    identityManager = identityManager,
                    navController = rootNavController,
                    db = db,
                    nearbyManager = nearbyManager,
                    torManager = torManager,
                    messageRouter = messageRouter
                )
            }
            composable("contacts") {
                ContactsScreen(
                    navController = rootNavController,
                    db = db,
                    nearbyManager = nearbyManager,
                    torManager = torManager
                )
            }
            composable("security") {
                SecurityCenterScreen(
                    identityManager = identityManager,
                    torManager = torManager,
                    settingsManager = settingsManager
                )
            }
            composable("settings") {
                val onionAddress by torManager.onionAddress.collectAsStateWithLifecycle()
                SettingsScreen(
                    identityManager = identityManager,
                    navController = rootNavController,
                    onionAddress = onionAddress,
                    db = db,
                    settingsManager = settingsManager,
                    onNavigateBack = {
                        bottomNavController.navigate("chats") {
                            popUpTo(bottomNavController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun AstraBottomNavigation(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    NavigationBar(
        modifier = Modifier
            .padding(horizontal = AstraTheme.spacing.standard, vertical = AstraTheme.spacing.small)
            .clip(RoundedCornerShape(20.dp))
            .background(AstraTheme.surfaceCard)
            .border(1.dp, AstraTheme.border, RoundedCornerShape(20.dp)),
        containerColor = Color.Transparent,
        tonalElevation = 2.dp
    ) {
        val items = listOf(
            NavRoute("chats", Icons.Rounded.ChatBubble, "Messages"),
            NavRoute("contacts", Icons.Rounded.Groups, "Contacts"),
            NavRoute("security", Icons.Rounded.Security, "Security"),
            NavRoute("settings", Icons.Rounded.Settings, "Settings")
        )

        items.forEach { item ->
            val selected = currentRoute == item.route
            PremiumNavItem(item = item, selected = selected, onNavigate = onNavigate)
        }
    }
}

@Composable
private fun RowScope.PremiumNavItem(
    item: NavRoute,
    selected: Boolean,
    onNavigate: (String) -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        label = "dockItemScale"
    )
    val textMuted = AstraTheme.textSecondary
    NavigationBarItem(
        selected = selected,
        onClick = { onNavigate(item.route) },
        icon = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) TorXPrimarySoft
                            else Color.Transparent
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) TorXPrimary else textMuted
                    )
                }
                Text(
                    item.label,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) TorXPrimary else textMuted,
                    maxLines = 1
                )
            }
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = TorXPrimary,
            unselectedIconColor = textMuted,
            indicatorColor = Color.Transparent
        )
    )
}

data class NavRoute(val route: String, val icon: ImageVector, val label: String)
