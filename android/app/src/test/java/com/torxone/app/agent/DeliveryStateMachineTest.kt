package com.torxone.app.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Phase 2 Unit Tests: Truthful Delivery State Machine & Receipt Pipeline
 *
 * Verifies:
 * 1. QUEUED -> TRANSMITTING -> RELAY_ACCEPTED -> DEVICE_RECEIVED -> DELIVERED -> READ
 * 2. Relay acceptance is strictly separate from recipient delivery (RELAY_ACCEPTED != DELIVERED)
 * 3. Monotonic delivery state guards prevent backward state regressions (e.g. delayed retry cannot revert DELIVERED)
 * 4. Explicit delivery receipts: Direct transport transmission (ACCEPTED) requires an authenticated ACK to reach DELIVERED.
 */
@RunWith(RobolectricTestRunner::class)
class DeliveryStateMachineTest {

    private lateinit var dao: FakeDeliveryQueueDao

    @Before
    fun setup() {
        dao = FakeDeliveryQueueDao()
    }

    private fun createTestEnvelope(
        envelopeId: String = UUID.randomUUID().toString(),
        messageId: String = "msg_123",
        recipientKey: String = "bob_key",
        state: String = "QUEUED"
    ): DeliveryQueueEntity {
        val now = System.currentTimeMillis()
        return DeliveryQueueEntity(
            envelopeId = envelopeId,
            connectionId = "conn_1",
            messageId = messageId,
            queueId = "queue_1",
            sequenceNumber = 1L,
            previousMessageHash = null,
            envelopeHash = "hash_123",
            messageType = "MESSAGE",
            encryptedPayload = "ciphertext_bytes",
            state = state,
            createdAt = now,
            expiresAt = now + 86400000L,
            recipientKey = recipientKey
        )
    }

    @Test
    fun testTruthfulDeliveryLifecycle_RelayAcceptedNotDelivered() = runBlocking {
        val envelope = createTestEnvelope(messageId = "msg_truth_1")
        dao.insert(envelope)

        // 1. Initial state: QUEUED
        assertEquals("QUEUED", dao.getByEnvelopeId(envelope.envelopeId)?.state)

        // 2. Transmitting
        dao.markTransmitting(envelope.envelopeId)
        assertEquals("TRANSMITTING", dao.getByEnvelopeId(envelope.envelopeId)?.state)

        // 3. Relay accepts ciphertext
        dao.markRelayAccepted(envelope.envelopeId, "OFFLINE_RELAY")
        val relayState = dao.getByEnvelopeId(envelope.envelopeId)!!
        assertEquals("RELAY_ACCEPTED", relayState.state)
        assertEquals("OFFLINE_RELAY", relayState.transportUsed)
        assertNull("Relay acceptance must NOT set deliveredAt timestamp", relayState.deliveredAt)

        // 4. Recipient device receives envelope
        dao.markDeviceReceived("msg_truth_1")
        val devReceivedState = dao.getByEnvelopeId(envelope.envelopeId)!!
        assertEquals("DEVICE_RECEIVED", devReceivedState.state)
        assertNull(devReceivedState.deliveredAt)

        // 5. Recipient decrypts, commits, and returns authenticated ACK
        val nowDelivered = System.currentTimeMillis()
        dao.markDelivered("msg_truth_1", nowDelivered)
        val deliveredState = dao.getByEnvelopeId(envelope.envelopeId)!!
        assertEquals("DELIVERED", deliveredState.state)
        assertEquals(nowDelivered, deliveredState.deliveredAt)

        // 6. Recipient opens chat screen and returns authenticated READ receipt
        val nowRead = nowDelivered + 1000L
        dao.markRead("msg_truth_1", nowRead)
        val readState = dao.getByEnvelopeId(envelope.envelopeId)!!
        assertEquals("READ", readState.state)
        assertEquals(nowRead, readState.readAt)
    }

    @Test
    fun testMonotonicDeliveryStateGuard_PreventsRegression() = runBlocking {
        val envelope = createTestEnvelope(messageId = "msg_guard_1")
        dao.insert(envelope)

        // Advance to DELIVERED
        dao.markTransmitting(envelope.envelopeId)
        dao.markAccepted(envelope.envelopeId, "NEARBY_DIRECT")
        val ackTime = System.currentTimeMillis()
        dao.markDelivered("msg_guard_1", ackTime)

        val delivered = dao.getByEnvelopeId(envelope.envelopeId)!!
        assertEquals("DELIVERED", delivered.state)

        // Late transport response or retry attempt must NOT revert DELIVERED
        dao.markTransmitting(envelope.envelopeId)
        assertEquals("DELIVERED", dao.getByEnvelopeId(envelope.envelopeId)?.state)

        dao.markRelayAccepted(envelope.envelopeId, "OFFLINE_RELAY")
        assertEquals("DELIVERED", dao.getByEnvelopeId(envelope.envelopeId)?.state)

        dao.markAccepted(envelope.envelopeId, "TOR")
        assertEquals("DELIVERED", dao.getByEnvelopeId(envelope.envelopeId)?.state)

        dao.scheduleRetry(envelope.envelopeId, System.currentTimeMillis() + 5000L)
        assertEquals("DELIVERED", dao.getByEnvelopeId(envelope.envelopeId)?.state)

        // Advance to READ
        val readTime = ackTime + 2000L
        dao.markRead("msg_guard_1", readTime)
        assertEquals("READ", dao.getByEnvelopeId(envelope.envelopeId)?.state)

        // Out-of-order duplicate ACK must NOT revert READ back to DELIVERED
        dao.markDelivered("msg_guard_1", ackTime + 3000L)
        assertEquals("READ", dao.getByEnvelopeId(envelope.envelopeId)?.state)
    }

    @Test
    fun testDirectTransport_RequiresExplicitAckForDelivered() = runBlocking {
        val envelope = createTestEnvelope(messageId = "msg_direct_1")
        dao.insert(envelope)

        // Physical transmission over Direct Wi-Fi / Nearby
        dao.markTransmitting(envelope.envelopeId)
        dao.markAccepted(envelope.envelopeId, "WIFI_DIRECT")

        val inTransit = dao.getByEnvelopeId(envelope.envelopeId)!!
        assertEquals("ACCEPTED", inTransit.state)
        assertNull("Direct socket write does not imply recipient decrypted or persisted", inTransit.deliveredAt)

        // Only when authenticated ACK arrives does it transition to DELIVERED
        dao.markDelivered("msg_direct_1")
        val finalDelivered = dao.getByEnvelopeId(envelope.envelopeId)!!
        assertEquals("DELIVERED", finalDelivered.state)
        assertNotNull(finalDelivered.deliveredAt)
    }
}

/**
 * In-memory test double for DeliveryQueueDao
 */
class FakeDeliveryQueueDao : DeliveryQueueDao {
    private val storage = mutableMapOf<String, DeliveryQueueEntity>()

    override suspend fun insert(entity: DeliveryQueueEntity) {
        storage[entity.envelopeId] = entity
    }

    override suspend fun insertAll(entities: List<DeliveryQueueEntity>) {
        entities.forEach { storage[it.envelopeId] = it }
    }

    override suspend fun update(entity: DeliveryQueueEntity) {
        storage[entity.envelopeId] = entity
    }

    override suspend fun getPendingDeliveries(now: Long, limit: Int): List<DeliveryQueueEntity> {
        return storage.values
            .filter { it.state == "QUEUED" && (it.nextRetryAt == null || it.nextRetryAt!! <= now) && it.expiresAt > now }
            .sortedBy { it.sequenceNumber }
            .take(limit)
    }

    override suspend fun getTransmitting(): List<DeliveryQueueEntity> {
        return storage.values.filter { it.state == "TRANSMITTING" }
    }

    override suspend fun getByEnvelopeId(envelopeId: String): DeliveryQueueEntity? {
        return storage[envelopeId]
    }

    override suspend fun getByMessageId(messageId: String): List<DeliveryQueueEntity> {
        return storage.values.filter { it.messageId == messageId }
    }

    override suspend fun updateState(envelopeId: String, state: String, now: Long) {
        storage[envelopeId]?.let {
            storage[envelopeId] = it.copy(state = state, lastAttemptAt = now)
        }
    }

    override suspend fun markTransmitting(envelopeId: String, now: Long) {
        storage[envelopeId]?.let {
            if (it.state !in listOf("DELIVERED", "READ", "FAILED")) {
                storage[envelopeId] = it.copy(state = "TRANSMITTING", lastAttemptAt = now)
            }
        }
    }

    override suspend fun scheduleRetry(envelopeId: String, nextRetryAt: Long, now: Long) {
        storage[envelopeId]?.let {
            if (it.state !in listOf("DELIVERED", "READ", "FAILED")) {
                storage[envelopeId] = it.copy(
                    state = "QUEUED",
                    retryCount = it.retryCount + 1,
                    nextRetryAt = nextRetryAt,
                    lastAttemptAt = now
                )
            }
        }
    }

    override suspend fun markRelayAccepted(envelopeId: String, transport: String, now: Long) {
        storage[envelopeId]?.let {
            if (it.state !in listOf("DELIVERED", "READ")) {
                storage[envelopeId] = it.copy(
                    state = "RELAY_ACCEPTED",
                    transportUsed = transport,
                    lastAttemptAt = now
                )
            }
        }
    }

    override suspend fun markAccepted(envelopeId: String, transport: String, now: Long) {
        storage[envelopeId]?.let {
            if (it.state !in listOf("DELIVERED", "READ")) {
                storage[envelopeId] = it.copy(
                    state = "ACCEPTED",
                    transportUsed = transport,
                    lastAttemptAt = now
                )
            }
        }
    }

    override suspend fun markDeviceReceived(messageId: String, now: Long) {
        storage.values.filter { it.messageId == messageId }.forEach {
            if (it.state !in listOf("DELIVERED", "READ")) {
                storage[it.envelopeId] = it.copy(state = "DEVICE_RECEIVED", lastAttemptAt = now)
            }
        }
    }

    override suspend fun markDelivered(messageId: String, now: Long) {
        storage.values.filter { it.messageId == messageId }.forEach {
            if (it.state != "READ") {
                storage[it.envelopeId] = it.copy(state = "DELIVERED", deliveredAt = now)
            }
        }
    }

    override suspend fun markRead(messageId: String, now: Long) {
        storage.values.filter { it.messageId == messageId }.forEach {
            storage[it.envelopeId] = it.copy(state = "READ", readAt = now)
        }
    }

    override suspend fun markFailed(envelopeId: String) {
        storage[envelopeId]?.let {
            storage[envelopeId] = it.copy(state = "FAILED")
        }
    }

    override suspend fun pruneExpired(now: Long) {
        storage.entries.removeIf { it.value.expiresAt < now }
    }

    override suspend fun pruneCompleted(cutoff: Long) {
        storage.entries.removeIf {
            it.value.state in listOf("DELIVERED", "READ", "FAILED", "EXPIRED") &&
                    (it.value.lastAttemptAt ?: 0L) < cutoff
        }
    }

    override suspend fun delete(envelopeId: String) {
        storage.remove(envelopeId)
    }

    override fun observePendingCount(): Flow<Int> {
        return flowOf(storage.values.count { it.state == "QUEUED" })
    }

    override fun observePendingForPeer(recipientKey: String): Flow<List<DeliveryQueueEntity>> {
        return flowOf(
            storage.values
                .filter { it.recipientKey == recipientKey && it.state !in listOf("DELIVERED", "READ", "FAILED", "EXPIRED") }
                .sortedBy { it.sequenceNumber }
        )
    }
}
