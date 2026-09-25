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
import com.torxone.app.ui.components.ContactInviteDialog
import com.torxone.app.ui.screens.ChatScreen
import com.torxone.app.ui.screens.ConversationListScreen
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

    var currentScreen by remember { mutableStateOf<Screen>(Screen.ConversationList) }
    var showInviteDialog by remember { mutableStateOf(false) }

    // Ensure identity exists on startup
    LaunchedEffect(Unit) {
        if (app.identityRepository.loadIdentity() == null) {
            app.identityRepository.createIdentity("Me")
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

    when (val screen = currentScreen) {
        is Screen.ConversationList -> {
            val conversations by app.database.conversationDao()
                .observeAll()
                .collectAsState(initial = emptyList())

            ConversationListScreen(
                conversations = conversations,
                onConversationClick = { id ->
                    val clicked = conversations.find { it.conversationId == id }
                    currentScreen = Screen.Chat(
                        conversationId = id,
                        contactName = clicked?.title ?: "Chat"
                    )
                },
                onScanQrClick = {
                    showInviteDialog = true
                }
            )
        }

        is Screen.Chat -> {
            // Track active conversation for notification suppression and unread counts (Section 42)
            DisposableEffect(screen.conversationId) {
                app.activeConversationTracker.setActiveConversation(screen.conversationId)
                coroutineScope.launch {
                    app.database.conversationDao().updateUnreadCount(screen.conversationId, 0)
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
                    }
                )
            }
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
    data object ConversationList : Screen()
    data class Chat(val conversationId: String, val contactName: String) : Screen()
}
