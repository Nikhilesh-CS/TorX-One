package com.torxone.app.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.chat.*
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import com.torxone.app.media.MediaService
import com.torxone.app.media.MediaType
import com.torxone.app.media.VoiceNoteRecorder
import com.torxone.app.protocol.ReactionOperation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private val mediaService: MediaService? = null,
    private val localMessageStateDao: LocalMessageStateDao? = null,
    private val voiceNoteRecorder: VoiceNoteRecorder? = null,
    private val productivity: ChatProductivityService? = null,
    private val timeline: com.torxone.app.ui.timeline.ChatTimelineRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            isGroup = true,
            title = "Group"
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val typingTimestamps = ConcurrentHashMap<String, Long>()
    private var recordingTimerJob: Job? = null
    private var draftJob: Job? = null
    private var draftChanged = false
    private var restoredReplyId: String? = null
    private var composerBeforeEdit: Pair<String, MessageUiModel?>? = null

    private var presentationClosed = false
    private val presentationJobs = mutableListOf<kotlinx.coroutines.Job>()
    private fun observeUi(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        viewModelScope.launch {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _uiState.update { it.copy(error = "Couldn't load chat updates. Reopen this chat to try again.") } }
        }.also { presentationJobs += it }

    fun stopPresentation() {
        presentationClosed = true
        presentationJobs.forEach { it.cancel() }
        recordingTimerJob?.cancel()
        draftJob?.cancel()
        voiceNoteRecorder?.cancelRecording()
    }

    suspend fun awaitPendingActionsOnClose() {
        // A navigation event must not cancel an already accepted Send/Edit/React intent.
        viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach { it.join() }
    }

    init {
        productivity?.let { service ->
            observeUi {
                service.dao.draft(conversationId)?.let { draft ->
                    if (!draftChanged) {
                        restoredReplyId = draft.replyToMessageId
                        _uiState.update { it.copy(composerText = draft.text,
                            replyingTo = it.messages.find { message -> message.logicalMessageId == restoredReplyId }) }
                    }
                }
            }
            observeUi { service.dao.observeStars(conversationId).collect { ids ->
                _uiState.update { it.copy(starredIds = ids.toSet()) }
            } }
        }
        // 1. Observe Group Entity & Title
        observeUi {
            groupDao.observeById(groupId).filterNotNull().collect { group ->
                _uiState.update { current ->
                    current.copy(
                        title = group.title
                    )
                }
            }
        }

        // 2. Observe Members & Local Participant State
        observeUi {
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
        observeUi {
            timeline?.initialize()
            val messageFlow = if (timeline != null) combine(timeline.messages, timeline.references) { rows, references -> rows to references }
                else messageDao.observeByConversation(conversationId).map { it to emptyList<MessageEntity>() }
            combine(
                messageFlow,
                timeline?.reactions ?: reactionDao.observeForConversation(conversationId),
                combine(contactDao.observeAll(), productivity?.dao?.observeAliases() ?: flowOf(emptyList())) { contacts, aliases ->
                    val names = aliases.associate { it.contactId to it.alias }
                    contacts.map { peer -> names[peer.contactId]?.let { peer.copy(displayName = it) } ?: peer }
                },
                if (timeline != null) flowOf(emptyList()) else localMessageStateDao?.observeHiddenMessageIds(conversationId) ?: flowOf(emptyList()),
                timeline?.media ?: mediaDao?.observeForConversation(conversationId) ?: flowOf(emptyList())
            ) { messageData, reactions, contacts, hiddenMessageIds, mediaItems ->
                val (messages, referenceMessages) = messageData
                val quoteMessages = (referenceMessages + messages).associateBy { it.logicalMessageId }
                val hidden = hiddenMessageIds.toHashSet()
                val contactsMap = contacts.associateBy { it.remoteIdentityId }
                val reactionsByMessage = reactions.groupBy { it.messageId }
                val mediaByMessage = mediaItems.associateBy { it.messageId }

                messages.filterNot { it.logicalMessageId in hidden }.map { msg ->
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
                        val targetMsg = quoteMessages[replyId]
                        if (targetMsg != null) {
                            val authorName = if (targetMsg.senderId == localIdentityId) "You"
                            else contactsMap[targetMsg.senderId]?.displayName ?: targetMsg.senderId.take(8)
                            QuotedMessageUiModel(
                                messageId = replyId,
                                senderName = authorName,
                                previewText = if (targetMsg.deletedAt != null) "This message was deleted" else targetMsg.body ?: "Attachment"
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

                    val mediaModel = mediaByMessage[msg.logicalMessageId]?.let { media ->
                        MediaUiModel(
                            mediaId = media.mediaId,
                            type = runCatching { MediaType.valueOf(media.mediaType) }.getOrDefault(MediaType.IMAGE),
                            fileName = media.fileName,
                            mimeType = media.mimeType,
                            fileSize = media.fileSize,
                            localPath = media.localPath,
                            thumbnailData = media.thumbnailData,
                            durationMs = media.durationMs,
                            waveformData = media.waveformData,
                            status = runCatching { com.torxone.app.media.MediaStatus.valueOf(media.status) }
                                .getOrDefault(com.torxone.app.media.MediaStatus.COMPLETE),
                            progress = media.transferProgress
                        )
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
                        expiresAt = msg.expiresAt,
                        reactions = reactionSummaries,
                        media = mediaModel
                    )
                }
            }.collect { messageList ->
                _uiState.update { it.copy(messages = messageList,
                    replyingTo = it.replyingTo ?: messageList.find { message -> message.logicalMessageId == restoredReplyId }) }
            }
        }

        timeline?.let { repository -> observeUi {
            repository.info.collect { window -> _uiState.update { it.copy(
                hasEarlierMessages = window.hasEarlier, isLatestWindow = window.isLatest,
                newerMessageCount = window.newerIncoming) } }
        } }

        // 4. Mark group read upon opening
        markConversationRead()
    }

    fun onComposerTextChanged(newText: String) {
        _uiState.update { it.copy(composerText = newText) }
        persistDraft()
    }

    fun sendText() {
        val text = _uiState.value.composerText.trim()
        if (text.isEmpty() || !_uiState.value.isParticipantActive || _uiState.value.isSending) return

        val replyToId = _uiState.value.replyingTo?.logicalMessageId
        val editing = _uiState.value.editingMessage

        if (editing != null) {
            _uiState.update { it.copy(isSending = true, error = null) }
            viewModelScope.launch {
                try {
                    groupService.sendGroupEdit(groupId, editing.logicalMessageId, text)
                    if (_uiState.value.editingMessage?.logicalMessageId == editing.logicalMessageId &&
                        _uiState.value.composerText.trim() == text) cancelEditing()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _uiState.update { it.copy(error = failure.message ?: "Edit failed") }
                } finally { _uiState.update { it.copy(isSending = false) } }
            }
            return
        }

        draftJob?.cancel()
        _uiState.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            try {
                groupService.sendGroupText(
                    groupId = groupId,
                    text = text,
                    replyToMessageId = replyToId
                )
                restoredReplyId = null
                _uiState.update { if (it.composerText.trim() == text) it.copy(composerText = "", replyingTo = null) else it }
                persistDraft()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun onReply(message: MessageUiModel) {
        restoredReplyId = null
        _uiState.update { it.copy(replyingTo = message, editingMessage = null) }
        persistDraft()
    }

    fun cancelReply() {
        restoredReplyId = null
        _uiState.update { it.copy(replyingTo = null) }
        persistDraft()
    }

    fun startEditing(message: MessageUiModel) {
        if (message.direction != MessageDirection.OUTGOING || message.isDeleted) return
        if (_uiState.value.editingMessage == null) {
            composerBeforeEdit = _uiState.value.composerText to _uiState.value.replyingTo
            persistDraft()
        }
        _uiState.update {
            it.copy(
                editingMessage = message,
                replyingTo = null,
                composerText = message.body.orEmpty()
            )
        }
    }

    fun cancelEditing() {
        val draft = composerBeforeEdit
        composerBeforeEdit = null
        _uiState.update { it.copy(editingMessage = null, composerText = draft?.first.orEmpty(), replyingTo = draft?.second) }
        persistDraft()
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
            val message = messageDao.getById(messageId) ?: return@launch
            localMessageStateDao?.upsert(
                LocalMessageStateEntity(
                    messageId = messageId,
                    conversationId = message.conversationId,
                    hiddenLocally = true,
                    hiddenAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun deleteForEveryone(messageId: String) {
        viewModelScope.launch {
            groupService.sendGroupDelete(groupId, messageId)
        }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }

    fun loadEarlierMessages() = readTimeline { loadEarlier() }
    fun openTimelineMessage(messageId: String) = readTimeline { openMessage(messageId) }
    fun jumpToLatestMessages() = readTimeline { jumpToLatest() }
    private fun readTimeline(action: suspend com.torxone.app.ui.timeline.ChatTimelineRepository.() -> Unit) {
        val repository = timeline ?: return
        viewModelScope.launch {
            try { repository.action() }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _uiState.update { it.copy(error = "Could not load this part of the conversation. Try again.") }
            }
        }
    }

    suspend fun persistPendingDraftOnClose() {
        if (!draftChanged) return
        val service = productivity ?: return
        val snapshot = _uiState.value
        val original = composerBeforeEdit
        service.saveDraft(conversationId, original?.first ?: snapshot.composerText,
            original?.second?.logicalMessageId ?: snapshot.replyingTo?.logicalMessageId ?: restoredReplyId)
    }

    override fun onCleared() {
        recordingTimerJob?.cancel()
        draftJob?.cancel()
        voiceNoteRecorder?.cancelRecording()
        super.onCleared()
    }

    fun clearScheduledComposer(text: String, replyId: String?) {
        val current = _uiState.value
        if (current.editingMessage != null || current.composerText != text || current.replyingTo?.logicalMessageId != replyId) return
        restoredReplyId = null
        _uiState.update { it.copy(composerText = "", replyingTo = null) }
        persistDraft(0)
    }

    private fun persistDraft(debounceMs: Long = 500) {
        draftChanged = true
        if (_uiState.value.editingMessage != null) return
        val service = productivity ?: return
        val snapshot = _uiState.value
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(debounceMs)
            try { service.saveDraft(conversationId, snapshot.composerText, snapshot.replyingTo?.logicalMessageId ?: restoredReplyId) }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _uiState.update { it.copy(error = "Unable to save draft: ${error.message}") }
            }
        }
    }

    fun setStarred(ids: Set<String>, enabled: Boolean) { viewModelScope.launch {
        try { productivity?.setStarred(ids, enabled) }
        catch (error: Exception) { _uiState.update { it.copy(error = error.message) } }
    } }

    fun downloadMedia(mediaId: String) {
        viewModelScope.launch {
            try { requireNotNull(mediaService) { "Media service unavailable" }.resumeTransfer(mediaId) }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _uiState.update { it.copy(error = error.message ?: "Unable to download attachment") }
            }
        }
    }

    fun cancelMediaTransfer(mediaId: String) {
        viewModelScope.launch {
            try { requireNotNull(mediaService) { "Media service unavailable" }.cancelTransfer(mediaId) }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _uiState.update { it.copy(error = "Unable to cancel this transfer. Try again.") }
            }
        }
    }

    fun pauseMediaTransfer(mediaId: String) {
        viewModelScope.launch {
            try { requireNotNull(mediaService) { "Media service unavailable" }.pauseTransfer(mediaId) }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _uiState.update { it.copy(error = "Unable to pause this transfer. Try again.") }
            }
        }
    }

    fun sendImage(fileName: String, bytes: ByteArray, mimeType: String) {
        if (!_uiState.value.isParticipantActive) return
        viewModelScope.launch {
            try {
                if (mediaService != null) {
                    val isVideo = mimeType.startsWith("video/")
                    sendGroupMedia(if (isVideo) MediaType.VIDEO else MediaType.IMAGE, fileName, mimeType, bytes)
                } else {
                    groupService.sendGroupText(groupId, "📷 $fileName")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun sendDocument(fileName: String, bytes: ByteArray, mimeType: String) {
        if (!_uiState.value.isParticipantActive) return
        viewModelScope.launch {
            try {
                if (mediaService != null) {
                    sendGroupMedia(MediaType.DOCUMENT, fileName, mimeType, bytes)
                } else {
                    groupService.sendGroupText(groupId, "📄 $fileName")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    private suspend fun sendGroupMedia(
        type: MediaType,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        durationMs: Long? = null,
        waveform: ByteArray? = null
    ) {
        val service = requireNotNull(mediaService)
        val group = groupDao.getById(groupId) ?: error("Group no longer exists")
        val recipients = groupMemberDao.getActiveMembers(groupId)
            .filter { it.memberIdentityId != localIdentityId }
            .map { MediaService.GroupMediaRecipient(it.memberIdentityId, it.relationshipId) }
        service.sendGroupMedia(
            groupId = groupId,
            groupEpoch = group.epoch,
            localIdentityId = localIdentityId,
            recipients = recipients,
            type = type,
            fileName = fileName,
            mimeType = mimeType,
            rawBytes = bytes,
            durationMs = durationMs,
            waveformData = waveform,
            replyToMessageId = _uiState.value.replyingTo?.logicalMessageId
        )
        _uiState.update { it.copy(replyingTo = null) }
    }

    fun startVoiceRecording() {
        if (!_uiState.value.isParticipantActive) return
        val recorder = voiceNoteRecorder ?: run {
            _uiState.update { it.copy(error = "Audio recorder unavailable") }
            return
        }
        if (!recorder.startRecording()) {
            _uiState.update { it.copy(error = "Microphone recording permission or device unavailable") }
            return
        }
        _uiState.update { it.copy(voiceRecording = VoiceRecordingState(isRecording = true)) }
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val amplitudes = mutableListOf<Float>()
            while (isActive) {
                delay(100)
                amplitudes += recorder.amplitudeFlow.value
                if (amplitudes.size > 30) amplitudes.removeAt(0)
                _uiState.update {
                    it.copy(voiceRecording = it.voiceRecording.copy(
                        elapsedDurationMs = System.currentTimeMillis() - startedAt,
                        amplitudeLevels = amplitudes.toList()
                    ))
                }
            }
        }
    }

    fun cancelVoiceRecording() {
        recordingTimerJob?.cancel()
        voiceNoteRecorder?.cancelRecording()
        _uiState.update { it.copy(voiceRecording = VoiceRecordingState()) }
    }

    fun finishVoiceRecording() {
        val elapsed = _uiState.value.voiceRecording.elapsedDurationMs
        recordingTimerJob?.cancel()
        _uiState.update { it.copy(voiceRecording = VoiceRecordingState()) }
        if (elapsed < 500) {
            voiceNoteRecorder?.cancelRecording()
            return
        }
        val result = voiceNoteRecorder?.stopRecording()
        if (result == null) {
            _uiState.update { it.copy(error = "Audio recording failed or microphone was unavailable") }
            return
        }
        viewModelScope.launch {
            try {
                sendGroupMedia(MediaType.VOICE_NOTE, "voice_note.wav", "audio/wav", result.audioData, result.durationMs, result.waveform)
            } catch (error: Exception) {
                _uiState.update { it.copy(error = error.message ?: "Failed to send voice note") }
            }
        }
    }

    fun markConversationRead() {
        viewModelScope.launch {
            if (presentationClosed || timeline?.isVisible == false) return@launch
            try { groupService.markGroupRead(groupId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _uiState.update { it.copy(error = "Couldn't update read status. Reopen this chat to try again.") } }
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
            val contacts = contactDao.getAll().associateBy { it.remoteIdentityId }
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
