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
    private val groupService: com.torxone.app.groups.GroupService? = null,
    private val bootstrapStateDao: com.torxone.app.data.dao.BootstrapStateDao? = null,
    private val connectionDao: com.torxone.app.data.dao.ConnectionDao? = null,
    private val pairRelationshipDao: com.torxone.app.data.dao.PairRelationshipDao? = null,
    private val transactionRunner: (suspend (suspend () -> Unit) -> Unit)? = null,
    private val contactDao: com.torxone.app.data.dao.ContactDao? = null,
    private val authenticatedContactProvider: suspend (String) -> Pair<String, String>? = { null },
    private val onBootstrapConfirmed: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "DeliveryReceiptHandler"
    }

    suspend fun handleDeliveryAck(envelope: SecureEnvelope, connection: com.torxone.app.connection.Connection? = null) {
        val ack = DeliveryAck.fromByteArray(envelope.payload)
        val envId = ack.originalEnvelopeId
        val authenticatedConnection = connection ?: throw SecurityException("ACK lacks authenticated connection")
        require(!envId.isNullOrBlank()) { "ACK requires exact delivery ID" }
        val pending = outboxDao.getByDeliveryId(envId) ?: return
        require(pending.logicalMessageId == ack.originalMessageId && pending.connectionId == authenticatedConnection.connectionId) {
            "ACK does not belong to authenticated relationship"
        }
        val envPrefix = envId?.take(8) ?: "none"
        Log.i(TAG, "[ACK] Received ACK for message=${ack.originalMessageId.take(8)} env=$envPrefix")

        // Check if this ACK corresponds to an initiator bootstrap confirmation (P0-7)
        if (bootstrapStateDao != null) {
            val bootstrapState = bootstrapStateDao.getByInviteId(ack.originalMessageId)

            if (bootstrapState != null && bootstrapState.relationshipId == authenticatedConnection.relationshipId && bootstrapState.isInitiator && bootstrapState.status != com.torxone.app.data.entity.BootstrapStatus.ACTIVE) {
                Log.i(TAG, "[BOOTSTRAP CONFIRMED] Initiator received confirmation for invite=${bootstrapState.inviteId} rel=${bootstrapState.relationshipId}")
                val runner = transactionRunner ?: { block -> block() }
                runner {
                    connectionDao?.updateStateByRelationship(bootstrapState.relationshipId, "ACTIVE")
                    pairRelationshipDao?.updateState(bootstrapState.relationshipId, "ACTIVE")
                    bootstrapStateDao.updateStatus(bootstrapState.relationshipId, com.torxone.app.data.entity.BootstrapStatus.ACTIVE)
                }
                onBootstrapConfirmed(bootstrapState.inviteId)
            }
        }

        val isGroup = groupService?.isGroupMessage(ack.originalMessageId) ?: false
        if (!isGroup) {
            // 1. Mark 1:1 message as DELIVERED in Room
            messageDao.markDelivered(ack.originalMessageId, DeliveryStatus.DELIVERED.name, ack.receivedAt)

            // 2. Remove exact outbox item
            if (!envId.isNullOrBlank()) {
                agent.markDeliveryAcknowledged(envId, ack.originalMessageId)
            }
            if (envId.isNullOrBlank()) {
                outboxDao.removeByMessageId(ack.originalMessageId)
                agent.markDelivered(ack.originalMessageId)
            }
        } else {
            // Phase 13: Group message exact delivery ACK semantics
            // Remove ONLY this exact recipient's delivery from outbox
            if (!envId.isNullOrBlank()) {
                agent.markDeliveryAcknowledged(envId, null)
            }

            // Notify GroupService to record per-recipient delivery status.
            // Aggregate MessageEntity is marked DELIVERED only when all active members have acknowledged.
            groupService?.handleDeliveryAck(ack.originalMessageId, envelope.senderIdentity, ack.receivedAt)
        }
    }

    suspend fun handleReadReceipt(envelope: SecureEnvelope, connection: com.torxone.app.connection.Connection? = null) {
        if (envelope.payload.isEmpty()) {
            Log.w(TAG, "[READ] Received empty payload for ReadReceipt envelopeId=${envelope.logicalMessageId}; ignoring invalid receipt")
            return
        }

        try {
            val receipt = com.torxone.app.protocol.ReadReceipt.fromByteArray(envelope.payload)
            Log.i(TAG, "[READ] Received batch READ up to message=${receipt.upToMessageId.take(8)} for conv=${receipt.conversationId.take(8)}")
            val authenticatedConnection = connection ?: throw SecurityException("Read receipt lacks authenticated connection")
            val target = messageDao.getById(receipt.upToMessageId) ?: return
            require(target.direction == com.torxone.app.data.entity.MessageDirection.OUTGOING)
            require(target.conversationId == receipt.conversationId && envelope.conversationId == receipt.conversationId)
            val isGroup = groupService?.isGroupMessage(receipt.upToMessageId) ?: false
            if (!isGroup) {
                val binding = contactDao?.getByRelationshipId(authenticatedConnection.relationshipId)
                    ?.let { it.conversationId to it.remoteIdentityId }
                    ?: authenticatedContactProvider(authenticatedConnection.relationshipId)
                    ?: throw SecurityException("Read receipt relationship unavailable")
                require(binding.first == target.conversationId && binding.second == envelope.senderIdentity)
            }
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
