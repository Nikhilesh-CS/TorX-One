package com.torxone.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.torxone.app.contacts.ContactsViewModel
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.profile.SettingsViewModel
import com.torxone.app.ui.components.ContactInviteDialog
import com.torxone.app.ui.screens.*
import com.torxone.app.ui.theme.TorXOneTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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

@Composable
fun TorXOneApp() {
    val context = LocalContext.current
    val app = context.applicationContext as TorXOneApplication
    val coroutineScope = rememberCoroutineScope()

    // Check first-run state
    val onboardingComplete by app.settingsRepository.isOnboardingComplete.collectAsState(initial = null)

    // Don't render anything until we know the onboarding state (avoid flash)
    val resolved = onboardingComplete ?: return

    var currentScreen by remember(resolved) {
        val launchConvId = (context as? ComponentActivity)?.intent?.getStringExtra("conversationId")
        if (!launchConvId.isNullOrBlank()) {
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
                    app.chatService.markConversationRead(screen.conversationId)
                }
                onDispose {
                    app.activeConversationTracker.clearActiveConversation()
                }
            }

            val contactState = produceState<com.torxone.app.data.entity.ContactEntity?>(initialValue = null, screen.conversationId) {
                value = app.database.contactDao().getByConversationId(screen.conversationId)
                    ?: app.database.contactDao().getAll().firstOrNull()
            }
            val localIdentityState = produceState<com.torxone.app.identity.TorXIdentity?>(initialValue = null) {
                value = app.identityRepository.loadIdentity()
            }

            val contact = contactState.value
            val localIdentity = localIdentityState.value

            if (contact != null && localIdentity != null) {
                val viewModel = remember(screen.conversationId, contact.relationshipId) {
                    com.torxone.app.chat.ChatViewModel(
                        conversationId = screen.conversationId,
                        relationshipId = contact.relationshipId,
                        localIdentityId = localIdentity.identityId,
                        recipientId = contact.contactId,
                        contactName = screen.contactName,
                        chatService = app.chatService,
                        presenceService = app.presenceService
                    )
                }

                ChatScreen(
                    viewModel = viewModel,
                    onBackClick = {
                        currentScreen = Screen.ConversationList
                    },
                    onHeaderClick = {
                        currentScreen = Screen.ContactInfo(screen.conversationId, screen.contactName)
                    }
                )
            }
        }

        is Screen.ContactInfo -> {
            val contactState = produceState<com.torxone.app.data.entity.ContactEntity?>(initialValue = null, screen.conversationId) {
                value = app.database.contactDao().getByConversationId(screen.conversationId)
                    ?: app.database.contactDao().getAll().firstOrNull()
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
    }

    if (showInviteDialog) {
        ContactInviteDialog(
            viewModel = contactsViewModel,
            onContactAdded = { conversationId ->
                showInviteDialog = false
                currentScreen = Screen.Chat(
                    conversationId = conversationId,
                    contactName = "Peer"
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
    data object Settings : Screen()
    data object Profile : Screen()
}
