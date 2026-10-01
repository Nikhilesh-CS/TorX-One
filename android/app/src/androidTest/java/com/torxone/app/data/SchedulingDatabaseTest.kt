package com.torxone.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.scheduling.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchedulingDatabaseTest {
    @Test fun scheduleSurvivesRestartGenerationRejectsOldWorkAndConversationDeleteCascades() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "scheduled-messages-room-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, TorXDatabase::class.java, name).build()
        try {
            var db = open()
            try {
                db.conversationDao().upsert(ConversationEntity("chat", title = "Alice"))
                db.scheduledMessageDao().insert(ScheduledMessageEntity("schedule", "chat", "me", "send later", "reply", 1000,
                    createdAt = 1, updatedAt = 1))
            } finally { db.close() }
            db = open()
            try {
                val dao = db.scheduledMessageDao()
                assertEquals("send later", dao.get("schedule")?.draftPayload)
                assertEquals(1, dao.edit("schedule", 1, "new text", null, 3000, 2000))
                assertEquals(0, dao.claim("schedule", 1, 4000, 5000))
                assertEquals(1, dao.claim("schedule", 2, 4000, 5000))
                assertEquals(0, dao.edit("schedule", 2, "too late", null, 6000, 4000))
                assertEquals(0, dao.cancel("schedule", 2, 4000))
                assertEquals(0, dao.claim("schedule", 2, 4500, 6000))
                assertEquals(1, dao.claim("schedule", 2, 5000, 6000))
                assertEquals(1, dao.complete("schedule", 2, 5001))
                assertEquals("", dao.get("schedule")?.draftPayload)
                assertEquals("SENT", dao.get("schedule")?.state)
                db.conversationDao().deleteById("chat")
                assertNull(dao.get("schedule"))
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
