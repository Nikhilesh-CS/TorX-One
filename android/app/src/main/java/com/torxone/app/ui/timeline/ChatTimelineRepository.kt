package com.torxone.app.ui.timeline

import com.torxone.app.data.dao.ChatTimelineDao
import com.torxone.app.data.dao.TimelineCursor
import com.torxone.app.data.entity.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TimelineWindowInfo(val hasEarlier: Boolean = false, val isLatest: Boolean = true, val newerIncoming: Int = 0)

/** Bounded, read-only UI projection. All writes remain with the existing messaging services. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatTimelineRepository(
    private val dao: ChatTimelineDao,
    private val conversationId: String,
    private val initialMessageId: String? = null
) {
    companion object { const val WINDOW_SIZE = 500; const val OVERLAP_SIZE = 100 }
    private val lock = Mutex()
    private val initialized = MutableStateFlow(false)
    private val upper = MutableStateFlow<TimelineCursor?>(null)
    private val visible = MutableStateFlow(true)
    private var currentMessages: List<MessageEntity> = emptyList()
    private val observedMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    private data class ReadRequest(val active: Boolean, val cursor: TimelineCursor?)
    private val requests = combine(initialized, upper, visible) { ready, cursor, shown -> ReadRequest(ready && shown, cursor) }
        .distinctUntilChanged()
    private fun <T> observe(query: (TimelineCursor?) -> Flow<T>): Flow<T> =
        requests.flatMapLatest { if (it.active) query(it.cursor) else emptyFlow() }

    val messages = observe { cursor ->
        dao.observeWindow(conversationId, cursor?.created_at, cursor?.logical_message_id, WINDOW_SIZE)
    }.onEach { currentMessages = it; observedMessages.value = it }
    val references = observe { cursor ->
        dao.observeReferences(conversationId, cursor?.created_at, cursor?.logical_message_id, WINDOW_SIZE)
    }
    val reactions = observe { cursor ->
        dao.observeReactions(conversationId, cursor?.created_at, cursor?.logical_message_id, WINDOW_SIZE)
    }
    val media = observe { cursor ->
        dao.observeMedia(conversationId, cursor?.created_at, cursor?.logical_message_id, WINDOW_SIZE)
    }
    val info = combine(observedMessages, requests) { rows, request -> rows to request }.flatMapLatest { (rows, request) ->
        if (!request.active) return@flatMapLatest emptyFlow()
        val cursor = request.cursor
        val first = rows.firstOrNull()
        val hasEarlier = first != null && dao.hasEarlier(conversationId, first.createdAt, first.logicalMessageId)
        dao.observeNewerIncoming(conversationId, cursor?.created_at, cursor?.logical_message_id)
            .map { TimelineWindowInfo(hasEarlier, cursor == null, it) }
    }

    val isVisible: Boolean get() = visible.value
    fun setVisible(shown: Boolean) { visible.value = shown }

    suspend fun initialize() = lock.withLock {
        if (!initialized.value) {
            upper.value = initialMessageId?.let { around(it) }
            initialized.value = true
        }
    }

    private suspend fun around(messageId: String): TimelineCursor? {
        val anchor = dao.cursor(conversationId, messageId) ?: return null
        return dao.cursorAfter(conversationId, anchor.created_at, anchor.logical_message_id, WINDOW_SIZE / 2)
    }

    suspend fun openMessage(messageId: String) = lock.withLock {
        upper.value = around(messageId)
        initialized.value = true
    }

    suspend fun loadEarlier() = lock.withLock {
        val rows = currentMessages
        if (rows.isNotEmpty()) {
            val anchor = rows[(OVERLAP_SIZE - 1).coerceAtMost(rows.lastIndex)]
            if (dao.hasEarlier(conversationId, rows.first().createdAt, rows.first().logicalMessageId))
                upper.value = TimelineCursor(anchor.logicalMessageId, anchor.createdAt)
        }
    }

    suspend fun jumpToLatest() = lock.withLock { upper.value = null; initialized.value = true }
}
