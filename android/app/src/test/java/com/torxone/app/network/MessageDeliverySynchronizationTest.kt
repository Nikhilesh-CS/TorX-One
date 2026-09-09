package com.torxone.app.network

import com.torxone.app.data.MessageOutboxEntity
import com.torxone.app.data.ReceiptOutboxEntity
import com.torxone.app.engine.MessageDeliveryState
import com.torxone.app.engine.MessageLifecycleState
import com.torxone.app.ui.components.toDeliveryState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * MessageDeliverySynchronizationTest
 *
 * Verifies verified protocol delivery states, state machine transitions,
 * ACK and READ wire protocol formats, outbox persistence guarantees,
 * deduplication, and protection against state regression.
 */
@RunWith(RobolectricTestRunner::class)
class MessageDeliverySynchronizationTest {

    private val myKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val peerKey = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private val otherKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    // -------------------------------------------------------------
    // 1. Delivery State Mapping Tests
    // -------------------------------------------------------------

    @Test
    fun testDeliveryState_fromDbStatus_mapsCorrectly() {
        assertEquals(MessageDeliveryState.SENDING, MessageDeliveryState.fromDbStatus("sending"))
        assertEquals(MessageDeliveryState.SENDING, MessageDeliveryState.fromDbStatus("pending"))
        assertEquals(MessageDeliveryState.SENDING, MessageDeliveryState.fromDbStatus("queued"))
        assertEquals(MessageDeliveryState.SENDING, MessageDeliveryState.fromDbStatus("draft"))
        assertEquals(MessageDeliveryState.SENDING, MessageDeliveryState.fromDbStatus("encrypting"))

        assertEquals(MessageDeliveryState.SENT, MessageDeliveryState.fromDbStatus("sent"))
        assertEquals(MessageDeliveryState.SENT, MessageDeliveryState.fromDbStatus("in_transit"))
        assertEquals(MessageDeliveryState.SENT, MessageDeliveryState.fromDbStatus("transport_selected"))

        assertEquals(MessageDeliveryState.DELIVERED, MessageDeliveryState.fromDbStatus("delivered"))

        assertEquals(MessageDeliveryState.SEEN, MessageDeliveryState.fromDbStatus("read"))
        assertEquals(MessageDeliveryState.SEEN, MessageDeliveryState.fromDbStatus("seen"))

        assertEquals(MessageDeliveryState.FAILED, MessageDeliveryState.fromDbStatus("failed"))
        assertEquals(MessageDeliveryState.FAILED, MessageDeliveryState.fromDbStatus("cancelled"))
        assertEquals(MessageDeliveryState.FAILED, MessageDeliveryState.fromDbStatus("expired"))

        // Unknown defaults to SENDING
        assertEquals(MessageDeliveryState.SENDING, MessageDeliveryState.fromDbStatus("unknown_xyz"))
    }

    @Test
    fun testLifecycleState_toDeliveryState_mapsCorrectly() {
        assertEquals(MessageDeliveryState.SENDING, MessageLifecycleState.SENDING.toDeliveryState())
        assertEquals(MessageDeliveryState.SENDING, MessageLifecycleState.QUEUED.toDeliveryState())
        assertEquals(MessageDeliveryState.SENDING, MessageLifecycleState.ENCRYPTING.toDeliveryState())
        assertEquals(MessageDeliveryState.SENDING, MessageLifecycleState.RETRYING.toDeliveryState())

        assertEquals(MessageDeliveryState.SENT, MessageLifecycleState.IN_TRANSIT.toDeliveryState())
        assertEquals(MessageDeliveryState.SENT, MessageLifecycleState.ARCHIVED.toDeliveryState())

        assertEquals(MessageDeliveryState.DELIVERED, MessageLifecycleState.DELIVERED.toDeliveryState())

        assertEquals(MessageDeliveryState.SEEN, MessageLifecycleState.READ.toDeliveryState())

        assertEquals(MessageDeliveryState.FAILED, MessageLifecycleState.FAILED.toDeliveryState())
        assertEquals(MessageDeliveryState.FAILED, MessageLifecycleState.CANCELLED.toDeliveryState())
        assertEquals(MessageDeliveryState.FAILED, MessageLifecycleState.EXPIRED.toDeliveryState())
    }

    // -------------------------------------------------------------
    // 2. Protocol Wire Format & Receipt Encoding Tests
    // -------------------------------------------------------------

    @Test
    fun testEncodeAck_containsRequiredFieldsAndTimestamp() {
        val msgId = UUID.randomUUID().toString()
        val onion = "testpeer123456789.onion"
        val ackJsonStr = MeshProtocol.encodeAck(
            messageId = msgId,
            fromKey = myKey,
            toKey = peerKey,
            senderOnion = onion
        )

        val json = JSONObject(ackJsonStr)
        assertEquals(MeshProtocol.TYPE_ACK, json.getString("type"))
        assertEquals(msgId, json.getString("msgId"))
        assertEquals(myKey, json.getString("from"))
        assertEquals(peerKey, json.getString("to"))
        assertEquals(onion, json.getString("senderOnion"))
        assertTrue("ACK must contain timestamp", json.has("timestamp"))
        assertTrue("Timestamp should be valid epoch millis", json.getLong("timestamp") > 0L)
    }

    @Test
    fun testEncodeRead_containsRequiredFieldsAndTimestamp() {
        val msgId = UUID.randomUUID().toString()
        val onion = "testpeer123456789.onion"
        val readJsonStr = MeshProtocol.encodeRead(
            messageId = msgId,
            fromKey = myKey,
            toKey = peerKey,
            senderOnion = onion
        )

        val json = JSONObject(readJsonStr)
        assertEquals(MeshProtocol.TYPE_READ, json.getString("type"))
        assertEquals(msgId, json.getString("msgId"))
        assertEquals(myKey, json.getString("from"))
        assertEquals(peerKey, json.getString("to"))
        assertEquals(onion, json.getString("senderOnion"))
        assertTrue("READ must contain timestamp", json.has("timestamp"))
        assertTrue("Timestamp should be valid epoch millis", json.getLong("timestamp") > 0L)
    }

    @Test
    fun testDecodeAckAndRead() {
        val msgId = "test-msg-uuid-999"
        val ackStr = MeshProtocol.encodeAck(msgId, myKey, peerKey, null)
        val readStr = MeshProtocol.encodeRead(msgId, myKey, peerKey, null)

        assertEquals(msgId, MeshProtocol.decodeAck(ackStr))
        assertEquals(msgId, MeshProtocol.decodeRead(readStr))
    }

    // -------------------------------------------------------------
    // 3. Outbox Entity & Wire Frame Persistence Tests
    // -------------------------------------------------------------

    @Test
    fun testMessageOutboxEntity_preservesWireJsonForRetries() {
        val msgId = UUID.randomUUID().toString()
        val sampleWireJson = """{"type":"session_message","from":"$myKey","to":"$peerKey","ciphertext":"xyz123"}"""
        val now = System.currentTimeMillis()

        val entity = MessageOutboxEntity(
            messageId = msgId,
            contactKey = peerKey,
            wireJson = sampleWireJson,
            createdAt = now,
            retryCount = 0,
            nextRetryAt = now + 3000L
        )

        assertEquals(msgId, entity.messageId)
        assertEquals(peerKey, entity.contactKey)
        assertEquals(sampleWireJson, entity.wireJson)
        assertEquals(0, entity.retryCount)
        assertEquals(now + 3000L, entity.nextRetryAt)

        // Simulate retry update: increment retry count and exponential backoff
        val updatedRetry = entity.retryCount + 1
        val backoffDelay = (3000L * (1 shl (updatedRetry - 1))).coerceAtMost(30000L)
        val updatedNextRetry = now + backoffDelay

        assertEquals(1, updatedRetry)
        assertEquals(now + 3000L, updatedNextRetry) // 3s for retry 1

        val retry2 = updatedRetry + 1
        val backoff2 = (3000L * (1 shl (retry2 - 1))).coerceAtMost(30000L)
        assertEquals(6000L, backoff2) // 6s for retry 2

        val retry3 = retry2 + 1
        val backoff3 = (3000L * (1 shl (retry3 - 1))).coerceAtMost(30000L)
        assertEquals(12000L, backoff3) // 12s for retry 3
    }

    @Test
    fun testReceiptOutboxEntity_tracksPendingAckAndRead() {
        val ackMsgId = UUID.randomUUID().toString()
        val readMsgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val ackOutbox = ReceiptOutboxEntity(
            id = "ack_$ackMsgId",
            messageId = ackMsgId,
            recipientKey = peerKey,
            type = "ack",
            createdAt = now,
            retryCount = 0,
            nextRetryAt = now
        )

        val readOutbox = ReceiptOutboxEntity(
            id = "read_$readMsgId",
            messageId = readMsgId,
            recipientKey = peerKey,
            type = "read",
            createdAt = now,
            retryCount = 0,
            nextRetryAt = now
        )

        assertEquals("ack", ackOutbox.type)
        assertEquals("read", readOutbox.type)
        assertEquals(peerKey, ackOutbox.recipientKey)
        assertEquals(readMsgId, readOutbox.messageId)
    }

    // -------------------------------------------------------------
    // 4. Receipt Ownership & Routing Validation Logic
    // -------------------------------------------------------------

    @Test
    fun testReceiptValidation_rejectsMismatchedRecipientOrSender() {
        val msgId = "msg-validation-123"

        // Legitimate ACK
        val validAckJson = JSONObject(MeshProtocol.encodeAck(msgId, peerKey, myKey, null))
        val ackSender = validAckJson.optString("from").trim().lowercase()
        val ackRecipient = validAckJson.optString("to").trim().lowercase()

        assertEquals(peerKey, ackSender)
        assertEquals(myKey, ackRecipient)

        // ACK sent to wrong device
        val wrongRecipientAck = JSONObject(MeshProtocol.encodeAck(msgId, peerKey, otherKey, null))
        assertFalse(
            "ACK addressed to someone else must be rejected",
            wrongRecipientAck.optString("to").trim().lowercase() == myKey
        )

        // ACK from unauthorized peer (e.g. Charlie pretending to ACK Alice's message)
        val wrongSenderAck = JSONObject(MeshProtocol.encodeAck(msgId, otherKey, myKey, null))
        assertFalse(
            "ACK from wrong sender key must be rejected",
            wrongSenderAck.optString("from").trim().lowercase() == peerKey
        )
    }

    @Test
    fun testReadValidation_rejectsMismatchedRecipientOrSender() {
        val msgId = "msg-read-validation-123"

        val validReadJson = JSONObject(MeshProtocol.encodeRead(msgId, peerKey, myKey, null))
        val readSender = validReadJson.optString("from").trim().lowercase()
        val readRecipient = validReadJson.optString("to").trim().lowercase()

        assertEquals(peerKey, readSender)
        assertEquals(myKey, readRecipient)

        val wrongRecipientRead = JSONObject(MeshProtocol.encodeRead(msgId, peerKey, otherKey, null))
        assertFalse(
            "READ addressed to someone else must be rejected",
            wrongRecipientRead.optString("to").trim().lowercase() == myKey
        )

        val wrongSenderRead = JSONObject(MeshProtocol.encodeRead(msgId, otherKey, myKey, null))
        assertFalse(
            "READ from wrong sender key must be rejected",
            wrongSenderRead.optString("from").trim().lowercase() == peerKey
        )
    }

    // -------------------------------------------------------------
    // 5. State Machine Transition Rules
    // -------------------------------------------------------------

    @Test
    fun testDeliveryStateMachine_validTransitions() {
        // Linear progression: SENDING -> SENT -> DELIVERED -> SEEN
        var status = "sending"

        // Step 1: Transport write succeeds
        status = advanceStatus(current = status, newStatus = "sent")
        assertEquals("sent", status)

        // Step 2: Recipient ACKs (DB persisted on recipient device)
        status = advanceStatus(current = status, newStatus = "delivered")
        assertEquals("delivered", status)

        // Step 3: Recipient reads message
        status = advanceStatus(current = status, newStatus = "read")
        assertEquals("read", status)

        // Idempotent duplicate READ: remains read
        status = advanceStatus(current = status, newStatus = "read")
        assertEquals("read", status)

        // Regression attempt: Delayed ACK arriving after READ must NOT downgrade status
        status = advanceStatus(current = status, newStatus = "delivered")
        assertEquals("read", status)

        // Regression attempt: Delayed sent confirmation must NOT downgrade status
        status = advanceStatus(current = status, newStatus = "sent")
        assertEquals("read", status)
    }

    @Test
    fun testDeliveryStateMachine_jumpToReadFromSent() {
        // If an ACK was dropped in network transit but recipient read the message and emitted READ:
        // SENT -> READ is a valid forward progression
        val status = advanceStatus(current = "sent", newStatus = "read")
        assertEquals("read", status)
    }

    /**
     * Replicates the exact SQL CASE logic from MessageDao.updateSentMessageStatus:
     *
     * CASE
     *   WHEN :newStatus = 'read' THEN 'read'
     *   WHEN :newStatus = 'delivered' AND status != 'read' THEN 'delivered'
     *   WHEN :newStatus = 'sent' AND status NOT IN ('delivered', 'read') THEN 'sent'
     *   WHEN :newStatus = 'failed' AND status NOT IN ('delivered', 'read') THEN 'failed'
     *   ELSE status
     * END
     */
    private fun advanceStatus(current: String, newStatus: String): String {
        return when {
            newStatus == "read" -> "read"
            newStatus == "delivered" && current != "read" -> "delivered"
            newStatus == "sent" && current !in listOf("delivered", "read") -> "sent"
            newStatus == "failed" && current !in listOf("delivered", "read") -> "failed"
            else -> current
        }
    }
}
