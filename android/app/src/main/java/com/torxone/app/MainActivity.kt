package com.torxone.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.torxone.app.contacts.ContactsViewModel
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.media.RealVoiceNoteRecorder
import com.torxone.app.profile.SettingsViewModel
import com.torxone.app.ui.components.ContactInviteDialog
import com.torxone.app.ui.permissions.PermissionHelper
import com.torxone.app.ui.screens.*
import com.torxone.app.ui.security.AppLockManager
import com.torxone.app.ui.security.AppLockOverlay
import com.torxone.app.ui.theme.TorXOneTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TorXOneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TorXOneApp()
                }
            }
        }
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
            if (app.identityRepository.loadIdentity() == null) {
                val name = app.settingsRepository.getDisplayName().ifEmpty { "Me" }
                app.identityRepository.createIdentity(name)
            }
        }
    }

    val contactsViewModel = remember {
        ContactsViewModel(
            database = app.database,
            identityRepository = app.identityRepository,
            sessionCrypto = app.sessionCrypto,
            connectionManager = app.connectionManager,
            agent = app.agent
        )
    }

    val conversationListViewModel = remember {
        com.torxone.app.conversations.ConversationListViewModel(
            chatService = app.chatService,
            messageDao = app.database.messageDao()
        )
    }

    val settingsViewModel = remember {
        SettingsViewModel(settingsRepo = app.settingsRepository)
    }

    val callViewModel = remember {
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

    // App Lock: trigger biometric prompt on resume / launch when enabled
    LaunchedEffect(settingsState.appLockEnabled) {
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

    // Navigation BackStack handling
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
        }
    }

    BackHandler(enabled = screenStack.isNotEmpty()) {
        navigateBack()
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

                        // Create cryptographic identity
                        if (app.identityRepository.loadIdentity() == null) {
                            app.identityRepository.createIdentity(displayName)
                        }

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
                    currentScreen = Screen.Chat(
                        conversationId = id,
                        contactName = clicked?.title ?: "Chat"
                    )
                },
                onArchivedClick = {
                    currentScreen = Screen.ArchivedList
                },
                onScanQrClick = {
                    showInviteDialog = true
                },
                onNewGroupClick = {
                    currentScreen = Screen.NewGroup
                },
                onSettingsClick = {
                    currentScreen = Screen.Settings
                }
            )
        }

        is Screen.ArchivedList -> {
            val uiState by conversationListViewModel.uiState.collectAsState()

            com.torxone.app.ui.screens.ArchivedConversationsScreen(
                viewModel = conversationListViewModel,
                onConversationClick = { id ->
                    val clicked = uiState.archivedConversations.find { it.conversationId == id }
                    currentScreen = Screen.Chat(
                        conversationId = id,
                        contactName = clicked?.title ?: "Chat"
                    )
                },
                onBackClick = {
                    currentScreen = Screen.ConversationList
                }
            )
        }

        is Screen.Chat -> {
            // Track active conversation for notification suppression and unread counts (Section 42)
            DisposableEffect(screen.conversationId) {
                app.activeConversationTracker.setActiveConversation(screen.conversationId)
                app.notificationManager.cancelForConversation(screen.conversationId)
                coroutineScope.launch {
                    val conv = app.database.conversationDao().getById(screen.conversationId)
                    if (conv?.type == com.torxone.app.data.entity.ConversationType.GROUP) {
                        app.groupService.markGroupRead(screen.conversationId)
                    } else {
                        app.chatService.markConversationRead(screen.conversationId)
                    }
                }
                onDispose {
                    app.activeConversationTracker.clearActiveConversation()
                }
            }

            val conversationState = produceState<com.torxone.app.data.entity.ConversationEntity?>(initialValue = null, screen.conversationId) {
                value = app.database.conversationDao().getById(screen.conversationId)
            }
            val localIdentityState = produceState<com.torxone.app.identity.TorXIdentity?>(initialValue = null) {
                value = app.identityRepository.loadIdentity()
            }
            val conversation = conversationState.value
            val localIdentity = localIdentityState.value

            if (conversation != null && localIdentity != null) {
                if (conversation.type == com.torxone.app.data.entity.ConversationType.GROUP) {
                    val groupViewModel = remember(screen.conversationId) {
                        com.torxone.app.groups.GroupChatViewModel(
                            groupId = screen.conversationId,
                            conversationId = screen.conversationId,
                            localIdentityId = localIdentity.identityId,
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
                            currentScreen = Screen.ConversationList
                        },
                        onHeaderClick = {
                            currentScreen = Screen.GroupInfo(screen.conversationId)
                        }
                    )
                } else {
                    // DIRECT conversation: Load contact strictly by conversationId
                    val contactState = produceState<com.torxone.app.data.entity.ContactEntity?>(initialValue = null, screen.conversationId) {
                        value = app.database.contactDao().getByConversationId(screen.conversationId)
                    }
                    val contact = contactState.value

                    if (contact != null) {
                        val voiceRecorder = remember(context) { RealVoiceNoteRecorder(context) }
                        val viewModel = remember(screen.conversationId, contact.relationshipId) {
                            com.torxone.app.chat.ChatViewModel(
                                conversationId = screen.conversationId,
                                relationshipId = contact.relationshipId,
                                localIdentityId = localIdentity.identityId,
                                recipientId = contact.contactId,
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
                                currentScreen = Screen.ConversationList
                            },
                            onHeaderClick = {
                                currentScreen = Screen.ContactInfo(screen.conversationId, screen.contactName)
                            },
                            onStartVoiceCall = {
                                coroutineScope.launch {
                                    app.callManager.startOutgoingCall(
                                        conversationId = screen.conversationId,
                                        relationshipId = contact.relationshipId,
                                        peerIdentityId = contact.contactId,
                                        type = com.torxone.app.calls.CallType.VOICE
                                    )
                                    currentScreen = Screen.ActiveCall
                                }
                            },
                            onStartVideoCall = {
                                coroutineScope.launch {
                                    app.callManager.startOutgoingCall(
                                        conversationId = screen.conversationId,
                                        relationshipId = contact.relationshipId,
                                        peerIdentityId = contact.contactId,
                                        type = com.torxone.app.calls.CallType.VIDEO
                                    )
                                    currentScreen = Screen.ActiveCall
                                }
                            }
                        )
                    } else {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = { Text(screen.contactName.ifBlank { "Conversation" }) },
                                    navigationIcon = {
                                        IconButton(onClick = { currentScreen = Screen.ConversationList }) {
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
                                    Button(onClick = { currentScreen = Screen.ConversationList }) {
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
                                IconButton(onClick = { currentScreen = Screen.ConversationList }) {
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
                            Text("This conversation cannot be found or is loading.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { currentScreen = Screen.ConversationList }) {
                                Text("Back to Chats")
                            }
                        }
                    }
                }
            }
        }

        is Screen.ContactInfo -> {
            val contactState = produceState<com.torxone.app.data.entity.ContactEntity?>(initialValue = null, screen.conversationId) {
                value = app.database.contactDao().getByConversationId(screen.conversationId)
            }
            val conversationState = produceState<com.torxone.app.data.entity.ConversationEntity?>(initialValue = null, screen.conversationId) {
                value = app.database.conversationDao().getById(screen.conversationId)
            }

            com.torxone.app.ui.screens.ContactInfoScreen(
                contact = contactState.value,
                conversation = conversationState.value,
                chatService = app.chatService,
                onBackClick = {
                    currentScreen = Screen.Chat(screen.conversationId, screen.contactName)
                },
                onChatDeleted = {
                    currentScreen = Screen.ConversationList
                }
            )
        }

        is Screen.NewGroup -> {
            val contactsState = produceState<List<com.torxone.app.data.entity.ContactEntity>>(initialValue = emptyList()) {
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
                            currentScreen = Screen.Chat(group.groupId, group.title)
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to create group: ${e.message}", e)
                        }
                    }
                },
                onBackClick = {
                    currentScreen = Screen.ConversationList
                }
            )
        }

        is Screen.GroupInfo -> {
            val localIdentityState = produceState<com.torxone.app.identity.TorXIdentity?>(initialValue = null) {
                value = app.identityRepository.loadIdentity()
            }

            GroupInfoScreen(
                groupId = screen.groupId,
                database = app.database,
                groupService = app.groupService,
                chatService = app.chatService,
                localIdentityId = localIdentityState.value?.identityId,
                onBackClick = {
                    currentScreen = Screen.Chat(screen.groupId, "Group")
                },
                onGroupLeft = {
                    currentScreen = Screen.ConversationList
                }
            )
        }

        is Screen.Settings -> {
            val settingsState by settingsViewModel.uiState.collectAsState()

            SettingsScreen(
                displayName = settingsState.displayName,
                about = settingsState.about,
                lastSeenVisible = settingsState.lastSeenVisible,
                onlineVisible = settingsState.onlineVisible,
                readReceiptsEnabled = settingsState.readReceiptsEnabled,
                notificationsEnabled = settingsState.notificationsEnabled,
                soundEnabled = settingsState.soundEnabled,
                vibrationEnabled = settingsState.vibrationEnabled,
                notificationPreviewMode = settingsState.notificationPreviewMode,
                appLockEnabled = settingsState.appLockEnabled,
                screenSecurityEnabled = settingsState.screenSecurityEnabled,
                autoConnectNearby = settingsState.autoConnectNearby,
                lowBandwidthMode = settingsState.lowBandwidthMode,
                themeMode = settingsState.themeMode,
                dynamicColorsEnabled = settingsState.dynamicColorsEnabled,
                autoDownloadMedia = settingsState.autoDownloadMedia,
                onBackClick = { currentScreen = Screen.ConversationList },
                onProfileClick = { currentScreen = Screen.Profile },
                onPrivacyChange = { field, value -> settingsViewModel.setPrivacy(field, value) },
                onNotificationChange = { field, value -> settingsViewModel.setNotification(field, value) },
                onSecurityChange = { field, value -> settingsViewModel.setSecurity(field, value) },
                onConnectionChange = { field, value -> settingsViewModel.setConnection(field, value) },
                onAppearanceChange = { field, value -> settingsViewModel.setAppearance(field, value) },
                onDataChange = { field, value -> settingsViewModel.setData(field, value) }
            )
        }

        is Screen.Profile -> {
            val settingsState by settingsViewModel.uiState.collectAsState()
            val localIdentityState = produceState<com.torxone.app.identity.TorXIdentity?>(initialValue = null) {
                value = app.identityRepository.loadIdentity()
            }
            val identity = localIdentityState.value

            ProfileScreen(
                displayName = settingsState.displayName,
                about = settingsState.about,
                avatarUri = null,
                identityId = identity?.identityId ?: "",
                signingPublicKey = identity?.signingPublicKey,
                onUpdateProfile = { name, about ->
                    settingsViewModel.updateProfile(name, about)
                },
                onBackClick = { currentScreen = Screen.Settings },
                onShowQr = { showInviteDialog = true }
            )
        }

        is Screen.ActiveCall -> {
            CallScreen(
                viewModel = callViewModel,
                onBackClick = {
                    currentScreen = Screen.ConversationList
                }
            )
        }
    }

    if (showInviteDialog) {
        ContactInviteDialog(
            viewModel = contactsViewModel,
            onContactAdded = { conversationId, contactName ->
                showInviteDialog = false
                currentScreen = Screen.Chat(
                    conversationId = conversationId,
                    contactName = contactName.ifBlank { "Contact" }
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
