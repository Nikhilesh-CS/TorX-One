package com.torxone.app.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.chat.ChatService
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.notifications.NotificationPolicy
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ConversationListUiState(
    val conversations: List<ConversationUiModel> = emptyList(),
    val archivedConversations: List<ConversationUiModel> = emptyList(),
    val archivedCount: Int = 0,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val selectedConversationForMenu: ConversationUiModel? = null,
    val showMuteDialogFor: ConversationUiModel? = null,
    val showDeleteDialogFor: ConversationUiModel? = null
)

@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationListViewModel(
    private val chatService: ChatService,
    private val messageDao: MessageDao? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    private val searchQueryFlow = MutableStateFlow("")

    init {
        // 1. Observe active conversations or search results
        viewModelScope.launch {
            searchQueryFlow
                .debounce { if (it.isBlank()) 0L else 300L }
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        chatService.observeConversations()
                    } else {
                        chatService.searchConversations(query)
                    }
                }
                .map { list -> mapToUiModels(list) }
                .collect { uiModels ->
                    _uiState.update { it.copy(conversations = uiModels) }
                }
        }

        // 2. Observe archived conversations
        viewModelScope.launch {
            chatService.observeArchivedConversations()
                .map { list -> mapToUiModels(list) }
                .collect { uiModels ->
                    _uiState.update { it.copy(archivedConversations = uiModels) }
                }
        }

        // 3. Observe archived count
        viewModelScope.launch {
            chatService.observeArchivedCount().collect { count ->
                _uiState.update { it.copy(archivedCount = count) }
            }
        }
    }

    private suspend fun mapToUiModels(entities: List<ConversationEntity>): List<ConversationUiModel> {
        val now = System.currentTimeMillis()
        return entities.map { entity ->
            var isOutgoing = false
            var status: DeliveryStatus? = null

            if (entity.lastMessageId != null && messageDao != null) {
                val lastMsg = messageDao.getById(entity.lastMessageId)
                if (lastMsg != null) {
                    isOutgoing = lastMsg.direction == MessageDirection.OUTGOING
                    status = try {
                        DeliveryStatus.valueOf(lastMsg.status)
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            ConversationUiModel(
                conversationId = entity.conversationId,
                title = entity.title ?: "Contact",
                preview = entity.lastMessagePreview,
                timestamp = entity.lastMessageTime,
                unreadCount = entity.unreadCount,
                manuallyUnread = entity.manuallyUnread,
                isPinned = entity.isPinned,
                isArchived = entity.isArchived,
                isMuted = NotificationPolicy.isConversationMuted(entity.mutedUntil, now),
                avatarHash = entity.avatarHash,
                isLastMessageOutgoing = isOutgoing,
                lastMessageStatus = status
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        searchQueryFlow.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleSearch(open: Boolean) {
        if (!open) {
            onSearchQueryChanged("")
        }
        _uiState.update { it.copy(isSearching = open) }
    }

    fun openActionMenu(conversation: ConversationUiModel) {
        _uiState.update { it.copy(selectedConversationForMenu = conversation) }
    }

    fun closeActionMenu() {
        _uiState.update { it.copy(selectedConversationForMenu = null) }
    }

    fun togglePin(conversation: ConversationUiModel) {
        viewModelScope.launch {
            chatService.setChatPinned(conversation.conversationId, !conversation.isPinned)
            closeActionMenu()
        }
    }

    fun toggleArchive(conversation: ConversationUiModel) {
        viewModelScope.launch {
            chatService.setChatArchived(conversation.conversationId, !conversation.isArchived)
            closeActionMenu()
        }
    }

    fun showMuteDialog(conversation: ConversationUiModel) {
        _uiState.update { it.copy(showMuteDialogFor = conversation, selectedConversationForMenu = null) }
    }

    fun dismissMuteDialog() {
        _uiState.update { it.copy(showMuteDialogFor = null) }
    }

    fun setMuteDuration(conversationId: String, durationMillis: Long?) {
        viewModelScope.launch {
            val mutedUntil = if (durationMillis != null) {
                if (durationMillis == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + durationMillis
            } else {
                null
            }
            chatService.setChatMuted(conversationId, mutedUntil)
            dismissMuteDialog()
        }
    }

    fun unmute(conversationId: String) {
        viewModelScope.launch {
            chatService.setChatMuted(conversationId, null)
            closeActionMenu()
        }
    }

    fun markAsRead(
        conversationId: String,
        relationshipId: String? = null,
        localIdentityId: String? = null,
        recipientId: String? = null
    ) {
        viewModelScope.launch {
            if (relationshipId != null && localIdentityId != null && recipientId != null) {
                chatService.markConversationRead(conversationId, relationshipId, localIdentityId, recipientId)
            } else {
                chatService.markChatUnread(conversationId, false)
            }
            closeActionMenu()
        }
    }

    fun markAsUnread(conversationId: String) {
        viewModelScope.launch {
            chatService.markChatUnread(conversationId, true)
            closeActionMenu()
        }
    }

    fun promptDeleteChat(conversation: ConversationUiModel) {
        _uiState.update { it.copy(showDeleteDialogFor = conversation, selectedConversationForMenu = null) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialogFor = null) }
    }

    fun confirmDeleteChat(conversationId: String) {
        viewModelScope.launch {
            chatService.deleteChatLocally(conversationId)
            dismissDeleteDialog()
        }
    }
}
