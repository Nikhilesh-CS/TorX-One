package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.dao.OutboxDao
import com.torxone.app.protocol.DeliveryAck
import com.torxone.app.protocol.SecureEnvelope

class DeliveryReceiptHandler(
    private val messageDao: MessageDao,
    private val outboxDao: OutboxDao,
    private val agent: TorXAgent
) {
    companion object {
        private const val TAG = "DeliveryReceiptHandler"
    }

    suspend fun handleDeliveryAck(envelope: SecureEnvelope) {
        val ack = DeliveryAck.fromByteArray(envelope.payload)
        Log.i(TAG, "[ACK] Received ACK for message=${ack.originalMessageId.take(8)}")

        // 1. Mark message as DELIVERED in Room
        messageDao.markDelivered(ack.originalMessageId, DeliveryStatus.DELIVERED.name, ack.receivedAt)

        // 2. Remove outbox record
        outboxDao.removeByMessageId(ack.originalMessageId)

        // 3. Notify agent
        agent.markDelivered(ack.originalMessageId)
    }

    suspend fun handleReadReceipt(envelope: SecureEnvelope) {
        val messageId = envelope.logicalMessageId
        Log.i(TAG, "[READ] Received READ for message=${messageId.take(8)}")
        messageDao.markRead(messageId, DeliveryStatus.READ.name, envelope.timestamp)
    }
}
