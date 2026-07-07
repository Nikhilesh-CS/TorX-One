package com.astramesh.app.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.data.MessageEntity
import com.astramesh.app.engine.*
import com.astramesh.app.network.MessageRouter
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
                entities.forEach { entity ->
                    val transport = when (entity.transport) {
                        "NEARBY" -> TransportType.BLUETOOTH
                        "TOR" -> TransportType.TOR
                        null -> TransportType.NONE
                        else -> TransportType.AUTO
                    }
                    val lifecycle = when (entity.status) {
                        "sent" -> MessageLifecycleState.IN_TRANSIT
                        "delivered" -> MessageLifecycleState.DELIVERED
                        "read" -> MessageLifecycleState.READ
                        "failed" -> MessageLifecycleState.FAILED
                        else -> MessageLifecycleState.DELIVERED
                    }
                    
                    val reactionsMap = try {
                        val map = mutableMapOf<String, String>()
                        if (!entity.reactionsJson.isNullOrBlank()) {
                            val json = org.json.JSONObject(entity.reactionsJson)
                            for (key in json.keys()) {
                                map[key] = json.getString(key)
                            }
                        }
                        map
                    } catch (e: Exception) {
                        emptyMap<String, String>()
                    }

                    val payload = MessagePayload(
                        id = entity.messageId,
                        senderId = if (entity.direction == "sent") "me" else contactKey,
                        receiverId = if (entity.direction == "sent") contactKey else "me",
                        text = entity.text,
                        timestamp = entity.timestamp,
                        lifecycleState = lifecycle,
                        transportType = transport,
                        replyToId = entity.replyToId,
                        hasAttachments = entity.messageType != "TEXT",
                        messageType = entity.messageType,
                        fileName = entity.fileName,
                        fileSize = entity.fileSize,
                        mimeType = entity.mimeType,
                        localUri = entity.localUri,
                        thumbnailUri = entity.thumbnailUri,
                        transferProgress = entity.transferProgress,
                        reactions = reactionsMap
                    )
                    conversationEngine.ingestMessage(payload)

                    // Auto-read
                    if (entity.direction == "received" && entity.status != "read") {
                        db.messageDao().updateMessageStatus(entity.messageId, "read")
                        messageRouter.sendReadReceipt(entity.messageId, contactKey)
                    }
                }
            }
        }
    }
    
    fun loadMoreMessages() {
        _messageLimit.value += 100
    }

    fun sendMessage(text: String, replyToId: String? = null) {
        viewModelScope.launch {
            val result = messageRouter.sendMessage(contactKey, text)
            // The DB observation will pick up the new message and feed it to conversationEngine
        }
    }
}



