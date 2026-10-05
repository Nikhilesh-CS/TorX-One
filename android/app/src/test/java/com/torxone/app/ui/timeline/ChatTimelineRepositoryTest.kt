package com.torxone.app.ui.timeline

import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTimelineRepositoryTest {
    private class Reader : ChatTimelineDao {
        var subscriptions = 0
        val rows = (0..999).map { MessageEntity(it.toString().padStart(4, '0'), "chat", "peer", "TEXT", "Body",
            MessageDirection.INCOMING, "DELIVERED", it.toLong()) }
        override fun observeWindow(conversationId: String, upperTime: Long?, upperId: String?, limit: Int) = flow {
            subscriptions++
            try { emit(rows.filter { upperTime == null || it.createdAt <= upperTime }.takeLast(limit)); awaitCancellation() }
            finally { subscriptions-- }
        }
        override fun observeReferences(conversationId: String, upperTime: Long?, upperId: String?, limit: Int): Flow<List<MessageEntity>> = flowOf(emptyList())
        override fun observeReactions(conversationId: String, upperTime: Long?, upperId: String?, limit: Int): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override fun observeMedia(conversationId: String, upperTime: Long?, upperId: String?, limit: Int): Flow<List<MediaEntity>> = flowOf(emptyList())
        override fun observeThumbnail(mediaId: String): Flow<ByteArray?> = flowOf(null)
        override suspend fun cursor(conversationId: String, messageId: String) = rows.find { it.logicalMessageId == messageId }?.let { TimelineCursor(it.logicalMessageId, it.createdAt) }
        override suspend fun cursorAfter(conversationId: String, time: Long, id: String, offset: Int) = rows.filter { it.createdAt >= time }.getOrNull(offset)?.let { TimelineCursor(it.logicalMessageId, it.createdAt) }
        override suspend fun hasEarlier(conversationId: String, time: Long, id: String) = rows.any { it.createdAt < time }
        override fun observeNewerIncoming(conversationId: String, time: Long?, id: String?): Flow<Int> = flowOf(if (time == null) 0 else rows.count { it.createdAt > time })
        override suspend fun firstUnreadVisible(conversationId: String): String? = rows.first().logicalMessageId
        override suspend fun countUnreadVisible(conversationId: String) = rows.size
    }

    @Test fun hiddenScreenCancelsReaderAndResumeReopensIt() = runTest {
        val reader = Reader(); val timeline = ChatTimelineRepository(reader, "chat")
        timeline.setVisible(false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { timeline.messages.collect() }
        timeline.initialize(); runCurrent()
        assertEquals(0, reader.subscriptions)
        timeline.setVisible(true); runCurrent(); assertEquals(1, reader.subscriptions)
        timeline.setVisible(false); runCurrent(); assertEquals(0, reader.subscriptions)
        timeline.setVisible(true); runCurrent(); assertEquals(1, reader.subscriptions)
    }

    @Test fun pagingKeepsOverlapAndTargetJumpThenReturnsToLatest() = runTest {
        val reader = Reader(); val timeline = ChatTimelineRepository(reader, "chat")
        var visible = emptyList<MessageEntity>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { timeline.messages.collect { visible = it } }
        timeline.initialize(); runCurrent(); assertEquals("0500", visible.first().logicalMessageId)
        timeline.loadEarlier(); runCurrent()
        assertEquals("0100", visible.first().logicalMessageId); assertEquals("0599", visible.last().logicalMessageId)
        assertEquals(100, visible.count { it.createdAt >= 500 })
        timeline.openMessage("0200"); runCurrent(); assertTrue(visible.any { it.logicalMessageId == "0200" })
        timeline.jumpToLatest(); runCurrent(); assertEquals("0999", visible.last().logicalMessageId)
        assertTrue(visible.all { it.status == "DELIVERED" })
    }
}
