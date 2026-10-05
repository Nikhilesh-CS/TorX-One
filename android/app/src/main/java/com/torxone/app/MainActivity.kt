package com.torxone.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.torxone.app.contacts.ContactsViewModel
import com.torxone.app.data.entity.ContactEntity
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.ConversationType
import com.torxone.app.identity.TorXIdentity
import com.torxone.app.media.RealVoiceNoteRecorder
import com.torxone.app.profile.SettingsViewModel
import com.torxone.app.profile.AppSettingsRepository
import com.torxone.app.ui.components.ContactInviteDialog
import com.torxone.app.ui.permissions.PermissionHelper
import com.torxone.app.ui.screens.*
import com.torxone.app.ui.security.AppLockManager
import com.torxone.app.ui.security.AppLockOverlay
import com.torxone.app.ui.theme.TorXOneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

class MainActivity : FragmentActivity() {
    val intentFlow = kotlinx.coroutines.flow.MutableStateFlow<android.content.Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentFlow.value = intent
        enableEdgeToEdge()

        setContent {
            val app = applicationContext as TorXOneApplication
            val themeMode by app.settingsRepository.themeMode.collectAsState(initial = "SYSTEM")
            val themeSource by app.settingsRepository.themeSource.collectAsState(initial = "TORX")
            val accentId by app.settingsRepository.accentId.collectAsState(initial = "SAGE")
            val fontSize by app.settingsRepository.fontSize.collectAsState(initial = "MEDIUM")
            val isDark = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemInDarkTheme()
            }

            DisposableEffect(isDark) {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
                onDispose { }
            }

            TorXOneTheme(
                darkTheme = isDark,
                dynamicColor = themeSource == "SYSTEM",
                accentId = accentId,
                fontSize = fontSize
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TorXOneApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentFlow.value = intent
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorXOneApp() {
    val context = LocalContext.current
    val app = context.applicationContext as TorXOneApplication
    val coroutineScope = rememberCoroutineScope()

    // Check first-run state
    val onboardingComplete by app.settingsRepository.isOnboardingComplete.collectAsState(initial = null)

    // Don't render anything until we know the onboarding state (avoid flash)
    val resolved = onboardingComplete ?: return

    val appInitState by app.initState.collectAsState()
    // Android can deny foreground promotion during a background service restart.
    // Retry only after initialization has completed and this activity is visible.
    val coreLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(coreLifecycleOwner, appInitState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && appInitState is TorXOneApplication.AppInitState.Ready) {
                runCatching { com.torxone.app.service.TorXCoreService.start(context) }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        android.util.Log.w("MainActivity", "Core foreground service start deferred")
                    }
            }
        }
        coreLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { coreLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (resolved && appInitState is TorXOneApplication.AppInitState.Failed) {
        val error = (appInitState as TorXOneApplication.AppInitState.Failed).error
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TorX One could not safely initialize.", style = MaterialTheme.typography.titleMedium)
                Text("TorX One couldn't securely open local data. Error code: INIT-DB-01")
                Button(onClick = { (context as? android.app.Activity)?.recreate() }) { Text("Retry") }
            }
        }
        return
    }
    if (resolved && appInitState is TorXOneApplication.AppInitState.Initializing) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val aliases by remember(app.database) { app.database.featureDao().observeAliases() }
        .collectAsState(initial = emptyList())
    val localNames = remember(aliases) { aliases.associate { it.contactId to it.alias } }

    val launchNavigateTo = (context as? android.app.Activity)?.intent?.getStringExtra("navigate_to")
    var currentScreen by remember(resolved) {
        val launchConvId = (context as? android.app.Activity)?.intent?.getStringExtra("conversationId")
        if (launchNavigateTo == "active_call") {
            mutableStateOf<Screen>(Screen.ActiveCall)
        } else if (!launchConvId.isNullOrBlank()) {
            mutableStateOf<Screen>(Screen.Chat(launchConvId, "Chat"))
        } else if (!resolved) {
            mutableStateOf<Screen>(Screen.Landing)
        } else {
            mutableStateOf<Screen>(Screen.ConversationList)
        }
    }
    var showInviteDialog by remember { mutableStateOf(false) }
    val appearanceRepository = remember(context) { com.torxone.app.ui.appearance.ChatAppearanceRepository(context) }
    LaunchedEffect(appearanceRepository) {
        try { appearanceRepository.cleanUnusedAssets() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { android.util.Log.w("MainActivity", "Wallpaper cleanup deferred") }
    }
    var showAppearanceEditor by remember { mutableStateOf(false) }
    var appearanceConversationId by remember { mutableStateOf<String?>(null) }
    var clearScheduledSource by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Ensure identity exists on startup (only when past onboarding)
    if (resolved) {
        LaunchedEffect(Unit) {
            val name = app.settingsRepository.getDisplayName().ifEmpty { "Me" }
            app.identityRepository.ensureIdentity(name)
        }
    }

    val contactsViewModel: ContactsViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
        ContactsViewModel(
            database = app.database,
            identityRepository = app.identityRepository,
            sessionCrypto = app.sessionCrypto,
            sessionStore = app.sessionStore,
            connectionManager = app.connectionManager,
            agent = app.agent,
            nearbyTransport = app.nearbyTransport,
            torRouteManager = app.torRouteManager,
            peerTorEndpoints = app.peerTorEndpoints,
            localOnionAddress = { app.onionEndpointManager.onionAddress() },
            keyProtector = app.keyProtector
        )
    }

    val conversationListViewModel: com.torxone.app.conversations.ConversationListViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
        com.torxone.app.conversations.ConversationListViewModel(
            chatService = app.chatService,
            messageDao = app.database.messageDao(),
            featureDao = app.database.featureDao(),
            contactDao = app.database.contactDao()
        )
    }

    val settingsViewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
        SettingsViewModel(
            settingsRepo = app.settingsRepository,
            chatService = app.chatService,
            identityRepo = app.identityRepository
        )
    }

    val callViewModel: com.torxone.app.calls.CallViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
        com.torxone.app.calls.CallViewModel(
            callManager = app.callManager,
            contactDao = app.database.contactDao(),
            featureDao = app.database.featureDao()
        )
    }

    val fragmentActivity = context as? FragmentActivity
    val settingsState by settingsViewModel.uiState.collectAsState()
    var isAppUnlocked by rememberSaveable { mutableStateOf(false) }
    var lockErrorMessage by remember { mutableStateOf<String?>(null) }

    // Screen Security: enforce FLAG_SECURE on window
    LaunchedEffect(settingsState.screenSecurityEnabled) {
        if (fragmentActivity != null) {
            AppLockManager.setScreenSecurity(fragmentActivity, settingsState.screenSecurityEnabled)
        }
    }

    // App Lock: track background timeout and reset unlocked state (M31)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, resolved, isAppUnlocked, settingsState.appLockEnabled) {
        fun updatePresence() {
            app.presenceService.setForeground(resolved && (!settingsState.appLockEnabled || isAppUnlocked) &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        val presenceObserver = LifecycleEventObserver { _, _ -> updatePresence() }
        lifecycleOwner.lifecycle.addObserver(presenceObserver)
        updatePresence()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(presenceObserver)
            app.presenceService.setForeground(false)
        }
    }
    var backgroundTimestamp by remember { mutableLongStateOf(0L) }
    SideEffect {
        AppSettingsRepository.appLockGate = {
            com.torxone.app.profile.AppUnlockPolicy.isAuthorized(isAppUnlocked, backgroundTimestamp,
                settingsState.appLockTimeoutMs, android.os.SystemClock.elapsedRealtime())
        }
    }

    DisposableEffect(lifecycleOwner, settingsState.appLockEnabled, settingsState.appLockTimeoutMs) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    backgroundTimestamp = android.os.SystemClock.elapsedRealtime()
                }
                Lifecycle.Event.ON_START -> {
                    app.applicationScope.launch {
                        runCatching {
                            app.securityPolicyService.cleanupDue()
                            app.scheduledMessageService.recover()
                        }.onFailure {
                            if (it is kotlinx.coroutines.CancellationException) throw it
                            android.util.Log.w("MainActivity", "Local feature recovery will retry")
                        }
                    }
                    if (settingsState.appLockEnabled && backgroundTimestamp > 0L) {
                        if (!com.torxone.app.profile.AppUnlockPolicy.isAuthorized(isAppUnlocked, backgroundTimestamp,
                                settingsState.appLockTimeoutMs, android.os.SystemClock.elapsedRealtime())) {
                            isAppUnlocked = false
                        }
                    }
                    backgroundTimestamp = 0L
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // App Lock: trigger biometric prompt on resume / launch when locked
    LaunchedEffect(settingsState.appLockEnabled, isAppUnlocked) {
        if (settingsState.appLockEnabled && !isAppUnlocked && fragmentActivity != null) {
            AppLockManager.promptUnlock(
                activity = fragmentActivity,
                onSuccess = {
                    isAppUnlocked = true
                    lockErrorMessage = null
                    app.applicationScope.launch {
                        runCatching { app.scheduledMessageService.recover() }.onFailure {
                            if (it is kotlinx.coroutines.CancellationException) throw it
                            android.util.Log.w("MainActivity", "Scheduled-message recovery will retry")
                        }
                    }
                },
                onError = { err ->
                    lockErrorMessage = err
                }
            )
        }
    }

    // Nearby transport permissions and notification permission have independent
    // lifecycles. A notification denial must never block encrypted networking.
    val nearbyPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val nearbyGranted = PermissionHelper.arePermissionsGranted(
            context, PermissionHelper.getNearbyPermissions()
        )
        if (nearbyGranted) {
            app.nearbyTransport.start()
            coroutineScope.launch {
                runCatching { app.torXRadioManager.start() }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        android.util.Log.w("MainActivity", "TorX Radio discovery unavailable")
                    }
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Notifications remain optional; transport state is untouched. */ }

    LaunchedEffect(resolved) {
        if (resolved) {
            val nearbyPermissions = PermissionHelper.getNearbyPermissions()
            if (PermissionHelper.arePermissionsGranted(context, nearbyPermissions)) {
                app.nearbyTransport.start()
                runCatching { app.torXRadioManager.start() }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        android.util.Log.w("MainActivity", "TorX Radio discovery unavailable")
                    }
            } else {
                nearbyPermissionsLauncher.launch(nearbyPermissions)
            }

            val notificationPermissions = PermissionHelper.getNotificationPermissions()
            if (notificationPermissions.isNotEmpty() &&
                !PermissionHelper.arePermissionsGranted(context, notificationPermissions)
            ) {
                notificationPermissionLauncher.launch(notificationPermissions)
            }
        }
    }

    // Navigation BackStack handling (M29)
    var screenStack by remember { mutableStateOf<List<Screen>>(emptyList()) }

    fun navigateTo(newScreen: Screen) {
        if (newScreen != currentScreen) {
            screenStack = screenStack + currentScreen
            currentScreen = newScreen
        }
    }

    fun navigateBack() {
        if (screenStack.isNotEmpty()) {
            val prev = screenStack.last()
            screenStack = screenStack.dropLast(1)
            currentScreen = prev
        } else {
            currentScreen = Screen.ConversationList
        }
    }

    BackHandler(enabled = screenStack.isNotEmpty() || (currentScreen != Screen.ConversationList && currentScreen != Screen.Landing)) {
        navigateBack()
    }

    val mainActivity = context as? MainActivity
    val currentIntent by mainActivity?.intentFlow?.collectAsState() ?: remember { mutableStateOf(null) }

    var pendingAnswerCallId by remember { mutableStateOf<String?>(null) }
    var pendingAnswerIsVideo by remember { mutableStateOf(false) }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val id = pendingAnswerCallId
        if (id != null) {
            val audioOk = results[android.Manifest.permission.RECORD_AUDIO] ?: PermissionHelper.isRecordAudioGranted(context)
            val cameraOk = if (pendingAnswerIsVideo) {
                results[android.Manifest.permission.CAMERA] ?: PermissionHelper.isCameraGranted(context)
            } else true
            if (audioOk && cameraOk) {
                coroutineScope.launch {
                    app.callManager.acceptCall(id)
                    app.callNotificationManager.cancelIncomingNotification()
                }
                navigateTo(Screen.ActiveCall)
            } else {
                coroutineScope.launch {
                    app.callManager.declineCall(id)
                    app.callNotificationManager.cancelIncomingNotification()
                }
                android.widget.Toast.makeText(
                    context,
                    if (!audioOk) "Microphone permission is required to answer this call."
                    else "Camera permission is required to answer this video call.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            pendingAnswerCallId = null
        }
    }

    LaunchedEffect(app.callManager) {
        app.callManager.activeCall.collect { session ->
            if (session?.state == com.torxone.app.calls.CallState.INCOMING_RINGING && currentScreen != Screen.ActiveCall) {
                navigateTo(Screen.ActiveCall)
            }
        }
    }

    LaunchedEffect(currentIntent) {
        val targetIntent = currentIntent ?: return@LaunchedEffect
        when (targetIntent.action) {
            "ACTION_ANSWER_CALL" -> {
                val callId = targetIntent.getStringExtra("callId") ?: return@LaunchedEffect
                val isVideo = targetIntent.getBooleanExtra("isVideo", false)
                val audioGranted = PermissionHelper.isRecordAudioGranted(context)
                val cameraGranted = PermissionHelper.isCameraGranted(context)
                val permsGranted = if (isVideo) audioGranted && cameraGranted else audioGranted
                if (!permsGranted) {
                    pendingAnswerCallId = callId
                    pendingAnswerIsVideo = isVideo
                    val needed = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
                    if (isVideo) needed.add(android.Manifest.permission.CAMERA)
                    callPermissionLauncher.launch(needed.toTypedArray())
                } else {
                    coroutineScope.launch {
                        app.callManager.acceptCall(callId)
                        app.callNotificationManager.cancelIncomingNotification()
                    }
                    navigateTo(Screen.ActiveCall)
                }
            }
            else -> {
                val nav = targetIntent.getStringExtra("navigate_to")
                val convId = targetIntent.getStringExtra("conversationId")
                if (nav == "active_call") {
                    navigateTo(Screen.ActiveCall)
                } else if (!convId.isNullOrBlank()) {
                    navigateTo(Screen.Chat(convId, "Chat"))
                }
            }
        }
    }

    // Render fullscreen AppLockOverlay if App Lock is active and unauthenticated
    if (settingsState.appLockEnabled && !isAppUnlocked) {
        AppLockOverlay(
            onUnlockClick = {
                if (fragmentActivity != null) {
                    AppLockManager.promptUnlock(
                        activity = fragmentActivity,
                        onSuccess = {
                            isAppUnlocked = true
                            lockErrorMessage = null
                        },
                        onError = { err ->
                            lockErrorMessage = err
                        }
                    )
                }
            },
            errorMessage = lockErrorMessage
        )
        return
    }

    val thumbnailReader = remember(app.database) { app.database.chatTimelineDao()::observeThumbnail }
    CompositionLocalProvider(com.torxone.app.ui.components.LocalChatThumbnailReader provides thumbnailReader) {
    when (val screen = currentScreen) {
        is Screen.Landing -> {
            LandingScreen(
                onComplete = { displayName ->
                        // Durably create the authoritative identity before marking onboarding complete.
                        app.identityRepository.createAndPublishIdentity(displayName)
                        app.settingsRepository.updateProfile(displayName = displayName)
                        app.settingsRepository.completeOnboarding()

                        screenStack = emptyList()
                        currentScreen = Screen.ConversationList
                }
            )
        }

        is Screen.ConversationList -> {
            val uiState by conversationListViewModel.uiState.collectAsState()

            ConversationListScreen(
                viewModel = conversationListViewModel,
                onConversationClick = { id ->
                    val clicked = uiState.conversations.find { it.conversationId == id }
                    navigateTo(
                        Screen.Chat(
                            conversationId = id,
                            contactName = clicked?.title ?: "Chat"
                        )
                    )
                },
                onArchivedClick = {
                    navigateTo(Screen.ArchivedList)
                },
                onScanQrClick = {
                    showInviteDialog = true
                },
                onNewGroupClick = {
                    navigateTo(Screen.NewGroup)
                },
                onSettingsClick = {
                    navigateTo(Screen.Settings)
                }
            )
        }

        is Screen.ArchivedList -> {
            val uiState by conversationListViewModel.uiState.collectAsState()

            com.torxone.app.ui.screens.ArchivedConversationsScreen(
                viewModel = conversationListViewModel,
                onConversationClick = { id ->
                    val clicked = uiState.archivedConversations.find { it.conversationId == id }
                    navigateTo(
                        Screen.Chat(
                            conversationId = id,
                            contactName = clicked?.title ?: "Chat"
                        )
                    )
                },
                onBackClick = {
                    navigateBack()
                }
            )
        }

        is Screen.Chat -> {
            var conversation by remember(screen.conversationId) { mutableStateOf<ConversationEntity?>(null) }
            var showConnection by remember(screen.conversationId) { mutableStateOf(false) }
            var showDisappearing by remember(screen.conversationId) { mutableStateOf(false) }
            var infoMessageId by remember(screen.conversationId) { mutableStateOf<String?>(null) }
            val connectionFlow = remember(screen.conversationId) {
                com.torxone.app.ui.connection.ConnectionUxReader(app.database, app.transportRouter,
                    app.torController.state, app.nearbyTransport, context,
                    isSendingPaused = { it in app.settingsRepository.pausedConversations.first() }).observe(screen.conversationId)
            }
            val connectionSnapshot by connectionFlow.collectAsState(initial = com.torxone.app.ui.connection.ConnectionUxSnapshot())
            val policy by remember(screen.conversationId) {
                app.securityPolicyService.dao.observePolicy(screen.conversationId)
            }.collectAsState(initial = null)
            if (showConnection) com.torxone.app.ui.components.ConnectionDashboardDialog(connectionSnapshot,
                onDismiss = { showConnection = false }, onRetry = { app.agent.triggerImmediateRetry(screen.conversationId) },
                onRetryTor = { app.torBootstrapManager.retry() },
                onToggleSendingPaused = { coroutineScope.launch {
                    app.settingsRepository.setConversationSendingPaused(screen.conversationId, !connectionSnapshot.sendingPaused)
                    if (connectionSnapshot.sendingPaused) app.agent.triggerImmediateRetry(screen.conversationId)
                } },
                onVerifyIdentity = if (conversation?.type != ConversationType.DIRECT) null else ({
                    showConnection = false
                    navigateTo(Screen.ContactInfo(screen.conversationId, screen.contactName))
                }))
            if (showDisappearing) com.torxone.app.ui.components.DisappearingTimerDialog(policy,
                onSave = { app.securityPolicyService.setTimer(screen.conversationId, it) },
                onDismiss = { showDisappearing = false })
            infoMessageId?.let { id ->
                com.torxone.app.ui.components.MessageInfoHost(app.database, id, screen.conversationId,
                    onDismiss = { infoMessageId = null }, onRetry = { app.agent.triggerImmediateRetry(screen.conversationId) })
            }
            val appearance by remember(screen.conversationId) {
                app.database.featureDao().observeAppearance(screen.conversationId)
            }.collectAsState(initial = null)
            val chatAppearance = appearance ?: com.torxone.app.data.entity.ConversationAppearanceEntity(screen.conversationId)
            val resolvedAppearance by remember(screen.conversationId) {
                appearanceRepository.observeConversation(screen.conversationId,
                    app.database.featureDao().observeAppearance(screen.conversationId))
                    .map { it as com.torxone.app.ui.appearance.ResolvedChatAppearance? }
            }.collectAsState(initial = null)
            var entryUnreadMessageId by remember(screen.conversationId) { mutableStateOf<String?>(null) }
            var entryUnreadCount by remember(screen.conversationId) { mutableIntStateOf(0) }
            var isLoadingEntrySnapshot by remember(screen.conversationId) { mutableStateOf(true) }
            // Track active conversation for notification suppression and unread counts
            DisposableEffect(screen.conversationId, lifecycleOwner) {
                fun syncActive() {
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        app.activeConversationTracker.setActiveConversation(screen.conversationId)
                        app.notificationManager.cancelForConversation(screen.conversationId)
                    } else if (app.activeConversationTracker.getActiveConversationId() == screen.conversationId) {
                        app.activeConversationTracker.clearActiveConversation()
                    }
                }
                val observer = LifecycleEventObserver { _, _ -> syncActive() }
                lifecycleOwner.lifecycle.addObserver(observer)
                syncActive()
                coroutineScope.launch {
                    try {
                        val entry = app.database.chatTimelineDao().unreadEntry(screen.conversationId)
                        entryUnreadMessageId = entry.firstMessageId
                        entryUnreadCount = entry.count
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        android.util.Log.w("MainActivity", "Unread context could not be loaded")
                    } finally { isLoadingEntrySnapshot = false }
                    val conv = app.database.conversationDao().getById(screen.conversationId)
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                        app.activeConversationTracker.getActiveConversationId() == screen.conversationId) {
                        if (conv?.type == ConversationType.GROUP) app.groupService.markGroupRead(screen.conversationId)
                        else app.chatService.markConversationRead(screen.conversationId)
                    }
                }
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    if (app.activeConversationTracker.getActiveConversationId() == screen.conversationId)
                        app.activeConversationTracker.clearActiveConversation()
                }
            }

            var isLoadingConversation by remember(screen.conversationId) { mutableStateOf(true) }
            var isLoadingIdentity by remember { mutableStateOf(true) }
            var localIdentity by remember { mutableStateOf<TorXIdentity?>(null) }

            LaunchedEffect(screen.conversationId) {
                isLoadingConversation = true
                conversation = app.database.conversationDao().getById(screen.conversationId)
                isLoadingConversation = false
            }

            LaunchedEffect(Unit) {
                isLoadingIdentity = true
                localIdentity = app.identityRepository.loadIdentity()
                isLoadingIdentity = false
            }

            if (isLoadingConversation || isLoadingIdentity || isLoadingEntrySnapshot || resolvedAppearance == null) {
                // Loading spinner while conversation entity and identity are loading (M40)
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(screen.contactName.ifBlank { "Chat" }) },
                            navigationIcon = {
                                IconButton(onClick = { navigateBack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (conversation != null && localIdentity != null) {
                val timeline = remember(screen.conversationId) {
                    com.torxone.app.ui.timeline.ChatTimelineRepository(app.database.chatTimelineDao(),
                        screen.conversationId, screen.messageId ?: entryUnreadMessageId)
                }
                DisposableEffect(timeline, lifecycleOwner) {
                    fun syncVisibility() { timeline.setVisible(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
                    val observer = LifecycleEventObserver { _, _ -> syncVisibility() }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    syncVisibility()
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer); timeline.setVisible(false) }
                }
                // Chat presentation models belong to the visible screen, not the whole activity.
                // Disposal stops database listeners and background auto-read for chats that were closed.
                val chatStore = remember(screen.conversationId) { androidx.lifecycle.ViewModelStore() }
                val chatOwner = remember(chatStore) { object : androidx.lifecycle.ViewModelStoreOwner {
                    override val viewModelStore = chatStore
                } }
                var flushClosingDraft by remember(chatStore) { mutableStateOf<(suspend () -> Unit)?>(null) }
                var stopClosingPresentation by remember(chatStore) { mutableStateOf<(() -> Unit)?>(null) }
                DisposableEffect(chatStore) { onDispose {
                    val flush = flushClosingDraft
                    stopClosingPresentation?.invoke()
                    app.applicationScope.launch {
                        try { flush?.invoke() }
                        catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            android.util.Log.w("MainActivity", "Closing chat draft could not be saved")
                        } finally {
                            withContext(Dispatchers.Main.immediate) { chatStore.clear() }
                        }
                    }
                } }
                if (conversation!!.type == ConversationType.GROUP) {
                    val groupVoiceRecorder = remember(context) { RealVoiceNoteRecorder(context) }
                    val groupViewModel: com.torxone.app.groups.GroupChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                        viewModelStoreOwner = chatOwner,
                        key = "group_${screen.conversationId}"
                    ) {
                        com.torxone.app.groups.GroupChatViewModel(
                            groupId = screen.conversationId,
                            conversationId = screen.conversationId,
                            localIdentityId = localIdentity!!.identityId,
                            groupService = app.groupService,
                            groupDao = app.database.groupDao(),
                            groupMemberDao = app.database.groupMemberDao(),
                            messageDao = app.database.messageDao(),
                            reactionDao = app.database.reactionDao(),
                            contactDao = app.database.contactDao(),
                            conversationDao = app.database.conversationDao(),
                            mediaDao = app.database.mediaDao(),
                            mediaService = app.mediaService,
                            localMessageStateDao = app.database.localMessageStateDao(),
                            voiceNoteRecorder = groupVoiceRecorder,
                            productivity = app.productivityService,
                            timeline = timeline
                        )
                    }

                    SideEffect {
                        stopClosingPresentation = groupViewModel::stopPresentation
                        flushClosingDraft = {
                            groupViewModel.awaitPendingActionsOnClose()
                            groupViewModel.persistPendingDraftOnClose()
                        }
                    }
                    ChatScreen(
                        viewModel = groupViewModel,
                        chatAppearance = resolvedAppearance?.config,
                        entryUnreadMessageId = entryUnreadMessageId,
                        entryUnreadCount = entryUnreadCount,
                        onEditAppearance = { appearanceConversationId = screen.conversationId; showAppearanceEditor = true },
                        initialMessageId = screen.messageId,
                        appearance = chatAppearance,
                        onSaveAppearance = app.productivityService::saveAppearance,
                        connectionSnapshot = connectionSnapshot,
                        onOpenConnection = { showConnection = true },
                        onDisappearing = { showDisappearing = true },
                        onSchedule = { text, reply ->
                            clearScheduledSource = if (text.isBlank()) null else { { groupViewModel.clearScheduledComposer(text, reply) } }
                            navigateTo(Screen.Scheduled(screen.conversationId, text, reply))
                        },
                        onSearch = { navigateTo(Screen.Search(screen.conversationId)) },
                        onForward = { navigateTo(Screen.Forward(it)) },
                        onBackClick = {
                            navigateBack()
                        },
                        onHeaderClick = {
                            navigateTo(Screen.GroupInfo(screen.conversationId))
                        }
                    )
                } else {
                    // DIRECT conversation: Load contact strictly by conversationId
                    var isLoadingContact by remember(screen.conversationId) { mutableStateOf(true) }
                    var contact by remember(screen.conversationId) { mutableStateOf<ContactEntity?>(null) }

                    LaunchedEffect(screen.conversationId) {
                        isLoadingContact = true
                        app.database.contactDao().observeAll().collect {
                            contact = app.database.contactDao().getByConversationId(screen.conversationId)
                            isLoadingContact = false
                        }
                    }

                    if (isLoadingContact) {
                        // Loading spinner while contact is loading (M40)
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = { Text(screen.contactName.ifBlank { "Chat" }) },
                                    navigationIcon = {
                                        IconButton(onClick = { navigateBack() }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                        }
                                    }
                                )
                            }
                        ) { padding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(padding),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (contact != null) {
                        if (!contact!!.isRemoteIdentityKnown) {
                            Scaffold(
                                topBar = {
                                    TopAppBar(
                                        title = { Text(screen.contactName.ifBlank { "Contact" }) },
                                        navigationIcon = {
                                            IconButton(onClick = { navigateBack() }) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                            }
                                        }
                                    )
                                }
                            ) { padding ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(padding)
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Text(
                                            text = "Security Upgrade Required",
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Security information for this contact needs to be refreshed. Reconnect or re-add this contact.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Button(onClick = { navigateBack() }) {
                                            Text("Back to Conversations")
                                        }
                                    }
                                }
                            }
                        } else {
                            val voiceRecorder = remember(context) { RealVoiceNoteRecorder(context) }
                            val peerNetworkIdentity = contact!!.remoteIdentityId
                            val viewModel: com.torxone.app.chat.ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                                viewModelStoreOwner = chatOwner,
                                key = "chat_${screen.conversationId}_${contact!!.relationshipId}"
                            ) {
                                com.torxone.app.chat.ChatViewModel(
                                    conversationId = screen.conversationId,
                                    relationshipId = contact!!.relationshipId,
                                    localIdentityId = localIdentity!!.identityId,
                                    recipientId = peerNetworkIdentity,
                                    contactName = screen.contactName,
                                    chatService = app.chatService,
                                    presenceService = app.presenceService,
                                    mediaService = app.mediaService,
                                    voiceNoteRecorder = voiceRecorder,
                                    productivity = app.productivityService,
                                    timeline = timeline
                                )
                            }

                        SideEffect {
                            stopClosingPresentation = viewModel::stopPresentation
                            flushClosingDraft = {
                                viewModel.awaitPendingActionsOnClose()
                                viewModel.persistPendingDraftOnClose()
                            }
                        }
                        ChatScreen(
                            viewModel = viewModel,
                            chatAppearance = resolvedAppearance?.config,
                            entryUnreadMessageId = entryUnreadMessageId,
                            entryUnreadCount = entryUnreadCount,
                            onEditAppearance = { appearanceConversationId = screen.conversationId; showAppearanceEditor = true },
                            initialMessageId = screen.messageId,
                        appearance = chatAppearance,
                        onSaveAppearance = app.productivityService::saveAppearance,
                        connectionSnapshot = connectionSnapshot,
                        onOpenConnection = { showConnection = true },
                        onDisappearing = { showDisappearing = true },
                        onRequestMessageInfo = { infoMessageId = it.logicalMessageId },
                        onRetryDelivery = { app.agent.triggerImmediateRetry(screen.conversationId) },
                        onSchedule = { text, reply ->
                            clearScheduledSource = if (text.isBlank()) null else { { viewModel.clearScheduledComposer(text, reply) } }
                            navigateTo(Screen.Scheduled(screen.conversationId, text, reply))
                        },
                            onSearch = { navigateTo(Screen.Search(screen.conversationId)) },
                            onForward = { navigateTo(Screen.Forward(it)) },
                            contactAvatar = contact?.avatarHash,
                            displayName = localNames[contact?.contactId] ?: contact?.displayName,
                            onBackClick = {
                                navigateBack()
                            },
                            onHeaderClick = {
                                navigateTo(Screen.ContactInfo(screen.conversationId, screen.contactName))
                            },
                            onStartVoiceCall = {
                                coroutineScope.launch {
                                    val started = runCatching { app.callManager.startOutgoingCall(
                                        conversationId = screen.conversationId,
                                        relationshipId = contact!!.relationshipId,
                                        peerIdentityId = peerNetworkIdentity,
                                        type = com.torxone.app.calls.CallType.VOICE
                                    ) }.getOrElse {
                                        android.widget.Toast.makeText(context, it.message ?: "Unable to start call", android.widget.Toast.LENGTH_LONG).show()
                                        null
                                    }
                                    if (started != null && app.callManager.activeCall.value?.callId == started.callId) navigateTo(Screen.ActiveCall)
                                    else android.widget.Toast.makeText(context, "Call could not start. Check this contact or finish the active call.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            },
                            onStartVideoCall = {
                                coroutineScope.launch {
                                    val started = runCatching { app.callManager.startOutgoingCall(
                                        conversationId = screen.conversationId,
                                        relationshipId = contact!!.relationshipId,
                                        peerIdentityId = peerNetworkIdentity,
                                        type = com.torxone.app.calls.CallType.VIDEO
                                    ) }.getOrElse {
                                        android.widget.Toast.makeText(context, it.message ?: "Unable to start call", android.widget.Toast.LENGTH_LONG).show()
                                        null
                                    }
                                    if (started != null && app.callManager.activeCall.value?.callId == started.callId) navigateTo(Screen.ActiveCall)
                                    else android.widget.Toast.makeText(context, "Call could not start. Check this contact or finish the active call.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }
                } else {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = { Text(screen.contactName.ifBlank { "Conversation" }) },
                                    navigationIcon = {
                                        IconButton(onClick = { navigateBack() }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                        }
                                    }
                                )
                            }
                        ) { padding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(padding),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    Text("Conversation Not Found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("This conversation or contact cannot be found.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = { navigateBack() }) {
                                        Text("Back to Chats")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(screen.contactName.ifBlank { "Chat" }) },
                            navigationIcon = {
                                IconButton(onClick = { navigateBack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text("Conversation Not Found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("This conversation cannot be found.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { navigateBack() }) {
                                Text("Back to Chats")
                            }
                        }
                    }
                }
            }
        }

        is Screen.ContactInfo -> {
            var showContactConnection by remember(screen.conversationId) { mutableStateOf(false) }
            var callBusy by remember(screen.conversationId) { mutableStateOf(false) }
            var callError by remember(screen.conversationId) { mutableStateOf<String?>(null) }
            val contactState = produceState<ContactEntity?>(initialValue = null, screen.conversationId) {
                app.database.contactDao().observeAll().collect { contacts ->
                    value = app.database.contactDao().getByConversationId(screen.conversationId)
                }
            }
            val conversationState = produceState<ConversationEntity?>(initialValue = null, screen.conversationId) {
                app.database.conversationDao().observeById(screen.conversationId).collect { value = it }
            }
            fun placeContactCall(type: com.torxone.app.calls.CallType) {
                if (callBusy) return
                val peer = contactState.value ?: return
                if (!peer.isRemoteIdentityKnown) return
                callBusy = true
                coroutineScope.launch {
                    try {
                        val started = app.callManager.startOutgoingCall(screen.conversationId,
                            peer.relationshipId, peer.remoteIdentityId, type)
                        if (started != null && app.callManager.activeCall.value?.callId == started.callId)
                            navigateTo(Screen.ActiveCall)
                        else callError = "Call could not start. Check this contact or finish the active call."
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        callError = error.message ?: "Unable to start call. Try again."
                    } finally { callBusy = false }
                }
            }
            callError?.let { message -> AlertDialog(onDismissRequest = { callError = null },
                title = { Text("Call unavailable") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = { callError = null }) { Text("Close") } }) }
            if (showContactConnection) {
                val flow = remember(screen.conversationId) {
                    com.torxone.app.ui.connection.ConnectionUxReader(app.database, app.transportRouter,
                        app.torController.state, app.nearbyTransport, context,
                        isSendingPaused = { it in app.settingsRepository.pausedConversations.first() }).observe(screen.conversationId)
                }
                val snapshot by flow.collectAsState(initial = com.torxone.app.ui.connection.ConnectionUxSnapshot())
                com.torxone.app.ui.components.ConnectionDashboardDialog(snapshot,
                    onDismiss = { showContactConnection = false },
                    onRetry = { app.agent.triggerImmediateRetry(screen.conversationId) },
                    onRetryTor = { app.torBootstrapManager.retry() },
                    onToggleSendingPaused = { coroutineScope.launch {
                        app.settingsRepository.setConversationSendingPaused(screen.conversationId, !snapshot.sendingPaused)
                        if (snapshot.sendingPaused) app.agent.triggerImmediateRetry(screen.conversationId)
                    } }, onVerifyIdentity = { showContactConnection = false })
            }

            com.torxone.app.ui.screens.ContactInfoScreen(
                contact = contactState.value,
                conversation = conversationState.value,
                chatService = app.chatService,
                nickname = localNames[contactState.value?.contactId],
                onSaveNickname = { nickname ->
                    val peer = requireNotNull(contactState.value) { "Contact unavailable" }
                    app.productivityService.saveNickname(peer.contactId, nickname)
                },
                onOpenMedia = { navigateTo(Screen.SharedMedia(screen.conversationId)) },
                onAudioCall = if (contactState.value?.isRemoteIdentityKnown == true) ({ placeContactCall(com.torxone.app.calls.CallType.VOICE) }) else null,
                onVideoCall = if (contactState.value?.isRemoteIdentityKnown == true) ({ placeContactCall(com.torxone.app.calls.CallType.VIDEO) }) else null,
                onSearch = { navigateTo(Screen.Search(screen.conversationId)) },
                onTheme = { appearanceConversationId = screen.conversationId; showAppearanceEditor = true },
                onConnection = { showContactConnection = true },
                onBackClick = {
                    navigateBack()
                },
                onChatDeleted = {
                    screenStack = emptyList()
                    currentScreen = Screen.ConversationList
                },
                onToggleVerification = { isVerified ->
                    val c = contactState.value ?: return@ContactInfoScreen
                    coroutineScope.launch(Dispatchers.IO) {
                        app.database.contactDao().upsert(
                            c.copy(verificationState = if (isVerified) "VERIFIED" else "UNVERIFIED")
                        )
                    }
                }
            )
        }

        is Screen.NewGroup -> {
            val contactsState = produceState<List<ContactEntity>>(initialValue = emptyList()) {
                value = app.database.contactDao().getAll()
            }

            NewGroupScreen(
                contacts = contactsState.value,
                onCreateGroup = { title, selectedMembers, avatarHash ->
                    val group = app.groupService.createGroup(title = title, initialMembers = selectedMembers, avatarHash = avatarHash)
                    navigateTo(Screen.Chat(group.groupId, group.title))
                },
                onBackClick = {
                    navigateBack()
                }
            )
        }

        is Screen.GroupInfo -> {
            val localIdentityState = produceState<TorXIdentity?>(initialValue = null) {
                value = app.identityRepository.loadIdentity()
            }

            GroupInfoScreen(
                groupId = screen.groupId,
                database = app.database,
                groupService = app.groupService,
                chatService = app.chatService,
                localIdentityId = localIdentityState.value?.identityId,
                onBackClick = {
                    navigateBack()
                },
                onGroupLeft = {
                    screenStack = emptyList()
                    currentScreen = Screen.ConversationList
                }
            )
        }

        is Screen.Settings -> {
            val currentSettingsState by settingsViewModel.uiState.collectAsState()
            val radioState by app.torXRadioManager.state.collectAsState()
            val haLowState by app.haLowGatewayManager.state.collectAsState()

            SettingsScreen(
                avatarUri = currentSettingsState.avatarUri,
                relayOnlyCalls = currentSettingsState.relayOnlyCalls,
                displayName = currentSettingsState.displayName,
                about = currentSettingsState.about,
                lastSeenVisible = currentSettingsState.lastSeenVisible,
                onlineVisible = currentSettingsState.onlineVisible,
                readReceiptsEnabled = currentSettingsState.readReceiptsEnabled,
                notificationsEnabled = currentSettingsState.notificationsEnabled,
                soundEnabled = currentSettingsState.soundEnabled,
                vibrationEnabled = currentSettingsState.vibrationEnabled,
                notificationPreviewMode = currentSettingsState.notificationPreviewMode,
                appLockEnabled = currentSettingsState.appLockEnabled,
                screenSecurityEnabled = currentSettingsState.screenSecurityEnabled,
                autoConnectNearby = currentSettingsState.autoConnectNearby,
                lowBandwidthMode = currentSettingsState.lowBandwidthMode,
                radioState = radioState,
                haLowState = haLowState,
                themeMode = currentSettingsState.themeMode,
                dynamicColorsEnabled = currentSettingsState.dynamicColorsEnabled,
                themeSource = currentSettingsState.themeSource,
                accentId = currentSettingsState.accentId,
                fontSize = currentSettingsState.fontSize,
                autoDownloadMedia = currentSettingsState.autoDownloadMedia,
                onBackClick = { navigateBack() },
                onProfileClick = { navigateTo(Screen.Profile) },
                onSavedMessages = { navigateTo(Screen.SavedMessages) },
                onSearchMessages = { navigateTo(Screen.Search(null)) },
                onChatDefaultsClick = { appearanceConversationId = null; showAppearanceEditor = true },
                onPrivacyChange = { field, value -> settingsViewModel.setPrivacy(field, value) },
                onNotificationChange = { field, value -> settingsViewModel.setNotification(field, value) },
                onSecurityChange = { field, value ->
                    if (field == "appLock" && value == true) {
                        if (fragmentActivity != null && !AppLockManager.canAuthenticate(fragmentActivity)) {
                            android.widget.Toast.makeText(
                                context,
                                "Device security (PIN, pattern, or biometrics) is not set up on this device.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@SettingsScreen
                        }
                    }
                    settingsViewModel.setSecurity(field, value)
                },
                onConnectionChange = { field, value -> settingsViewModel.setConnection(field, value) },
                onPairRadio = {
                    coroutineScope.launch {
                        runCatching { app.torXRadioManager.approvePairing() }
                            .onFailure {
                                android.widget.Toast.makeText(context, it.message ?: "Radio pairing failed", android.widget.Toast.LENGTH_LONG).show()
                            }
                    }
                },
                onPairHaLow = {
                    coroutineScope.launch {
                        runCatching { app.haLowGatewayManager.approvePairing() }
                            .onFailure {
                                android.widget.Toast.makeText(context, it.message ?: "HaLow pairing failed", android.widget.Toast.LENGTH_LONG).show()
                            }
                    }
                },
                onAppearanceChange = { field, value -> settingsViewModel.setAppearance(field, value) },
                onDataChange = { field, value -> settingsViewModel.setData(field, value) }
            )
        }

        is Screen.Profile -> {
            val currentSettingsState by settingsViewModel.uiState.collectAsState()
            val localIdentityState = produceState<TorXIdentity?>(initialValue = null) {
                value = app.identityRepository.loadIdentity()
            }
            val identity = localIdentityState.value

            ProfileScreen(
                displayName = currentSettingsState.displayName,
                about = currentSettingsState.about,
                avatarUri = currentSettingsState.avatarUri,
                identityId = identity?.identityId ?: "",
                signingPublicKey = identity?.signingPublicKey,
                onUpdateProfile = { name, about, avatarUpdate ->
                    settingsViewModel.updateProfile(name, about, avatarUpdate)
                },
                onBackClick = { navigateBack() },
                onShowQr = { showInviteDialog = true }
            )
        }

        is Screen.SharedMedia -> {
            val cachedLinks by remember(screen.conversationId) {
                app.database.featureDao().observeLinks(screen.conversationId)
            }.collectAsState(initial = emptyList())
            val media by app.database.mediaDao().observeForConversation(screen.conversationId).collectAsState(initial = emptyList())
            val messageFlow = remember(screen.conversationId) {
                app.chatService.observeMessages(screen.conversationId)
                    .map { rows -> rows.map { it to (it.body to it.deletedAt) } }
            }
            val messageSnapshots by messageFlow.collectAsState(initial = emptyList())
            val messages = messageSnapshots.map { it.first }
            val hidden by app.database.localMessageStateDao().observeHiddenMessageIds(screen.conversationId).collectAsState(initial = emptyList())
            val visible = messages.filter { it.deletedAt == null && it.logicalMessageId !in hidden }
            val visibleIds = remember(visible) { visible.map { it.logicalMessageId }.toHashSet() }
            val visibleMedia = remember(media, visibleIds) { media.filter { it.messageId in visibleIds } }
            com.torxone.app.ui.screens.SharedMediaScreen(visibleMedia, visible,
                cachedLinks = cachedLinks,
                onBack = { navigateBack() }, onDownload = { mediaId -> coroutineScope.launch {
                    runCatching { app.mediaService.resumeTransfer(mediaId) }.onFailure {
                        android.widget.Toast.makeText(context, it.message ?: "Unable to download", android.widget.Toast.LENGTH_LONG).show()
                    }
                } })
        }
        is Screen.Search -> {
            com.torxone.app.ui.screens.MessageSearchScreen(app.productivityService, screen.conversationId,
                onOpen = { message -> navigateTo(Screen.Chat(message.conversationId, "Chat", message.logicalMessageId)) },
                onBack = { navigateBack() })
        }
        is Screen.Scheduled -> {
            com.torxone.app.ui.screens.ScheduledMessagesScreen(app.scheduledMessageService, screen.conversationId,
                onBack = { navigateBack() }, initialText = screen.text, initialReplyToMessageId = screen.replyToMessageId,
                onCreated = { clearScheduledSource?.invoke(); clearScheduledSource = null })
        }
        is Screen.SavedMessages -> {
            val flow = remember { app.database.featureDao().observeStarredMessages() }
            val saved by flow.collectAsState(initial = emptyList())
            com.torxone.app.ui.screens.SavedMessagesScreen(saved,
                onOpen = { navigateTo(Screen.Chat(it.conversationId, "Chat", it.logicalMessageId)) },
                onUnstar = { id -> coroutineScope.launch { app.productivityService.setStarred(setOf(id), false) } },
                onBack = { navigateBack() })
        }
        is Screen.Forward -> {
            val flow = remember { app.chatService.observeConversations() }
            val destinations by flow.collectAsState(initial = emptyList())
            var sending by remember(screen) { mutableStateOf(false) }
            var error by remember(screen) { mutableStateOf<String?>(null) }
            com.torxone.app.ui.screens.ForwardMessagesScreen(destinations, sending, error,
                onChoose = { destination -> coroutineScope.launch {
                    sending = true; error = null
                    try {
                        val identity = requireNotNull(app.identityRepository.loadIdentity()) { "Unlock your identity first" }
                        app.productivityService.forward(screen.ids, destination, identity.identityId, screen.operationId)
                        navigateBack()
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        error = failure.message ?: "Unable to forward messages"
                    } finally { sending = false }
                } }, onBack = { navigateBack() })
        }
        is Screen.ActiveCall -> {
            CallScreen(
                viewModel = callViewModel,
                onBackClick = {
                    navigateBack()
                }
            )
        }
    }

    }
    if (showAppearanceEditor) {
        val legacyFlow = remember(appearanceConversationId) {
            appearanceConversationId?.let { app.database.featureDao().observeAppearance(it) }
                ?: kotlinx.coroutines.flow.flowOf<com.torxone.app.data.entity.ConversationAppearanceEntity?>(null)
        }
        com.torxone.app.ui.components.ChatAppearanceSheet(appearanceRepository,
            conversationId = appearanceConversationId, legacyFlow = legacyFlow,
            onDismiss = { showAppearanceEditor = false })
    }
    if (showInviteDialog) {
        ContactInviteDialog(
            viewModel = contactsViewModel,
            onContactAdded = { conversationId, contactName ->
                showInviteDialog = false
                navigateTo(
                    Screen.Chat(
                        conversationId = conversationId,
                        contactName = contactName.ifBlank { "Contact" }
                    )
                )
            },
            onDismiss = {
                showInviteDialog = false
            }
        )
    }
}

sealed class Screen {
    data object Landing : Screen()
    data object ConversationList : Screen()
    data object ArchivedList : Screen()
    data class Chat(val conversationId: String, val contactName: String, val messageId: String? = null) : Screen()
    data class Search(val conversationId: String?) : Screen()
    data class Scheduled(val conversationId: String, val text: String = "", val replyToMessageId: String? = null) : Screen()
    data class Forward(val ids: List<String>, val operationId: String = java.util.UUID.randomUUID().toString()) : Screen()
    data object SavedMessages : Screen()
    data class ContactInfo(val conversationId: String, val contactName: String) : Screen()
    data class SharedMedia(val conversationId: String) : Screen()
    data object NewGroup : Screen()
    data class GroupInfo(val groupId: String) : Screen()
    data object Settings : Screen()
    data object Profile : Screen()
    data object ActiveCall : Screen()
}
