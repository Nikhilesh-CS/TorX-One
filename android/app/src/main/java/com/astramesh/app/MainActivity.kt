package com.astramesh.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.astramesh.app.service.AstraMeshService
import com.astramesh.app.ui.components.UpdateDialog
import com.astramesh.app.ui.screens.*
import com.astramesh.app.ui.theme.AstraMeshTheme
import com.astramesh.app.ui.theme.DeepBlack
import com.astramesh.app.updater.GitHubUpdater
import com.astramesh.app.updater.UpdateInfo
import kotlinx.coroutines.launch
import android.widget.Toast

class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)
    private var serviceBound by mutableStateOf(false)
    private var meshService: AstraMeshService? by mutableStateOf(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as AstraMeshService.LocalBinder
            meshService = localBinder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            serviceBound = false
        }
    }

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
                perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
        if (permissionsGranted) startAndBindService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (permissionsGranted) {
            startAndBindService()
        } else {
            requestNearbyPermissions()
        }

        setContent {
            val settingsManager = remember { com.astramesh.app.data.SettingsManager(this@MainActivity) }
            val darkMode by settingsManager.darkModeFlow.collectAsState(initial = true)
            val reduceMotion by settingsManager.reduceMotionFlow.collectAsState(initial = false)
            val showTransportIcons by settingsManager.showTransportIconsFlow.collectAsState(initial = true)

            AstraMeshTheme(
                useAmoledTheme = darkMode,
                reduceMotion = reduceMotion,
                showTransportIcons = showTransportIcons
            ) {
                com.astramesh.app.debug.UIMicroAuditOverlay {
                    Surface(modifier = Modifier.fillMaxSize(), color = DeepBlack) {
                        if (!permissionsGranted) {
                        PermissionsScreen(onRetry = { requestNearbyPermissions() })
                        return@Surface
                    }

                    BatteryOptimizationPrompt()

                    val service = meshService
                    if (service == null || !serviceBound) {
                        // Show a loading state while service binds
                        return@Surface
                    }

                    val navController = rememberNavController()
                    val hasIdentity = remember { service.identityManager.hasIdentity() }
                    
                    val updater = remember { GitHubUpdater(this@MainActivity) }
                    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
                    var isDownloadingUpdate by remember { mutableStateOf(false) }
                    
                    LaunchedEffect(Unit) {
                        val info = updater.checkForUpdates(manual = false)
                        if (info != null && info.isUpdateAvailable) {
                            updateInfo = info
                        }
                    }
                    
                    updateInfo?.let { info ->
                        UpdateDialog(
                            updateInfo = info,
                            isDownloading = isDownloadingUpdate,
                            onConfirm = {
                                isDownloadingUpdate = true
                                updater.downloadAndInstallUpdate(
                                    updateInfo = info,
                                    onProgress = { },
                                    onComplete = { isDownloadingUpdate = false; updateInfo = null },
                                    onError = { err -> 
                                        isDownloadingUpdate = false
                                        updateInfo = null
                                        Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show() 
                                    }
                                )
                            },
                            onDismiss = { updateInfo = null }
                        )
                    }

                    LaunchedEffect(intent) {
                        val openChatKey = intent.getStringExtra("open_chat")
                        if (openChatKey != null) {
                            com.astramesh.app.service.NotificationHelper.clearContactNotifications(this@MainActivity, openChatKey)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                service.db.messageDao().markMessagesAsRead(openChatKey)
                            }
                            navController.navigate("chat/$openChatKey")
                        }
                    }

                    val animDuration = if (reduceMotion) 0 else 300
                    NavHost(
                        navController = navController,
                        startDestination = if (hasIdentity) "main" else "setup",
                        enterTransition = { fadeIn(animationSpec = tween(animDuration)) + slideInHorizontally { if (reduceMotion) 0 else it / 4 } },
                        exitTransition = { fadeOut(animationSpec = tween(animDuration)) },
                        popEnterTransition = { fadeIn(animationSpec = tween(animDuration)) + slideInHorizontally { if (reduceMotion) 0 else -it / 4 } },
                        popExitTransition = { fadeOut(animationSpec = tween(animDuration)) }
                    ) {
                        composable("setup") {
                            SetupScreen(
                                identityManager = service.identityManager,
                                onIdentityCreated = {
                                    service.configureAndStart()
                                }
                            )
                        }
                        composable("profile") {
                            com.astramesh.app.ui.screens.ProfileScreen(
                                navController = navController
                            )
                        }
                        composable("main") {
                            LaunchedEffect(Unit) {
                                service.configureAndStart()
                            }
                            MainScreen(
                                identityManager = service.identityManager,
                                rootNavController = navController,
                                db = service.db,
                                nearbyManager = service.nearbyManager,
                                torManager = service.torManager,
                                messageRouter = service.messageRouter,
                                settingsManager = service.settingsManager
                            )
                        }
                        composable("chat/{contactKey}") { backStackEntry ->
                            val contactKey = backStackEntry.arguments?.getString("contactKey")
                                ?: return@composable
                            ChatScreen(
                                contactKey = contactKey,
                                navController = navController,
                                db = service.db,
                                nearbyManager = service.nearbyManager,
                                messageRouter = service.messageRouter,
                                mediaTransferManager = service.mediaTransferManager
                            )
                        }
                        composable("settings") {
                            val onionAddress by service.torManager.onionAddress.collectAsState()
                            SettingsScreen(
                                identityManager = service.identityManager,
                                navController = navController,
                                onionAddress = onionAddress,
                                db = service.db,
                                settingsManager = service.settingsManager
                            )
                        }
                        composable("debug") {
                            DebugScreen(
                                navController = navController,
                                torManager = service.torManager
                            )
                        }
                        composable("mesh_dashboard") {
                            com.astramesh.app.ui.screens.MeshDashboardScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        }
                    }
                }
            }
        }
    }

    private fun startAndBindService() {
        AstraMeshService.start(this)
        bindService(
            Intent(this, AstraMeshService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun requestNearbyPermissions() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            permissionsGranted = true
            startAndBindService()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        // NOTE: We do NOT stop AstraMeshService here.
        // The service keeps running in the background so Tor stays alive.
    }
}
