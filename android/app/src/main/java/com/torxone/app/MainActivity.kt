package com.torxone.app

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.torxone.app.service.TorXOneService
import com.torxone.app.ui.components.UpdateDialog
import com.torxone.app.ui.screens.*
import com.torxone.app.ui.theme.TorXOneTheme
import com.torxone.app.ui.theme.DeepBlack
import com.torxone.app.updater.GitHubUpdater
import com.torxone.app.updater.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.widget.Toast
import com.torxone.app.call.CallDirection
import com.torxone.app.call.CallUiState
import com.torxone.app.call.activeCallId
import com.torxone.app.call.isActiveCall

class MainActivity : androidx.fragment.app.FragmentActivity() {

    private var permissionsGranted by mutableStateOf(false)
    private var serviceBound by mutableStateOf(false)
    private var meshService: TorXOneService? by mutableStateOf(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as TorXOneService.LocalBinder
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
            val perms = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_ADVERTISE
                perms += Manifest.permission.BLUETOOTH_CONNECT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms += Manifest.permission.NEARBY_WIFI_DEVICES
                }
            } else {
                perms += Manifest.permission.ACCESS_FINE_LOCATION
                perms += Manifest.permission.ACCESS_COARSE_LOCATION
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

        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        permissionsGranted = allGranted
        if (allGranted) {
            startAndBindService()
        } else {
            requestNearbyPermissions()
        }

        setContent {
            val settingsManager = remember { com.torxone.app.data.SettingsManager(this@MainActivity) }
            val darkMode by settingsManager.darkModeFlow.collectAsState(initial = false)
            val reduceMotion by settingsManager.reduceMotionFlow.collectAsState(initial = false)
            val showTransportIcons by settingsManager.showTransportIconsFlow.collectAsState(initial = true)
            val appLockEnabled by settingsManager.appLockEnabledFlow.collectAsState(initial = false)

            TorXOneTheme(
                useAmoledTheme = darkMode,
                reduceMotion = reduceMotion,
                showTransportIcons = showTransportIcons
            ) {
                com.torxone.app.debug.UIMicroAuditOverlay {
                    Surface(modifier = Modifier.fillMaxSize(), color = DeepBlack) {
                        if (!permissionsGranted) {
                        PermissionsScreen(onRetry = { requestNearbyPermissions() })
                        return@Surface
                    }

                    val service = meshService
                    if (service == null || !serviceBound) {
                        // Show a loading state while service binds
                        return@Surface
                    }

                    // --- APP LOCK GATING ---
                    LaunchedEffect(appLockEnabled) {
                        service.identityManager.isAppLockEnabled = appLockEnabled
                    }
                    
                    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                    val scope = rememberCoroutineScope()
                    var forceRecompose by remember { mutableStateOf(0) }
                    
                    DisposableEffect(lifecycleOwner, appLockEnabled) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && appLockEnabled) {
                                service.identityManager.lockSession()
                                forceRecompose++
                            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                                forceRecompose++
                            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    service.identityManager.isAwaitingExternalActivity = false
                                }, 500)
                                forceRecompose++
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    // This forces recompose when lifecycle changes
                    forceRecompose.let { } 

                    val isLocked = appLockEnabled && !service.identityManager.isSessionUnlocked
                    
                    if (isLocked) {
                        val biometricAuthManager = remember { com.torxone.app.security.BiometricAuthManager(this@MainActivity) }
                        com.torxone.app.ui.screens.LockScreen(
                            biometricAuthManager = biometricAuthManager,
                            onUnlock = {
                                service.identityManager.unlockSession()
                                forceRecompose++
                                scope.launch(Dispatchers.IO) {
                                    service.messageRouter.processEncryptedBacklog()
                                }
                            }
                        )
                        return@Surface
                    }
                    // --- END APP LOCK GATING ---

                    val navController = rememberNavController()
                    val hasIdentity = remember { service.identityManager.hasIdentity() }

                    // Prompt existing users to setup Biometric / Face lock upon app update
                    val appLockUpdateNotified by settingsManager.appLockUpdateNotifiedFlow.collectAsState(initial = false)
                    var showAppLockUpdatePrompt by remember { mutableStateOf(false) }

                    LaunchedEffect(hasIdentity, appLockEnabled, appLockUpdateNotified) {
                        if (hasIdentity && !appLockEnabled && !appLockUpdateNotified) {
                            com.torxone.app.service.NotificationHelper.showAppLockSetupNotification(this@MainActivity)
                            showAppLockUpdatePrompt = true
                            settingsManager.setAppLockUpdateNotified(true)
                        }
                    }

                    LaunchedEffect(intent) {
                        if (intent?.getBooleanExtra("open_app_lock_setup", false) == true) {
                            showAppLockUpdatePrompt = true
                        }
                    }

                    if (showAppLockUpdatePrompt) {
                        var setupPassword by remember { mutableStateOf("") }
                        var setupError by remember { mutableStateOf<String?>(null) }
                        val biometricAuthManager = remember { com.torxone.app.security.BiometricAuthManager(this@MainActivity) }

                        AlertDialog(
                            onDismissRequest = { showAppLockUpdatePrompt = false },
                            title = { Text("Security Update: Protect TorX One") },
                            text = {
                                Column {
                                    Text(
                                        "Set up Biometric or Face Lock to secure your messages and private identity against unauthorized access.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    OutlinedTextField(
                                        value = setupPassword,
                                        onValueChange = { setupPassword = it },
                                        label = { Text("Create Backup Password (min 4 chars)") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (setupError != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(setupError ?: "", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        try {
                                            biometricAuthManager.setupAppLockWithPassword(setupPassword)
                                            scope.launch {
                                                settingsManager.setAppLockEnabled(true)
                                            }
                                            showAppLockUpdatePrompt = false
                                            Toast.makeText(this@MainActivity, "App Lock Enabled", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            setupError = "Failed to enable: ${e.message}"
                                        }
                                    },
                                    enabled = setupPassword.length >= 4
                                ) {
                                    Text("Enable Lock")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showAppLockUpdatePrompt = false }) {
                                    Text("Maybe Later")
                                }
                            }
                        )
                    }
                    
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
                                    onInstallerLaunched = {
                                        isDownloadingUpdate = false
                                        updateInfo = null
                                        Toast.makeText(this@MainActivity, "Android installer opened. Confirm the update to finish.", Toast.LENGTH_LONG).show()
                                    },
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
                        val conversationType = intent.getStringExtra("conversation_type") ?: "direct"
                        if (openChatKey != null) {
                            com.torxone.app.service.NotificationHelper.clearContactNotifications(this@MainActivity, openChatKey)
                            navController.navigate("chat/$conversationType/$openChatKey")
                        }
                    }

                    val animDuration = if (reduceMotion) 0 else 300
                    Box(modifier = Modifier.fillMaxSize()) {
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
                            val profileCacheManager = remember { com.torxone.app.identity.profile.ProfileCacheManagerImpl(this@MainActivity) }
                            val imageProcessor = remember { com.torxone.app.media.ImageProcessor(this@MainActivity) }
                            val profileRepository = remember(service.db, service.identityManager) {
                                com.torxone.app.identity.profile.ProfileRepositoryImpl(
                                    service.db.profileDao(),
                                    service.identityManager,
                                    profileCacheManager,
                                    imageProcessor
                                )
                            }
                            val profileViewModel: com.torxone.app.ui.screens.ProfileViewModel =
                                androidx.lifecycle.viewmodel.compose.viewModel(
                                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                            return com.torxone.app.ui.screens.ProfileViewModel(profileRepository) as T
                                        }
                                    }
                                )
                            val identityQrPayload = remember(service.identityManager, service.torManager.onionAddress.value) {
                                service.identityManager.loadIdentity()?.let { identity ->
                                    com.torxone.app.crypto.CryptoManager.createContactString(
                                        identity,
                                        service.torManager.onionAddress.value.ifBlank {
                                            service.identityManager.loadOnionAddress()
                                        }
                                    )
                                } ?: ""
                            }
                            val profileOnionAddress = service.torManager.onionAddress.value.ifBlank {
                                service.identityManager.loadOnionAddress().orEmpty()
                            }
                            val profileFingerprint = service.identityManager.loadIdentity()?.let { identity ->
                                com.torxone.app.crypto.CryptoManager.toHex(identity.signingPublicKey)
                            }.orEmpty()
                            com.torxone.app.ui.screens.ProfileScreen(
                                navController = navController,
                                viewModel = profileViewModel,
                                identityQrPayload = identityQrPayload,
                                onionAddress = profileOnionAddress,
                                identityFingerprint = profileFingerprint
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
                        composable("create_group") {
                            com.torxone.app.ui.screens.CreateGroupScreen(
                                navController = navController,
                                db = service.db
                            )
                        }
                        composable("chat/{conversationType}/{contactKey}") { backStackEntry ->
                            val conversationType = backStackEntry.arguments?.getString("conversationType") ?: "direct"
                            val contactKey = backStackEntry.arguments?.getString("contactKey")
                                ?: return@composable
                            ChatScreen(
                                contactKey = contactKey,
                                conversationType = conversationType,
                                navController = navController,
                                db = service.db,
                                nearbyManager = service.nearbyManager,
                                messageRouter = service.messageRouter,
                                mediaTransferManager = service.mediaTransferManager
                            )
                        }
                        composable("contact_profile/{contactKey}") { backStackEntry ->
                            val contactKey = backStackEntry.arguments?.getString("contactKey")
                                ?: return@composable
                            com.torxone.app.ui.screens.ContactProfileScreen(
                                navController = navController,
                                contactKey = contactKey,
                                db = service.db
                            )
                        }
                        composable("group_info/{groupId}") { backStackEntry ->
                            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                            com.torxone.app.ui.screens.GroupInfoScreen(groupId, navController, service.db)
                        }
                        composable("settings") {
                            val onionAddress by service.torManager.onionAddress.collectAsState()
                            SettingsScreen(
                                identityManager = service.identityManager,
                                navController = navController,
                                onionAddress = onionAddress,
                                db = service.db,
                                settingsManager = service.settingsManager,
                                onNavigateBack = {
                                    if (!navController.navigateUp()) {
                                        navController.popBackStack()
                                    }
                                }
                            )
                        }
                        composable("battery_performance") {
                            com.torxone.app.ui.screens.BatteryPerformanceScreen(
                                settingsManager = service.settingsManager,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("debug") {
                            DebugScreen(
                                navController = navController,
                                torManager = service.torManager
                            )
                        }
                        composable("mesh_dashboard") {
                            com.torxone.app.ui.screens.MeshDashboardScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("scan_qr") {
                            com.torxone.app.ui.screens.ScanQrScreen(
                                navController = navController,
                                db = service.db,
                                groupManager = service.groupManager,
                                identityManager = service.identityManager
                            )
                        }
                    }

                    meshService?.let { activeService ->
                        val callState by activeService.callManager.stateStore.state.collectAsState()
                        var currentCallId by remember { mutableStateOf<String?>(null) }
                        var isCallMinimized by remember { mutableStateOf(false) }

                        LaunchedEffect(callState) {
                            when (callState) {
                                is com.torxone.app.call.CallUiState.Idle,
                                is com.torxone.app.call.CallUiState.Ended -> {
                                    isCallMinimized = false
                                    currentCallId = null
                                }
                                is com.torxone.app.call.CallUiState.Unavailable -> {
                                    // Let dialog show if not minimized
                                }
                                else -> {
                                    val id = callState.activeCallId
                                    if (id != null && id != currentCallId) {
                                        currentCallId = id
                                        isCallMinimized = false
                                    }
                                }
                            }
                        }

                        LaunchedEffect(intent) {
                            val openCallId = intent?.getStringExtra("open_call")
                            if (openCallId != null && callState.isActiveCall) {
                                isCallMinimized = false
                            }
                        }

                        com.torxone.app.ui.screens.CallOverlayHost(
                            callState = callState,
                            isCallMinimized = isCallMinimized,
                            onMinimize = { isCallMinimized = true },
                            onExpand = { isCallMinimized = false },
                            onAccept = { activeService.callManager.acceptIncomingCall() },
                            onReject = { activeService.callManager.rejectIncomingCall() },
                            onEnd = {
                                isCallMinimized = false
                                currentCallId = null
                                activeService.callManager.endCall()
                            },
                            onToggleMute = { activeService.callManager.toggleMute() },
                            onToggleSpeaker = { activeService.callManager.toggleSpeaker() },
                            onDismissUnavailable = {
                                isCallMinimized = false
                                currentCallId = null
                                activeService.callManager.stateStore.reset()
                            }
                        )
                    }
                    }
                }
            }
        }
    }
}

    private fun startAndBindService() {
        TorXOneService.start(this)
        bindService(
            Intent(this, TorXOneService::class.java),
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
        // NOTE: We do NOT stop TorXOneService here.
        // The service keeps running in the background so Tor stays alive.
    }
}
