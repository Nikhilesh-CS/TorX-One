package com.torxone.app.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.chat.*
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import com.torxone.app.media.MediaService
import com.torxone.app.media.MediaType
import com.torxone.app.protocol.ReactionOperation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * GroupChatViewModel — Presentation layer for secure direct group chats.
 *
 * Responsibilities:
 * - Observes group messages and maps to MessageUiModel with sender display names & avatars
 * - Manages pairwise encrypted fan-out through GroupService (text, reactions, edits, deletes)
 * - Tracks group active participant status (disables composer if removed/left)
 * - Aggregates group typing indicators ("Alice is typing...", "Alice and Bob are typing...")
 * - Exposes per-recipient delivery info through GroupService.getDeliverySummary()
 * - Dispatches group read receipts to authors on open
 */
class GroupChatViewModel(
    val groupId: String,
    val conversationId: String,
    val localIdentityId: String,
    private val groupService: GroupService,
    private val groupDao: GroupDao,
    private val groupMemberDao: GroupMemberDao,
    private val messageDao: MessageDao,
    private val reactionDao: ReactionDao,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val mediaDao: MediaDao? = null,
    private val mediaService: MediaService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            isGroup = true,
            title = "Group"
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val typingTimestamps = ConcurrentHashMap<String, Long>()

    init {
        // 1. Observe Group Entity & Title
        viewModelScope.launch {
            groupDao.observeById(groupId).filterNotNull().collect { group ->
                _uiState.update { current ->
                    current.copy(
                        title = group.title
                    )
                }
            }
        }

        // 2. Observe Members & Local Participant State
        viewModelScope.launch {
            groupMemberDao.observeMembers(groupId).collect { members ->
                val activeCount = members.count { it.state == GroupMemberState.ACTIVE.name }
                val self = members.find { it.memberIdentityId == localIdentityId }
                val isActive = self?.state == GroupMemberState.ACTIVE.name

                _uiState.update { current ->
                    current.copy(
                        participantCount = activeCount,
                        isParticipantActive = isActive,
                        subtitle = if (isActive) "$activeCount participants"
                            else "You can't send messages because you're no longer a participant."
                    )
                }
            }
        }

        // 3. Observe Messages, Reactions, and Contacts to produce MessageUiModel
        viewModelScope.launch {
            combine(
                messageDao.observeByConversation(conversationId),
                reactionDao.observeForConversation(conversationId),
                contactDao.observeAll()
            ) { messages, reactions, contacts ->
                val contactsMap = contacts.associateBy { it.contactId }
                val reactionsByMessage = reactions.groupBy { it.messageId }

                messages.map { msg ->
                    val senderName = if (msg.senderId == localIdentityId) {
                        "You"
                    } else {
                        contactsMap[msg.senderId]?.displayName ?: msg.senderId.take(8)
                    }
                    val senderAvatar = contactsMap[msg.senderId]?.avatarHash

                    // Reactions summary
                    val msgReactions = reactionsByMessage[msg.logicalMessageId].orEmpty()
                    val reactionSummaries = msgReactions.groupBy { it.emoji }.map { (emoji, list) ->
                        ReactionSummaryUiModel(
                            emoji = emoji,
                            count = list.size,
                            userReacted = list.any { it.senderId == localIdentityId }
                        )
                    }

                    // Quoted snippet (if replying)
                    val quoted = msg.replyToMessageId?.let { replyId ->
                        val targetMsg = messages.find { it.logicalMessageId == replyId }
                        if (targetMsg != null) {
                            val authorName = if (targetMsg.senderId == localIdentityId) "You"
                            else contactsMap[targetMsg.senderId]?.displayName ?: targetMsg.senderId.take(8)
                            QuotedMessageUiModel(
                                messageId = replyId,
                                senderName = authorName,
                                previewText = targetMsg.body ?: "Attachment"
                            )
                        } else {
                            QuotedMessageUiModel(
                                messageId = replyId,
                                senderName = "Original message",
                                previewText = "Message unavailable",
                                isUnavailable = true
                            )
                        }
                    }

                    MessageUiModel(
                        logicalMessageId = msg.logicalMessageId,
                        conversationId = msg.conversationId,
                        senderId = msg.senderId,
                        senderDisplayName = if (msg.direction == MessageDirection.INCOMING) senderName else null,
                        senderAvatarHash = senderAvatar,
                        body = msg.body,
                        direction = msg.direction,
                        status = try {
                            DeliveryStatus.valueOf(msg.status)
                        } catch (_: Exception) {
                            DeliveryStatus.QUEUED
                        },
                        createdAt = msg.createdAt,
                        deliveredAt = msg.deliveredAt,
                        readAt = msg.readAt,
                        replyToMessageId = msg.replyToMessageId,
                        quotedMessage = quoted,
                        isEdited = msg.editVersion > 0,
                        editedAt = msg.editedAt,
                        isDeleted = msg.deletedAt != null,
                        reactions = reactionSummaries
                    )
                }
            }.collect { messageList ->
                _uiState.update { it.copy(messages = messageList) }
            }
        }

        // 4. Mark group read upon opening
        markConversationRead()
    }

    fun onComposerTextChanged(newText: String) {
        _uiState.update { it.copy(composerText = newText) }
    }

    fun sendText() {
        val text = _uiState.value.composerText.trim()
        if (text.isEmpty() || !_uiState.value.isParticipantActive) return

        val replyToId = _uiState.value.replyingTo?.logicalMessageId
        val editing = _uiState.value.editingMessage

        if (editing != null) {
            viewModelScope.launch {
                groupService.sendGroupEdit(groupId, editing.logicalMessageId, text)
                _uiState.update { it.copy(editingMessage = null, composerText = "") }
            }
            return
        }

        _uiState.update { it.copy(composerText = "", replyingTo = null) }
        viewModelScope.launch {
            try {
                groupService.sendGroupText(
                    groupId = groupId,
                    text = text,
                    replyToMessageId = replyToId
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
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
        if (message.direction != MessageDirection.OUTGOING || message.isDeleted) return
        _uiState.update {
            it.copy(
                editingMessage = message,
                replyingTo = null,
                composerText = message.body.orEmpty()
            )
        }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingMessage = null, composerText = "") }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val msg = _uiState.value.messages.find { it.logicalMessageId == messageId } ?: return
        val userReacted = msg.reactions.any { it.emoji == emoji && it.userReacted }
        val op = if (userReacted) ReactionOperation.REMOVE else ReactionOperation.ADD

        viewModelScope.launch {
            groupService.sendGroupReaction(groupId, messageId, emoji, op)
        }
    }

    fun deleteForMe(messageId: String) {
        viewModelScope.launch {
            // Local delete: updates message locally
            messageDao.markDeleted(messageId, System.currentTimeMillis())
        }
    }

    fun deleteForEveryone(messageId: String) {
        viewModelScope.launch {
            groupService.sendGroupDelete(groupId, messageId)
        }
    }

    fun sendImage(fileName: String, bytes: ByteArray) {
        if (!_uiState.value.isParticipantActive) return
        viewModelScope.launch {
            try {
                // Send text preview or file notice
                groupService.sendGroupText(groupId, "📷 $fileName")
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun sendDocument(fileName: String, bytes: ByteArray) {
        if (!_uiState.value.isParticipantActive) return
        viewModelScope.launch {
            try {
                groupService.sendGroupText(groupId, "📄 $fileName")
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun markConversationRead() {
        viewModelScope.launch {
            groupService.markGroupRead(groupId)
        }
    }

    suspend fun getDeliverySummary(messageId: String): GroupMessageDeliverySummary? {
        return groupService.getDeliverySummary(messageId)
    }

    fun onPeerTypingReceived(senderId: String) {
        if (senderId == localIdentityId) return
        typingTimestamps[senderId] = System.currentTimeMillis()
        recomputeTypingStatus()
    }

    fun onPeerTypingStopped(senderId: String) {
        typingTimestamps.remove(senderId)
        recomputeTypingStatus()
    }

    private fun recomputeTypingStatus() {
        val now = System.currentTimeMillis()
        typingTimestamps.entries.removeIf { now - it.value > 3000L }

        if (typingTimestamps.isEmpty()) {
            val count = _uiState.value.participantCount ?: 0
            _uiState.update {
                it.copy(
                    isTyping = false,
                    typingText = null,
                    subtitle = "$count participants"
                )
            }
            return
        }

        viewModelScope.launch {
            val contacts = contactDao.getAll().associateBy { it.contactId }
            val names = typingTimestamps.keys.map { contacts[it]?.displayName ?: it.take(8) }

            val text = when {
                names.size == 1 -> "${names[0]} is typing…"
                names.size == 2 -> "${names[0]} and ${names[1]} are typing…"
                else -> "Several people are typing…"
            }

            _uiState.update {
                it.copy(
                    isTyping = true,
                    typingText = text,
                    subtitle = text
                )
            }
        }
    }
}
