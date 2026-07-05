package com.astramesh.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.identity.IdentityManager
import com.astramesh.app.network.MessageRouter
import com.astramesh.app.network.NearbyConnectionManager
import com.astramesh.app.network.TorManager
import com.astramesh.app.ui.theme.*

@Composable
fun MainScreen(
    identityManager: IdentityManager,
    rootNavController: NavHostController,
    db: AppDatabase,
    nearbyManager: NearbyConnectionManager,
    torManager: TorManager,
    messageRouter: MessageRouter
) {
    val bottomNavController = rememberNavController()
    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in listOf("chats", "contacts", "universe", "security", "settings")) {
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
                    navController = rootNavController, // Chat opens full screen in root
                    db = db,
                    nearbyManager = nearbyManager,
                    torManager = torManager,
                    messageRouter = messageRouter
                )
            }
            composable("contacts") {
                // Placeholder for Contacts Screen
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("Contacts", color = TextPrimary)
                }
            }
            composable("universe") {
                NetworkUniverseScreen(
                    nearbyManager = nearbyManager,
                    torManager = torManager,
                    db = db
                )
            }
            composable("security") {
                SecurityCenterScreen()
            }
            composable("settings") {
                val onionAddress by torManager.onionAddress.collectAsState()
                SettingsScreen(
                    identityManager = identityManager,
                    navController = rootNavController,
                    onionAddress = onionAddress,
                    db = db
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
            .padding(16.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .glassmorphism(cornerRadius = 24.dp, backgroundColor = SurfaceDark.copy(alpha = 0.8f)),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp
    ) {
        val items = listOf(
            NavRoute("chats", "💬", "Chats"),
            NavRoute("contacts", "👥", "Contacts"),
            NavRoute("universe", "🌌", "Network"),
            NavRoute("security", "🛡️", "Security"),
            NavRoute("settings", "⚙️", "Settings")
        )

        items.forEach { item ->
            val selected = currentRoute == item.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = { 
                    Text(
                        text = item.icon, 
                        fontSize = if (selected) 24.sp else 20.sp,
                        color = if (selected) MaterialTheme.colorScheme.primary else TextMuted
                    ) 
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = TextMuted,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

data class NavRoute(val route: String, val icon: String, val label: String)
