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
    private val agent: TorXAgent,
    private val groupService: com.torxone.app.groups.GroupService? = null
) {
    companion object {
        private const val TAG = "DeliveryReceiptHandler"
    }

    suspend fun handleDeliveryAck(envelope: SecureEnvelope) {
        val ack = DeliveryAck.fromByteArray(envelope.payload)
        val envId = ack.originalEnvelopeId
        val envPrefix = envId?.take(8) ?: "none"
        Log.i(TAG, "[ACK] Received ACK for message=${ack.originalMessageId.take(8)} env=$envPrefix")

        val isGroup = groupService?.isGroupMessage(ack.originalMessageId) ?: false
        if (!isGroup) {
            // 1. Mark 1:1 message as DELIVERED in Room
            messageDao.markDelivered(ack.originalMessageId, DeliveryStatus.DELIVERED.name, ack.receivedAt)

            // 2. Remove exact outbox item
            if (!envId.isNullOrBlank()) {
                outboxDao.removeByDeliveryId(envId)
            }
            outboxDao.removeByMessageId(ack.originalMessageId)

            // 3. Notify agent
            agent.markDelivered(ack.originalMessageId)
        } else {
            // Phase 13: Group message exact delivery ACK semantics
            // Remove ONLY this exact recipient's delivery from outbox
            if (!envId.isNullOrBlank()) {
                outboxDao.removeByDeliveryId(envId)
                agent.markDeliveryAcknowledged(envId, null)
            }

            // Notify GroupService to record per-recipient delivery status.
            // Aggregate MessageEntity is marked DELIVERED only when all active members have acknowledged.
            groupService?.handleDeliveryAck(ack.originalMessageId, envelope.senderIdentity, ack.receivedAt)
        }
    }

    suspend fun handleReadReceipt(envelope: SecureEnvelope) {
        if (envelope.payload.isEmpty()) {
            Log.w(TAG, "[READ] Received empty payload for ReadReceipt envelopeId=${envelope.logicalMessageId}; ignoring invalid receipt")
            return
        }

        try {
            val receipt = com.torxone.app.protocol.ReadReceipt.fromByteArray(envelope.payload)
            Log.i(TAG, "[READ] Received batch READ up to message=${receipt.upToMessageId.take(8)} for conv=${receipt.conversationId.take(8)}")
            val isGroup = groupService?.isGroupMessage(receipt.upToMessageId) ?: false
            if (!isGroup) {
                val targetMsg = messageDao.getById(receipt.upToMessageId)
                if (targetMsg != null) {
                    messageDao.markOutgoingReadUpTo(
                        conversationId = targetMsg.conversationId,
                        upToCreatedAt = targetMsg.createdAt,
                        status = DeliveryStatus.READ.name,
                        readAt = receipt.readAt
                    )
                } else {
                    messageDao.markRead(receipt.upToMessageId, DeliveryStatus.READ.name, receipt.readAt)
                }
            }
            agent.markRead(receipt.upToMessageId)
            groupService?.handleReadReceipt(receipt.upToMessageId, envelope.senderIdentity, receipt.readAt)
        } catch (e: Exception) {
            Log.w(TAG, "[READ] Failed to parse ReadReceipt payload for envelopeId=${envelope.logicalMessageId}: ${e.message}", e)
        }
    }
}
