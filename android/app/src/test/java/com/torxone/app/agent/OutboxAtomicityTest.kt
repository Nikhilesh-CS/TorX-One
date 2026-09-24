package com.torxone.app.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.data.AppDatabase
import com.torxone.app.transport.TransportRouter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutboxAtomicityTest {
    @Test
    fun failedOutboxInsertRollsBackAllocatedSequence() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val deliveryScope = CoroutineScope(Job().apply { cancel() })
        try {
            val manager = ConnectionManager(db.connectionQueueDao())
            val connection = manager.getOrCreateConnection("peer", "local")
            val outbox: DeliveryQueueDao = mock()
            whenever(outbox.insert(any())).thenThrow(IllegalStateException("disk write failed"))
            val agent = TorXAgent(db, mock<TransportRouter>(), outbox, manager, deliveryScope)

            try {
                agent.queueForDelivery("peer", "message", EnvelopeType.MSG, "ciphertext")
                fail("Insertion must fail")
            } catch (expected: IllegalStateException) {
                assertEquals("disk write failed", expected.message)
            }
            assertEquals(0L, db.connectionQueueDao().getById(connection.connectionId)!!.lastSendSeq)
        } finally {
            db.close()
        }
    }
}
