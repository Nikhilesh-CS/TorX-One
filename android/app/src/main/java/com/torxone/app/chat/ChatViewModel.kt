package com.torxone.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torxone.app.connection.ConnectionState
import com.torxone.app.data.entity.MessageEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatUiState(
    val title: String = "",
    val messages: List<MessageEntity> = emptyList(),
    val composerText: String = "",
    val connectionState: ConnectionState = ConnectionState.ACTIVE,
    val isSending: Boolean = false,
    val error: String? = null
)

/**
 * ChatViewModel (Section 43)
 * Exposes a single ChatUiState.
 * Completely decoupled from transports and protocol internals.
 */
class ChatViewModel(
    private val conversationId: String,
    private val relationshipId: String,
    private val localIdentityId: String,
    private val recipientId: String,
    private val contactName: String,
    private val chatService: ChatService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(title = contactName))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            chatService.observeMessages(conversationId).collect { msgList ->
                _uiState.update { it.copy(messages = msgList) }
            }
        }
    }

    fun onComposerTextChanged(text: String) {
        _uiState.update { it.copy(composerText = text) }
    }

    fun sendText() {
        val text = _uiState.value.composerText.trim()
        if (text.isEmpty()) return

        _uiState.update { it.copy(composerText = "", isSending = true, error = null) }
        viewModelScope.launch {
            try {
                chatService.sendTextMessage(
                    conversationId = conversationId,
                    relationshipId = relationshipId,
                    localIdentityId = localIdentityId,
                    recipientId = recipientId,
                    text = text
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Send failed") }
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
