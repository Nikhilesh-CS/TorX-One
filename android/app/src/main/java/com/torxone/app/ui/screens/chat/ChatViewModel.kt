package com.torxone.app.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.MessageEntity
import com.torxone.app.engine.*
import com.torxone.app.network.MessageRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(
    val contactKey: String,
    private val db: AppDatabase,
    private val messageRouter: MessageRouter
) : ViewModel() {

    val conversationEngine = ConversationEngine()
    val searchEngine = ChatSearchEngine(conversationEngine)

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _contactName = MutableStateFlow("Chat")
    val contactName: StateFlow<String> = _contactName

    private val _contactEndpoint = MutableStateFlow("")
    val contactEndpoint: StateFlow<String> = _contactEndpoint

    private val _contactOnion = MutableStateFlow("")
    val contactOnion: StateFlow<String> = _contactOnion

    val unreadCount: StateFlow<Int> = db.messageDao()
        .getUnreadCountForContact(contactKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        loadContact()
        observeMessages()
    }

    private fun loadContact() {
        viewModelScope.launch(Dispatchers.IO) {
            val contact = db.contactDao().getContact(contactKey)
            if (contact != null) {
                _contactName.value = contact.name
                _contactEndpoint.value = contact.endpointId
                _contactOnion.value = contact.onionAddress
            }
        }
    }

    private val _messageLimit = MutableStateFlow(100)
    
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMessages() {
        viewModelScope.launch(Dispatchers.IO) {
            _messageLimit.flatMapLatest { limit ->
                db.messageDao().getMessagesForContact(contactKey, limit)
            }.collect { entities ->
                _isLoading.value = false
                val payloads = entities.map { entity ->
                    val transport = when (entity.transport) {
                        "NEARBY" -> TransportType.BLUETOOTH
                        "TOR" -> TransportType.TOR
                        null -> TransportType.NONE
                        else -> TransportType.AUTO
                    }
                    val lifecycle = when (entity.status) {
                        "pending" -> MessageLifecycleState.QUEUED
                        "queued" -> MessageLifecycleState.QUEUED
                        "sending" -> MessageLifecycleState.SENDING
                        "sent" -> MessageLifecycleState.IN_TRANSIT
                        "delivered" -> MessageLifecycleState.DELIVERED
                        "read" -> MessageLifecycleState.READ
                        "receiving" -> MessageLifecycleState.IN_TRANSIT
                        "failed" -> MessageLifecycleState.FAILED
                        else -> MessageLifecycleState.QUEUED
                    }
                    
                    val reactionsMap = try {
                        val map = mutableMapOf<String, List<String>>()
                        if (!entity.reactionsJson.isNullOrBlank()) {
                            val json = org.json.JSONObject(entity.reactionsJson)
                            for (key in json.keys()) {
                                val value = json.get(key)
                                map[key] = when (value) {
                                    is org.json.JSONArray -> List(value.length()) { index -> value.optString(index) }
                                        .filter { it.isNotBlank() }
                                    is String -> listOf(value).filter { it.isNotBlank() }
                                    else -> emptyList()
                                }
                            }
                        }
                        map
                    } catch (e: Exception) {
                        emptyMap<String, List<String>>()
                    }

                    val inferredMessageType = when {
                        entity.messageType != "TEXT" -> entity.messageType
                        entity.text.startsWith("Media Message (IMAGE)", ignoreCase = true) -> "IMAGE"
                        entity.text.startsWith("Media Message (VIDEO)", ignoreCase = true) -> "VIDEO"
                        entity.text.startsWith("Media Message (AUDIO)", ignoreCase = true) -> "AUDIO"
                        entity.text.startsWith("Media Message (VOICE)", ignoreCase = true) -> "VOICE"
                        entity.text.startsWith("Media Message (DOCUMENT)", ignoreCase = true) -> "DOCUMENT"
                        entity.mimeType?.startsWith("image/") == true -> "IMAGE"
                        entity.mimeType?.startsWith("video/") == true -> "VIDEO"
                        entity.mimeType?.startsWith("audio/") == true -> "AUDIO"
                        entity.fileName != null || entity.localUri != null -> "DOCUMENT"
                        else -> "TEXT"
                    }
                    val hasMediaPayload = inferredMessageType != "TEXT" ||
                        entity.fileName != null ||
                        entity.localUri != null ||
                        entity.thumbnailUri != null ||
                        entity.transferProgress != null

                    MessagePayload(
                        id = entity.messageId,
                        senderId = if (entity.direction == "sent") "me" else contactKey,
                        receiverId = if (entity.direction == "sent") contactKey else "me",
                        text = entity.text,
                        timestamp = entity.timestamp,
                        lifecycleState = lifecycle,
                        transportType = transport,
                        replyToId = entity.replyToId,
                        replyToText = entity.replyToText,
                        replyToSender = entity.replyToSender,
                        replyToType = entity.replyToType,
                        hasAttachments = hasMediaPayload,
                        messageType = inferredMessageType,
                        fileName = entity.fileName,
                        fileSize = entity.fileSize,
                        mimeType = entity.mimeType,
                        localUri = entity.localUri,
                        thumbnailUri = entity.thumbnailUri,
                        transferProgress = entity.transferProgress,
                        reactions = reactionsMap
                    )
                }
                conversationEngine.replaceMessages(payloads)
            }
        }
    }
    
    fun loadMoreMessages() {
        _messageLimit.value += 100
    }

    fun sendMessage(text: String, replyToId: String? = null) {
        viewModelScope.launch {
            val replyTarget = replyToId?.let { id ->
                conversationEngine.messages.value.firstOrNull { it.id == id }
            }
            val result = messageRouter.sendMessage(
                contactKey = contactKey,
                text = text,
                replyToId = replyTarget?.id,
                replyToText = replyTarget?.replyPreviewText(),
                replyToSender = replyTarget?.senderId,
                replyToType = replyTarget?.messageType
            )
            // The DB observation will pick up the new message and feed it to conversationEngine
        }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val cleanEmoji = emoji.trim()
        if (messageId.isBlank() || cleanEmoji.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            messageRouter.toggleReaction(contactKey, messageId, cleanEmoji)
        }
    }

    fun markVisibleMessagesRead() {
        viewModelScope.launch(Dispatchers.IO) {
            val unread = db.messageDao().getUnreadMessagesSync(contactKey)
            if (unread.isEmpty()) return@launch
            unread.forEach { messageRouter.sendReadReceipt(it.messageId, contactKey) }
            db.messageDao().markMessagesAsRead(contactKey)
        }
    }

    fun deleteMessages(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            messageIds.forEach { db.messageDao().deleteMessage(it) }
        }
    }
}

private fun MessagePayload.replyPreviewText(): String {
    val cleanText = text.trim()
    return when (messageType.uppercase()) {
        "TEXT" -> cleanText.ifBlank { "Message" }
        "IMAGE" -> cleanText.ifBlank { fileName?.let { "Image: $it" } ?: "Image" }
        "VIDEO" -> cleanText.ifBlank { fileName?.let { "Video: $it" } ?: "Video" }
        "AUDIO" -> cleanText.ifBlank { fileName?.let { "Audio: $it" } ?: "Audio" }
        "VOICE" -> cleanText.ifBlank { "Voice note" }
        "STICKER" -> cleanText.ifBlank { "Sticker" }
        "GIF" -> cleanText.ifBlank { "GIF" }
        "DOCUMENT" -> cleanText.ifBlank { fileName?.let { "Document: $it" } ?: "Document" }
        "CONTACT" -> cleanText.ifBlank { "Contact" }
        "POLL" -> cleanText.ifBlank { "Poll" }
        else -> cleanText.ifBlank { messageType.lowercase().replaceFirstChar { it.titlecase() } }
    }
}



