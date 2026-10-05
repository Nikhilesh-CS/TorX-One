package com.torxone.app.ui

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.LocalMessageStateEntity
import com.torxone.app.ui.timeline.ChatTimelineRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatTimelineDaoDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun id(index: Int) = "message_" + index.toString().padStart(6, '0')

    private suspend fun seed(db: TorXDatabase, count: Int) {
        db.conversationDao().upsert(ConversationEntity("fixture", title = "Fixture"))
        val sql = db.openHelper.writableDatabase
        val insert = sql.compileStatement("INSERT INTO messages(logical_message_id,conversation_id,sender_id,type,body,direction,status,created_at,edit_version) VALUES(?,'fixture','peer','TEXT','Synthetic message','INCOMING','DELIVERED',?,0)")
        sql.beginTransaction()
        try {
            for (index in 0 until count) {
                insert.bindString(1, id(index))
                // Equal timestamps deliberately exercise the logical-ID tie breaker.
                insert.bindLong(2, index.toLong() / 2)
                insert.executeInsert()
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction(); insert.close() }
    }

    @Test fun hundredThousandRecordsStayBoundedAndOldAnchorRemainsReachable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TorXDatabase::class.java).build()
        try {
            seed(db, 100_000)
            val dao = db.chatTimelineDao()
            val latest = withTimeout(30_000) { dao.observeWindow("fixture", null, null, ChatTimelineRepository.WINDOW_SIZE).first() }
            assertEquals(500, latest.size)
            assertEquals(id(99_500), latest.first().logicalMessageId)
            assertEquals(id(99_999), latest.last().logicalMessageId)
            val anchor = requireNotNull(dao.cursor("fixture", id(50_000)))
            val upper = requireNotNull(dao.cursorAfter("fixture", anchor.created_at, anchor.logical_message_id, 250))
            val middle = dao.observeWindow("fixture", upper.created_at, upper.logical_message_id, 500).first()
            assertEquals(500, middle.size)
            assertTrue(middle.any { it.logicalMessageId == anchor.logical_message_id })
            assertTrue(dao.hasEarlier("fixture", middle.first().createdAt, middle.first().logicalMessageId))
            assertEquals(49_749, dao.observeNewerIncoming("fixture", upper.created_at, upper.logical_message_id).first())
            // Read-only projections must not acknowledge the conversation.
            assertEquals(100_000, dao.unreadEntry("fixture").count)
            assertEquals("DELIVERED", db.messageDao().getById(id(50_000))?.status)
        } finally { db.close() }
    }

    @Test fun hiddenMessagesDoNotConsumeWindowSlotsOrCreateUnreadAnchors() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TorXDatabase::class.java).build()
        try {
            seed(db, 1_000)
            db.localMessageStateDao().upsert(LocalMessageStateEntity(id(0), "fixture"))
            db.localMessageStateDao().upsert(LocalMessageStateEntity(id(999), "fixture"))
            val dao = db.chatTimelineDao()
            val entry = dao.unreadEntry("fixture")
            assertEquals(id(1), entry.firstMessageId)
            assertEquals(998, entry.count)
            val rows = dao.observeWindow("fixture", null, null, 500).first()
            assertEquals(500, rows.size)
            assertFalse(rows.any { it.logicalMessageId == id(999) })
            assertEquals(id(998), rows.last().logicalMessageId)
        } finally { db.close() }
    }
}
