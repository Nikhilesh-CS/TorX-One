package com.torxone.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.connection.ConnectionState
import com.torxone.app.data.entity.MediaEntity
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.entity.ReactionEntity
import com.torxone.app.media.MediaService
import com.torxone.app.media.MediaStatus
import com.torxone.app.media.MediaType
import com.torxone.app.media.VoiceNoteHelper
import com.torxone.app.protocol.ReactionOperation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * State of active voice note recording in composer.
 */
data class VoiceRecordingState(
    val isRecording: Boolean = false,
    val elapsedDurationMs: Long = 0L,
    val amplitudeLevels: List<Float> = emptyList()
)

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
    val voiceRecording: VoiceRecordingState = VoiceRecordingState(),
    val error: String? = null
)

/**
 * ChatViewModel
 *
 * Responsibilities:
 * - Maps Room MessageEntity + ReactionEntity + MediaEntity -> MessageUiModel
 * - Manages typing debouncing (TYPING_START on first keystroke, TYPING_STOP after 2.5s pause)
 * - Observes pairwise presence and updates header status
 * - Manages reply, edit, delete (for me & for everyone)
 * - Handles emoji reaction toggling
 * - Dispatches batch read receipts when conversation is visible
 * - Manages Media sending (Images, Videos, Documents, Voice notes)
 * - Manages Voice recording session (live timer, waveform visualization)
 */
class ChatViewModel(
    private val conversationId: String,
    private val relationshipId: String,
    private val localIdentityId: String,
    private val recipientId: String,
    private val contactName: String,
    private val chatService: ChatService,
    private val presenceService: PresenceService? = null,
    private val mediaService: MediaService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(title = contactName))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentReactionEntities: List<ReactionEntity> = emptyList()

    private var typingDebounceJob: Job? = null
    private var isTypingLocally = false
    private var recordingTimerJob: Job? = null

    init {
        // 1. Observe messages, reactions, local hidden state, and media attachments
        viewModelScope.launch {
            val mediaFlow: Flow<List<MediaEntity>> = mediaService?.observeMediaForConversation(conversationId)
                ?: flowOf(emptyList())

            combine(
                chatService.observeMessages(conversationId),
                chatService.observeReactions(conversationId),
                chatService.observeHiddenMessageIds(conversationId),
                mediaFlow
            ) { msgEntities, reactionEntities, hiddenIds, mediaEntities ->
                currentReactionEntities = reactionEntities
                val hiddenSet = hiddenIds.toSet()
                val visibleEntities = msgEntities.filter { !hiddenSet.contains(it.logicalMessageId) }
                mapToUiModels(visibleEntities, reactionEntities, mediaEntities)
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
        reactions: List<ReactionEntity>,
        mediaEntities: List<MediaEntity>
    ): List<MessageUiModel> {
        val entityMap = entities.associateBy { it.logicalMessageId }
        val reactionsByMessage = reactions.groupBy { it.messageId }
        val mediaByMessage = mediaEntities.associateBy { it.messageId }

        return entities.map { entity ->
            val quoted = entity.replyToMessageId?.let { replyId ->
                val original = entityMap[replyId]
                val originalMedia = mediaByMessage[replyId]

                if (original != null) {
                    val preview = when {
                        original.deletedAt != null -> "This message was deleted"
                        originalMedia != null -> when (originalMedia.mediaType) {
                            "IMAGE" -> "📷 Photo"
                            "VIDEO" -> "🎥 Video"
                            "VOICE_NOTE" -> "🎤 Voice message"
                            "AUDIO" -> "🎵 Audio"
                            "DOCUMENT" -> "📄 ${originalMedia.fileName}"
                            else -> original.body ?: ""
                        }
                        else -> original.body ?: ""
                    }

                    QuotedMessageUiModel(
                        messageId = replyId,
                        senderName = if (original.direction == MessageDirection.OUTGOING) "You" else contactName,
                        previewText = preview
                    )
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

            val messageReactions = if (entity.deletedAt != null) {
                emptyList()
            } else {
                reactionsByMessage[entity.logicalMessageId].orEmpty()
            }
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

            val mediaModel = mediaByMessage[entity.logicalMessageId]?.let { m ->
                MediaUiModel(
                    mediaId = m.mediaId,
                    type = try { MediaType.valueOf(m.mediaType) } catch (_: Exception) { MediaType.IMAGE },
                    fileName = m.fileName,
                    fileSize = m.fileSize,
                    localPath = m.localPath,
                    thumbnailData = m.thumbnailData,
                    durationMs = m.durationMs,
                    waveformData = m.waveformData,
                    status = try { MediaStatus.valueOf(m.status) } catch (_: Exception) { MediaStatus.COMPLETE },
                    progress = m.transferProgress
                )
            }

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
                isEdited = entity.editedAt != null,
                editedAt = entity.editedAt,
                isDeleted = entity.deletedAt != null,
                reactions = reactionSummaries,
                media = mediaModel
            )
        }
    }

    fun onComposerTextChanged(newText: String) {
        _uiState.update { it.copy(composerText = newText) }

        if (presenceService == null) return

        if (newText.isNotBlank()) {
            if (!isTypingLocally) {
                isTypingLocally = true
                viewModelScope.launch {
                    presenceService.sendTypingStart(relationshipId, conversationId)
                }
            }
            typingDebounceJob?.cancel()
            typingDebounceJob = viewModelScope.launch {
                delay(2500)
                if (isTypingLocally) {
                    isTypingLocally = false
                    presenceService.sendTypingStop(relationshipId, conversationId)
                }
            }
        } else {
            if (isTypingLocally) {
                isTypingLocally = false
                typingDebounceJob?.cancel()
                viewModelScope.launch {
                    presenceService.sendTypingStop(relationshipId, conversationId)
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
        if (message.direction == MessageDirection.OUTGOING && !message.isDeleted) {
            _uiState.update {
                it.copy(
                    editingMessage = message,
                    composerText = message.body ?: "",
                    replyingTo = null
                )
            }
        }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingMessage = null, composerText = "") }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val hasReacted = currentReactionEntities.any {
            it.messageId == messageId && it.senderId == localIdentityId && it.emoji == emoji
        }
        val operation = if (hasReacted) ReactionOperation.REMOVE else ReactionOperation.ADD

        viewModelScope.launch {
            try {
                chatService.sendReaction(
                    conversationId = conversationId,
                    relationshipId = relationshipId,
                    localIdentityId = localIdentityId,
                    recipientId = recipientId,
                    targetMessageId = messageId,
                    emoji = emoji,
                    operation = operation
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to update reaction") }
            }
        }
    }

    fun deleteForMe(messageId: String) {
        viewModelScope.launch {
            try {
                chatService.deleteForMe(
                    conversationId = conversationId,
                    messageId = messageId
                )
                mediaService?.deleteMediaForMessage(messageId, cleanupLocalFile = true)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to delete message locally") }
            }
        }
    }

    fun deleteForEveryone(messageId: String) {
        viewModelScope.launch {
            try {
                chatService.deleteForEveryone(
                    conversationId = conversationId,
                    relationshipId = relationshipId,
                    localIdentityId = localIdentityId,
                    recipientId = recipientId,
                    targetMessageId = messageId
                )
                mediaService?.deleteMediaForMessage(messageId, cleanupLocalFile = true)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to delete message for everyone") }
            }
        }
    }

    fun deleteMessage(messageId: String) {
        deleteForEveryone(messageId)
    }

    fun sendText() {
        val text = _uiState.value.composerText.trim()
        if (text.isEmpty()) return

        val editing = _uiState.value.editingMessage
        if (editing != null) {
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

    // ═══════════════════════════════════════════════════════════════
    //  Media Sending Methods
    // ═══════════════════════════════════════════════════════════════

    fun sendImage(fileName: String, bytes: ByteArray, mimeType: String = "image/jpeg", thumbnailBytes: ByteArray? = null) {
        sendMediaInternal(MediaType.IMAGE, fileName, mimeType, bytes, thumbnailBytes = thumbnailBytes)
    }

    fun sendVideo(fileName: String, bytes: ByteArray, mimeType: String = "video/mp4", durationMs: Long? = null, thumbnailBytes: ByteArray? = null) {
        sendMediaInternal(MediaType.VIDEO, fileName, mimeType, bytes, durationMs = durationMs, thumbnailBytes = thumbnailBytes)
    }

    fun sendDocument(fileName: String, bytes: ByteArray, mimeType: String = "application/octet-stream") {
        sendMediaInternal(MediaType.DOCUMENT, fileName, mimeType, bytes)
    }

    fun sendVoiceNote(bytes: ByteArray, durationMs: Long, waveform: ByteArray? = null) {
        sendMediaInternal(
            type = MediaType.VOICE_NOTE,
            fileName = "voice_note.m4a",
            mimeType = "audio/mp4",
            bytes = bytes,
            durationMs = durationMs,
            waveformData = waveform
        )
    }

    private fun sendMediaInternal(
        type: MediaType,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        durationMs: Long? = null,
        thumbnailBytes: ByteArray? = null,
        waveformData: ByteArray? = null
    ) {
        val replyToId = _uiState.value.replyingTo?.logicalMessageId
        _uiState.update { it.copy(replyingTo = null) }

        viewModelScope.launch {
            try {
                mediaService?.sendMedia(
                    conversationId = conversationId,
                    relationshipId = relationshipId,
                    localIdentityId = localIdentityId,
                    recipientId = recipientId,
                    type = type,
                    fileName = fileName,
                    mimeType = mimeType,
                    rawBytes = bytes,
                    durationMs = durationMs,
                    thumbnailBytes = thumbnailBytes,
                    waveformData = waveformData,
                    replyToMessageId = replyToId
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to send media") }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Voice Recording Controls
    // ═══════════════════════════════════════════════════════════════

    fun startVoiceRecording() {
        _uiState.update {
            it.copy(
                voiceRecording = VoiceRecordingState(
                    isRecording = true,
                    elapsedDurationMs = 0L,
                    amplitudeLevels = emptyList()
                )
            )
        }
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                delay(100)
                val elapsed = System.currentTimeMillis() - startTime
                val dummyAmps = List(30) { (it * 3 % 80 + 15) / 100f }
                _uiState.update {
                    it.copy(
                        voiceRecording = it.voiceRecording.copy(
                            elapsedDurationMs = elapsed,
                            amplitudeLevels = dummyAmps
                        )
                    )
                }
            }
        }
    }

    fun cancelVoiceRecording() {
        recordingTimerJob?.cancel()
        _uiState.update { it.copy(voiceRecording = VoiceRecordingState(isRecording = false)) }
    }

    fun finishVoiceRecording() {
        val elapsed = _uiState.value.voiceRecording.elapsedDurationMs
        recordingTimerJob?.cancel()
        _uiState.update { it.copy(voiceRecording = VoiceRecordingState(isRecording = false)) }

        if (elapsed < 500) {
            // Tap too short — treat as accidental tap
            return
        }

        val syntheticAudio = VoiceNoteHelper.generateSyntheticAudio(
            durationSeconds = (elapsed / 1000).toInt().coerceAtLeast(1)
        )
        val waveform = VoiceNoteHelper.generateWaveform()
        sendVoiceNote(syntheticAudio, elapsed, waveform)
    }

    fun cancelMediaTransfer(mediaId: String) {
        viewModelScope.launch {
            mediaService?.cancelTransfer(mediaId)
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
        recordingTimerJob?.cancel()
        if (isTypingLocally) {
            isTypingLocally = false
            viewModelScope.launch {
                presenceService?.sendTypingStop(relationshipId, conversationId)
            }
        }
    }
}
