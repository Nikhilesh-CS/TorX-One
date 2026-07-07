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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.astramesh.app.call.CallDirection
import com.astramesh.app.call.CallUiState

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
                                    navController.navigate("main") {
                                        popUpTo("setup") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }
                        composable("profile") {
                            val profileCacheManager = remember { com.astramesh.app.identity.profile.ProfileCacheManagerImpl(this@MainActivity) }
                            val imageProcessor = remember { com.astramesh.app.media.ImageProcessor(this@MainActivity) }
                            val profileRepository = remember(service.db, service.identityManager) {
                                com.astramesh.app.identity.profile.ProfileRepositoryImpl(
                                    service.db.profileDao(),
                                    service.identityManager,
                                    profileCacheManager,
                                    imageProcessor
                                )
                            }
                            val profileViewModel: com.astramesh.app.ui.screens.ProfileViewModel =
                                androidx.lifecycle.viewmodel.compose.viewModel(
                                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                            return com.astramesh.app.ui.screens.ProfileViewModel(profileRepository) as T
                                        }
                                    }
                                )
                            val identityQrPayload = remember(service.identityManager, service.torManager.onionAddress.value) {
                                service.identityManager.loadIdentity()?.let { identity ->
                                    com.astramesh.app.crypto.CryptoManager.createContactString(
                                        identity,
                                        service.torManager.onionAddress.value.ifBlank {
                                            service.identityManager.loadOnionAddress()
                                        }
                                    )
                                } ?: ""
                            }
                            com.astramesh.app.ui.screens.ProfileScreen(
                                navController = navController,
                                viewModel = profileViewModel,
                                identityQrPayload = identityQrPayload
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

                    meshService?.let { activeService ->
                        val callState by activeService.callManager.stateStore.state.collectAsState()
                        CallOverlay(
                            state = callState,
                            onAccept = { activeService.callManager.acceptIncomingCall() },
                            onReject = { activeService.callManager.rejectIncomingCall() },
                            onEnd = { activeService.callManager.endCall() },
                            onDismissEnded = { activeService.callManager.stateStore.reset() }
                        )
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

@Composable
private fun CallOverlay(
    state: CallUiState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onDismissEnded: () -> Unit
) {
    when (state) {
        CallUiState.Idle -> Unit
        is CallUiState.Ringing -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text(if (state.direction == CallDirection.INCOMING) "Incoming audio call" else "Calling...") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.peerName)
                        Text("Encrypted signaling. Audio stream uses WebRTC over the local route.")
                    }
                },
                confirmButton = {
                    if (state.direction == CallDirection.INCOMING) {
                        Button(onClick = onAccept) { Text("Accept") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = if (state.direction == CallDirection.INCOMING) onReject else onEnd) {
                        Text(if (state.direction == CallDirection.INCOMING) "Reject" else "Cancel")
                    }
                },
                shape = RoundedCornerShape(24.dp)
            )
        }
        is CallUiState.Connecting -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Connecting audio call") },
                text = { Text(state.peerName) },
                confirmButton = {
                    TextButton(onClick = onEnd) { Text("End") }
                }
            )
        }
        is CallUiState.Connected -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Audio call connected") },
                text = { Text(state.peerName) },
                confirmButton = {
                    Button(onClick = onEnd) { Text("End call") }
                }
            )
        }
        is CallUiState.Ended -> {
            AlertDialog(
                onDismissRequest = onDismissEnded,
                title = { Text("Call ended") },
                text = { Text(state.reason) },
                confirmButton = {
                    TextButton(onClick = onDismissEnded) { Text("Close") }
                }
            )
        }
        is CallUiState.Unavailable -> {
            AlertDialog(
                onDismissRequest = onDismissEnded,
                title = { Text("Call unavailable") },
                text = { Text(state.reason) },
                confirmButton = {
                    TextButton(onClick = onDismissEnded) { Text("Close") }
                }
            )
        }
    }
}
