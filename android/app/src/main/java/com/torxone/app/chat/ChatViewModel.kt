package com.torxone.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.connection.ConnectionState
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.entity.ReactionEntity
import com.torxone.app.protocol.ReactionOperation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Rich Chat UI State for presentation.
 */
data class ChatUiState(
    val title: String = "",
    val messages: List<MessageUiModel> = emptyList(),
    val composerText: String = "",
    val presence: PresenceStatus = PresenceStatus.UNKNOWN,
    val lastSeenAt: Long? = null,
    val isTyping: Boolean = false,
    val replyingTo: MessageUiModel? = null,
    val editingMessage: MessageUiModel? = null,
    val connectionState: ConnectionState = ConnectionState.ACTIVE,
    val isSending: Boolean = false,
    val error: String? = null
)

/**
 * ChatViewModel
 *
 * Responsibilities:
 * - Maps Room MessageEntity + ReactionEntity -> MessageUiModel with quotes & reactions
 * - Manages typing debouncing (TYPING_START on first keystroke, TYPING_STOP after 2.5s pause)
 * - Observes pairwise presence and updates header status
 * - Manages reply state (replyingTo, cancelReply)
 * - Manages edit state (startEditing, cancelEditing, editMessage)
 * - Manages delete state (deleteMessage tombstoning)
 * - Manages reaction toggling (toggleReaction)
 * - Dispatches batch read receipts when conversation is visible
 */
class ChatViewModel(
    private val conversationId: String,
    private val relationshipId: String,
    private val localIdentityId: String,
    private val recipientId: String,
    private val contactName: String,
    private val chatService: ChatService,
    private val presenceService: PresenceService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(title = contactName))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentReactionEntities: List<ReactionEntity> = emptyList()

    private var typingDebounceJob: Job? = null
    private var isTypingLocally = false

    init {
        // 1. Observe messages and reactions and map to UI models with quote & reaction resolution
        viewModelScope.launch {
            combine(
                chatService.observeMessages(conversationId),
                chatService.observeReactions(conversationId)
            ) { msgEntities, reactionEntities ->
                currentReactionEntities = reactionEntities
                mapToUiModels(msgEntities, reactionEntities)
            }.collect { uiModels ->
                _uiState.update { it.copy(messages = uiModels) }

                // Automatically mark incoming messages read if conversation is open
                markConversationRead()
            }
        }

        // 2. Observe pairwise presence (Online / Offline / Typing)
        if (presenceService != null) {
            viewModelScope.launch {
                presenceService.observePresence(relationshipId).collect { presence ->
                    _uiState.update {
                        it.copy(
                            presence = presence.status,
                            lastSeenAt = presence.lastSeenAt,
                            isTyping = presence.isTyping
                        )
                    }
                }
            }
        }
    }

    private fun mapToUiModels(
        entities: List<MessageEntity>,
        reactions: List<ReactionEntity>
    ): List<MessageUiModel> {
        val entityMap = entities.associateBy { it.logicalMessageId }
        val reactionsByMessage = reactions.groupBy { it.messageId }

        return entities.map { entity ->
            val quoted = entity.replyToMessageId?.let { replyId ->
                val original = entityMap[replyId]
                if (original != null) {
                    if (original.deletedAt != null) {
                        QuotedMessageUiModel(
                            messageId = replyId,
                            senderName = if (original.direction == MessageDirection.OUTGOING) "You" else contactName,
                            previewText = "This message was deleted"
                        )
                    } else {
                        QuotedMessageUiModel(
                            messageId = replyId,
                            senderName = if (original.direction == MessageDirection.OUTGOING) "You" else contactName,
                            previewText = original.body ?: ""
                        )
                    }
                } else {
                    QuotedMessageUiModel(
                        messageId = replyId,
                        senderName = "Unavailable",
                        previewText = "Original message unavailable",
                        isUnavailable = true
                    )
                }
            }

            val deliveryStatus = try {
                DeliveryStatus.valueOf(entity.status)
            } catch (_: Exception) {
                DeliveryStatus.QUEUED
            }

            val messageReactions = reactionsByMessage[entity.logicalMessageId].orEmpty()
            val reactionSummaries = messageReactions
                .groupBy { it.emoji }
                .map { (emoji, list) ->
                    ReactionSummaryUiModel(
                        emoji = emoji,
                        count = list.size,
                        userReacted = list.any { it.senderId == localIdentityId }
                    )
                }
                .sortedByDescending { it.count }

            MessageUiModel(
                logicalMessageId = entity.logicalMessageId,
                conversationId = entity.conversationId,
                senderId = entity.senderId,
                body = entity.body,
                direction = entity.direction,
                status = deliveryStatus,
                createdAt = entity.createdAt,
                deliveredAt = entity.deliveredAt,
                readAt = entity.readAt,
                replyToMessageId = entity.replyToMessageId,
                quotedMessage = quoted,
                isEdited = entity.editedAt != null && entity.editVersion > 0,
                editedAt = entity.editedAt,
                isDeleted = entity.deletedAt != null,
                reactions = reactionSummaries
            )
        }
    }

    fun onComposerTextChanged(text: String) {
        _uiState.update { it.copy(composerText = text) }
        handleTypingDebounce(text)
    }

    private fun handleTypingDebounce(text: String) {
        if (text.isNotBlank()) {
            if (!isTypingLocally) {
                isTypingLocally = true
                viewModelScope.launch {
                    presenceService?.sendTypingStart(relationshipId, conversationId)
                }
            }
            // Reset 2.5 second inactivity timer
            typingDebounceJob?.cancel()
            typingDebounceJob = viewModelScope.launch {
                delay(2500L)
                if (isTypingLocally) {
                    isTypingLocally = false
                    presenceService?.sendTypingStop(relationshipId, conversationId)
                }
            }
        } else {
            // Text cleared
            typingDebounceJob?.cancel()
            if (isTypingLocally) {
                isTypingLocally = false
                viewModelScope.launch {
                    presenceService?.sendTypingStop(relationshipId, conversationId)
                }
            }
        }
    }

    fun onReply(message: MessageUiModel) {
        _uiState.update { it.copy(replyingTo = message, editingMessage = null) }
    }

    fun cancelReply() {
        _uiState.update { it.copy(replyingTo = null) }
    }

    fun startEditing(message: MessageUiModel) {
        if (message.isDeleted || message.direction != MessageDirection.OUTGOING) return
        _uiState.update {
            it.copy(
                editingMessage = message,
                replyingTo = null,
                composerText = message.body ?: ""
            )
        }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingMessage = null, composerText = "") }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val alreadyReacted = currentReactionEntities.any {
            it.messageId == messageId && it.senderId == localIdentityId && it.emoji == emoji
        }
        val operation = if (alreadyReacted) ReactionOperation.REMOVE else ReactionOperation.ADD

        viewModelScope.launch {
            chatService.sendReaction(
                conversationId = conversationId,
                relationshipId = relationshipId,
                localIdentityId = localIdentityId,
                recipientId = recipientId,
                targetMessageId = messageId,
                emoji = emoji,
                operation = operation
            )
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                chatService.deleteMessage(
                    conversationId = conversationId,
                    relationshipId = relationshipId,
                    localIdentityId = localIdentityId,
                    recipientId = recipientId,
                    targetMessageId = messageId
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to delete message") }
            }
        }
    }

    fun sendText() {
        val text = _uiState.value.composerText.trim()
        if (text.isEmpty()) return

        val editing = _uiState.value.editingMessage
        if (editing != null) {
            // Editing mode
            _uiState.update { it.copy(editingMessage = null, composerText = "", isSending = true, error = null) }
            viewModelScope.launch {
                try {
                    chatService.editMessage(
                        conversationId = conversationId,
                        relationshipId = relationshipId,
                        localIdentityId = localIdentityId,
                        recipientId = recipientId,
                        targetMessageId = editing.logicalMessageId,
                        newText = text
                    )
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = e.message ?: "Edit failed") }
                } finally {
                    _uiState.update { it.copy(isSending = false) }
                }
            }
            return
        }

        val replyToId = _uiState.value.replyingTo?.logicalMessageId

        // Stop typing immediately when message is sent
        typingDebounceJob?.cancel()
        if (isTypingLocally) {
            isTypingLocally = false
            viewModelScope.launch {
                presenceService?.sendTypingStop(relationshipId, conversationId)
            }
        }

        _uiState.update { it.copy(composerText = "", replyingTo = null, isSending = true, error = null) }
        viewModelScope.launch {
            try {
                chatService.sendTextMessage(
                    conversationId = conversationId,
                    relationshipId = relationshipId,
                    localIdentityId = localIdentityId,
                    recipientId = recipientId,
                    text = text,
                    replyToMessageId = replyToId
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Send failed") }
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun markConversationRead() {
        viewModelScope.launch {
            try {
                chatService.markConversationRead(
                    conversationId = conversationId,
                    relationshipId = relationshipId,
                    localIdentityId = localIdentityId,
                    recipientId = recipientId
                )
            } catch (_: Exception) {}
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        typingDebounceJob?.cancel()
        if (isTypingLocally) {
            isTypingLocally = false
            viewModelScope.launch {
                presenceService?.sendTypingStop(relationshipId, conversationId)
            }
        }
    }
}
