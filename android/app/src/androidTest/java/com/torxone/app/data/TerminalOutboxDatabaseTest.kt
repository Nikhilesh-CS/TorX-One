package com.torxone.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.entity.OutboxEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalOutboxDatabaseTest {
    @Test fun restoredTerminalSequenceRemainsPendingCountedAndAnExactLateAckTargetAfterReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "terminal-outbox-sequence-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, TorXDatabase::class.java, name).build()
        try {
            var db = open()
            try {
                for (status in listOf("FAILED", "EXPIRED")) {
                    val chat = "chat-$status"
                    db.conversationDao().upsert(ConversationEntity(chat, title = "Fixture"))
                    for (message in listOf("head-$status", "next-$status")) {
                        db.messageDao().insertIfAbsent(MessageEntity(message, chat, "local", "TEXT", "fixture",
                            MessageDirection.OUTGOING, if (message.startsWith("head")) status else "QUEUED"))
                    }
                    db.outboxDao().insert(OutboxEntity("head-$status", "head-$status", chat, "connection", "queue",
                        byteArrayOf(1, 2), ByteArray(32), status, attemptCount = 13,
                        applicationSequence = 1, relationshipId = chat))
                    db.outboxDao().insert(OutboxEntity("next-$status", "next-$status", chat, "connection", "rotated-queue",
                        byteArrayOf(3), ByteArray(32), "QUEUED", applicationSequence = 2, relationshipId = chat))
                    db.outboxDao().insert(OutboxEntity("control-$status", "control-$status", chat, "connection", "queue",
                        byteArrayOf(4), ByteArray(32), status, applicationSequence = 3, relationshipId = chat))
                    db.outboxDao().insert(OutboxEntity("unsequenced-$status", "unsequenced-$status", chat, "connection", "queue",
                        byteArrayOf(5), ByteArray(32), status, relationshipId = chat))
                }
            } finally { db.close() }
            db = open()
            try {
                for (status in listOf("FAILED", "EXPIRED")) {
                    val chat = "chat-$status"
                    assertEquals(listOf("head-$status", "next-$status", "control-$status"),
                        db.outboxDao().getPending().filter { it.conversationId == chat }.map { it.deliveryId })
                    val head = requireNotNull(db.outboxDao().getByDeliveryId("head-$status"))
                    assertEquals(status, head.status)
                    assertEquals(13, head.attemptCount)
                    assertArrayEquals(byteArrayOf(1, 2), head.ciphertext)
                    assertNull(db.outboxDao().getByDeliveryId("unsequenced-$status"))
                    assertEquals(2, db.featureDao().pendingMessageCount(chat))
                    assertEquals(3, db.featureDao().pendingDeliveryCount(chat))
                    assertEquals(1, db.featureDao().pendingControlCount(chat))
                    // The normal receipt transaction removes exactly this row;
                    // its successor is now the first visible application sequence.
                    db.outboxDao().removeByDeliveryId("head-$status")
                    assertEquals("next-$status", db.outboxDao().getPending()
                        .first { it.conversationId == chat }.deliveryId)
                    assertEquals(1, db.featureDao().pendingMessageCount(chat))
                }
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
