package com.astramesh.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class MessageLifecycleState {
    DRAFT,
    QUEUED,
    ENCRYPTING,
    SENDING,
    TRANSPORT_SELECTED,
    IN_TRANSIT,
    DELIVERED,
    READ,
    ARCHIVED,
    FAILED,
    RETRYING,
    CANCELLED,
    EXPIRED
}

enum class TransportType {
    BLUETOOTH, WIFI_DIRECT, TOR, AUTO, NONE
}

data class MessagePayload(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val receiverId: String,
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val lifecycleState: MessageLifecycleState = MessageLifecycleState.QUEUED,
    val transportType: TransportType = TransportType.NONE,
    val isEncrypted: Boolean = true,
    val retryCount: Int = 0,
    val replyToId: String? = null,
    val hasAttachments: Boolean = false,
    val messageType: String = "TEXT",
    val fileName: String? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null,
    val localUri: String? = null,
    val thumbnailUri: String? = null,
    val transferProgress: Int? = null,
    val reactions: Map<String, String> = emptyMap() // Map of senderKey -> Emoji
)

/**
 * ConversationEngine
 * Decouples the UI from message ordering, deduplication, and sync reconciliation.
 * Replaces the UI-driven list with an autonomous state machine.
 */
class ConversationEngine {
    
    private val _messages = MutableStateFlow<List<MessagePayload>>(emptyList())
    val messages: StateFlow<List<MessagePayload>> = _messages.asStateFlow()

    private val messageMap = mutableMapOf<String, MessagePayload>()
    private val pendingQueue = mutableListOf<MessagePayload>()
    private val failedQueue = mutableListOf<MessagePayload>()

    /**
     * Handles incoming messages (both received and sent locally), ensuring 
     * stable chronological ordering and deduplication.
     */
    fun ingestMessage(payload: MessagePayload) {
        // Deduplicate
        if (messageMap.containsKey(payload.id)) {
            val existing = messageMap[payload.id]!!
            // Update if the new state is an progression
            if (payload.lifecycleState.ordinal > existing.lifecycleState.ordinal) {
                updateMessageState(payload.id, payload.lifecycleState)
            }
            return
        }

        messageMap[payload.id] = payload
        
        // Handle out-of-order arrival by re-sorting
        val sortedList = messageMap.values.sortedBy { it.timestamp }
        _messages.value = sortedList

        // Route to respective queues based on state
        when (payload.lifecycleState) {
            MessageLifecycleState.QUEUED -> pendingQueue.add(payload)
            MessageLifecycleState.FAILED -> failedQueue.add(payload)
            else -> {} // Active or terminal state
        }
    }

    fun updateMessageState(messageId: String, newState: MessageLifecycleState, newTransport: TransportType? = null) {
        val msg = messageMap[messageId] ?: return
        
        val updatedMsg = msg.copy(
            lifecycleState = newState,
            transportType = newTransport ?: msg.transportType
        )
        messageMap[messageId] = updatedMsg
        
        // Re-publish state
        _messages.value = messageMap.values.sortedBy { it.timestamp }
    }

    fun getPendingCount(): Int = pendingQueue.size
    fun getFailedCount(): Int = failedQueue.size
    
    fun retryFailedMessages() {
        val toRetry = failedQueue.toList()
        failedQueue.clear()
        
        toRetry.forEach { 
            updateMessageState(it.id, MessageLifecycleState.RETRYING)
            val retried = it.copy(retryCount = it.retryCount + 1)
            ingestMessage(retried)
        }
    }
}
