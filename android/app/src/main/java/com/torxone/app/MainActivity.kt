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
import com.torxone.app.ui.components.ContactInviteDialog
import com.torxone.app.ui.permissions.PermissionHelper
import com.torxone.app.ui.screens.*
import com.torxone.app.ui.security.AppLockManager
import com.torxone.app.ui.security.AppLockOverlay
import com.torxone.app.ui.theme.TorXOneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    val intentFlow = kotlinx.coroutines.flow.MutableStateFlow<android.content.Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentFlow.value = intent
        enableEdgeToEdge()

        setContent {
            val app = applicationContext as TorXOneApplication
            val themeMode by app.settingsRepository.themeMode.collectAsState(initial = "SYSTEM")
            val dynamicColorsEnabled by app.settingsRepository.dynamicColorsEnabled.collectAsState(initial = true)
            val isDark = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemInDarkTheme()
            }

            TorXOneTheme(
                darkTheme = isDark,
                dynamicColor = dynamicColorsEnabled
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
    if (resolved && appInitState is TorXOneApplication.AppInitState.Initializing) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

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
            connectionManager = app.connectionManager,
            agent = app.agent,
            nearbyTransport = app.nearbyTransport
        )
    }

    val conversationListViewModel: com.torxone.app.conversations.ConversationListViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
        com.torxone.app.conversations.ConversationListViewModel(
            chatService = app.chatService,
            messageDao = app.database.messageDao()
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
            contactDao = app.database.contactDao()
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
    var backgroundTimestamp by remember { mutableLongStateOf(0L) }

    DisposableEffect(lifecycleOwner, settingsState.appLockEnabled, settingsState.appLockTimeoutMs) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    backgroundTimestamp = System.currentTimeMillis()
                }
                Lifecycle.Event.ON_START -> {
                    if (settingsState.appLockEnabled && backgroundTimestamp > 0L) {
                        val elapsed = System.currentTimeMillis() - backgroundTimestamp
                        if (elapsed >= settingsState.appLockTimeoutMs) {
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
                },
                onError = { err ->
                    lockErrorMessage = err
                }
            )
        }
    }

    // Core Transport & Notification runtime permissions
    val startupPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            app.nearbyTransport.start()
        }
    }

    LaunchedEffect(resolved) {
        if (resolved) {
            val perms = PermissionHelper.getStartupPermissions()
            if (!PermissionHelper.arePermissionsGranted(context, perms)) {
                startupPermissionsLauncher.launch(perms)
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
            }
            pendingAnswerCallId = null
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

    when (val screen = currentScreen) {
        is Screen.Landing -> {
            LandingScreen(
                onComplete = { displayName ->
                    coroutineScope.launch {
                        // Save profile to DataStore
                        app.settingsRepository.updateProfile(displayName = displayName)
                        app.settingsRepository.completeOnboarding()

                        // Create and publish authoritative cryptographic identity immediately
                        app.identityRepository.createAndPublishIdentity(displayName)

                        screenStack = emptyList()
                        currentScreen = Screen.ConversationList
                    }
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
            // Track active conversation for notification suppression and unread counts
            DisposableEffect(screen.conversationId) {
                app.activeConversationTracker.setActiveConversation(screen.conversationId)
                app.notificationManager.cancelForConversation(screen.conversationId)
                coroutineScope.launch {
                    val conv = app.database.conversationDao().getById(screen.conversationId)
                    if (conv?.type == ConversationType.GROUP) {
                        app.groupService.markGroupRead(screen.conversationId)
                    } else {
                        app.chatService.markConversationRead(screen.conversationId)
                    }
                }
                onDispose {
                    app.activeConversationTracker.clearActiveConversation()
                }
            }

            var isLoadingConversation by remember(screen.conversationId) { mutableStateOf(true) }
            var conversation by remember(screen.conversationId) { mutableStateOf<ConversationEntity?>(null) }
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

            if (isLoadingConversation || isLoadingIdentity) {
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
                if (conversation!!.type == ConversationType.GROUP) {
                    val groupViewModel: com.torxone.app.groups.GroupChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
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
                            mediaService = app.mediaService
                        )
                    }

                    ChatScreen(
                        viewModel = groupViewModel,
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
                        contact = app.database.contactDao().getByConversationId(screen.conversationId)
                        isLoadingContact = false
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
                                    voiceNoteRecorder = voiceRecorder
                                )
                            }

                        ChatScreen(
                            viewModel = viewModel,
                            onBackClick = {
                                navigateBack()
                            },
                            onHeaderClick = {
                                navigateTo(Screen.ContactInfo(screen.conversationId, screen.contactName))
                            },
                            onStartVoiceCall = {
                                coroutineScope.launch {
                                    app.callManager.startOutgoingCall(
                                        conversationId = screen.conversationId,
                                        relationshipId = contact!!.relationshipId,
                                        peerIdentityId = peerNetworkIdentity,
                                        type = com.torxone.app.calls.CallType.VOICE
                                    )
                                    navigateTo(Screen.ActiveCall)
                                }
                            },
                            onStartVideoCall = {
                                coroutineScope.launch {
                                    app.callManager.startOutgoingCall(
                                        conversationId = screen.conversationId,
                                        relationshipId = contact!!.relationshipId,
                                        peerIdentityId = peerNetworkIdentity,
                                        type = com.torxone.app.calls.CallType.VIDEO
                                    )
                                    navigateTo(Screen.ActiveCall)
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
            val contactState = produceState<ContactEntity?>(initialValue = null, screen.conversationId) {
                app.database.contactDao().observeAll().collect { contacts ->
                    value = contacts.firstOrNull { it.conversationId == screen.conversationId }
                }
            }
            val conversationState = produceState<ConversationEntity?>(initialValue = null, screen.conversationId) {
                value = app.database.conversationDao().getById(screen.conversationId)
            }

            com.torxone.app.ui.screens.ContactInfoScreen(
                contact = contactState.value,
                conversation = conversationState.value,
                chatService = app.chatService,
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
                onCreateGroup = { title, selectedMembers ->
                    coroutineScope.launch {
                        try {
                            val group = app.groupService.createGroup(
                                title = title,
                                initialMembers = selectedMembers
                            )
                            navigateTo(Screen.Chat(group.groupId, group.title))
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to create group: ${e.message}", e)
                            android.widget.Toast.makeText(
                                context,
                                "Failed to create group: ${e.message ?: "Unknown error"}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
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

            SettingsScreen(
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
                themeMode = currentSettingsState.themeMode,
                dynamicColorsEnabled = currentSettingsState.dynamicColorsEnabled,
                autoDownloadMedia = currentSettingsState.autoDownloadMedia,
                onBackClick = { navigateBack() },
                onProfileClick = { navigateTo(Screen.Profile) },
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
                onUpdateProfile = { name, about ->
                    settingsViewModel.updateProfile(name, about)
                },
                onBackClick = { navigateBack() },
                onShowQr = { showInviteDialog = true }
            )
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
    data class Chat(val conversationId: String, val contactName: String) : Screen()
    data class ContactInfo(val conversationId: String, val contactName: String) : Screen()
    data object NewGroup : Screen()
    data class GroupInfo(val groupId: String) : Screen()
    data object Settings : Screen()
    data object Profile : Screen()
    data object ActiveCall : Screen()
}
