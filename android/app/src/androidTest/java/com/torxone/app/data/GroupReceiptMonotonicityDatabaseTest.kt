package com.torxone.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.GroupDeliveryStatus
import com.torxone.app.data.entity.GroupMessageDeliveryEntity
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.data.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupReceiptMonotonicityDatabaseTest {
    @Test fun lateGroupAckPreservesReadAndExistingTimestampsAndFillsMissingDeliveryTime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, TorXDatabase::class.java).build()
        try {
            db.conversationDao().upsert(ConversationEntity("chat", title = "Fixture"))
            db.messageDao().insertIfAbsent(MessageEntity("message", "chat", "local", "TEXT", "fixture",
                MessageDirection.OUTGOING, "READ", readAt = 200))
            val dao = db.groupMessageDeliveryDao()
            dao.upsert(GroupMessageDeliveryEntity("already-delivered", "message", "alice", "alice-rel",
                status = GroupDeliveryStatus.READ.name, deliveredAt = 100, readAt = 200, updatedAt = 250))
            dao.upsert(GroupMessageDeliveryEntity("read-first", "message", "bob", "bob-rel",
                status = GroupDeliveryStatus.READ.name, readAt = 200, updatedAt = 250))
            dao.markDelivered("message", "alice", deliveredAt = 150, updatedAt = 240)
            dao.markDelivered("message", "bob", deliveredAt = 100, updatedAt = 300)
            dao.markDelivered("message", "bob", deliveredAt = 50, updatedAt = 275)
            val deliveries = dao.getDeliveriesForMessage("message").associateBy { it.recipientIdentityId }
            val alice = requireNotNull(deliveries["alice"])
            assertEquals("READ", alice.status)
            assertEquals(100L, alice.deliveredAt)
            assertEquals(200L, alice.readAt)
            assertEquals(250L, alice.updatedAt)
            val bob = requireNotNull(deliveries["bob"])
            assertEquals("READ", bob.status)
            assertEquals(100L, bob.deliveredAt)
            assertEquals(200L, bob.readAt)
            assertEquals(300L, bob.updatedAt)
            assertEquals("READ", db.messageDao().getById("message")?.status)
        } finally { db.close() }
    }
}
