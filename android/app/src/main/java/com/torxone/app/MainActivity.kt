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
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.ui.screens.ChatScreen
import com.torxone.app.ui.screens.ConversationListScreen
import com.torxone.app.ui.theme.TorXOneTheme

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
    // Simple navigation state for Milestone 1
    var currentScreen by remember { mutableStateOf<Screen>(Screen.ConversationList) }

    when (val screen = currentScreen) {
        is Screen.ConversationList -> {
            ConversationListScreen(
                conversations = emptyList(), // Will be connected to Room
                onConversationClick = { id ->
                    currentScreen = Screen.Chat(conversationId = id, contactName = "Contact")
                },
                onScanQrClick = {
                    // TODO: Navigate to QR scanner
                }
            )
        }

        is Screen.Chat -> {
            ChatScreen(
                contactName = screen.contactName,
                messages = emptyList(), // Will be connected to Room
                onSendMessage = { text ->
                    // TODO: Connect to ChatService
                },
                onBackClick = {
                    currentScreen = Screen.ConversationList
                }
            )
        }
    }
}

sealed class Screen {
    data object ConversationList : Screen()
    data class Chat(val conversationId: String, val contactName: String) : Screen()
}
