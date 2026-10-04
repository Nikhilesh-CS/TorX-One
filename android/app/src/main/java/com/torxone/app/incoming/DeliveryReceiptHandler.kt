package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.dao.OutboxDao
import com.torxone.app.protocol.DeliveryAck
import com.torxone.app.protocol.SecureEnvelope
import com.torxone.app.transport.TransportDestination

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

    data class CommittedAck(
        val originalEnvelopeId: String,
        val logicalMessageId: String?,
        val destination: TransportDestination,
        val conversationId: String,
        val applicationSequence: Long?,
        val confirmedInviteId: String?,
        val visibleStatus: DeliveryStatus
    )

    /** Durable changes only. The caller must invoke afterCommit after its outer transaction succeeds. */
    suspend fun handleDeliveryAck(envelope: SecureEnvelope, connection: com.torxone.app.connection.Connection? = null): CommittedAck? {
        val ack = DeliveryAck.fromByteArray(envelope.payload)
        val envId = ack.originalEnvelopeId
        val authenticatedConnection = connection ?: throw SecurityException("ACK lacks authenticated connection")
        require(!envId.isNullOrBlank()) { "ACK requires exact delivery ID" }
        val pending = outboxDao.getByDeliveryId(envId) ?: return null
        require(pending.logicalMessageId == ack.originalMessageId && pending.connectionId == authenticatedConnection.connectionId) {
            "ACK does not belong to authenticated relationship"
        }
        Log.i(TAG, "[ACK]")

        // Check if this ACK corresponds to an initiator bootstrap confirmation (P0-7)
        var confirmedInviteId: String? = null
        if (bootstrapStateDao != null) {
            val bootstrapState = bootstrapStateDao.getByInviteId(ack.originalMessageId)

            if (bootstrapState != null && bootstrapState.relationshipId == authenticatedConnection.relationshipId && bootstrapState.isInitiator && bootstrapState.status != com.torxone.app.data.entity.BootstrapStatus.ACTIVE) {
                Log.i(TAG, "[BOOTSTRAP CONFIRMED]")
                val runner = transactionRunner ?: { block -> block() }
                runner {
                    connectionDao?.updateStateByRelationship(bootstrapState.relationshipId, "ACTIVE")
                    pairRelationshipDao?.updateState(bootstrapState.relationshipId, "ACTIVE")
                    bootstrapStateDao.updateStatus(bootstrapState.relationshipId, com.torxone.app.data.entity.BootstrapStatus.ACTIVE)
                }
                confirmedInviteId = bootstrapState.inviteId
            }
        }

        val isGroup = groupService?.isGroupMessage(ack.originalMessageId) ?: false
        val currentMessage = if (isGroup) null else messageDao.getById(ack.originalMessageId)
        val visibleStatus = if (currentMessage?.readAt != null || currentMessage?.status == DeliveryStatus.READ.name)
            DeliveryStatus.READ else DeliveryStatus.DELIVERED
        if (!isGroup) {
            // 1. Mark 1:1 message as DELIVERED in Room
            messageDao.markDelivered(ack.originalMessageId, visibleStatus.name, ack.receivedAt)
        } else {
            // Phase 13: Group message exact delivery ACK semantics
            // Notify GroupService to record per-recipient delivery status.
            // Aggregate MessageEntity is marked DELIVERED only when all active members have acknowledged.
            groupService?.handleDeliveryAck(ack.originalMessageId, envelope.senderIdentity, ack.receivedAt)
        }
        // Delete only this exact recipient's delivery, within the caller's transaction.
        outboxDao.removeByDeliveryId(envId)
        return CommittedAck(envId, if (isGroup) null else ack.originalMessageId,
            TransportDestination(pending.queueAddress, relationshipId = authenticatedConnection.relationshipId),
            pending.conversationId, pending.applicationSequence, confirmedInviteId, visibleStatus)
    }

    suspend fun afterCommit(receipt: CommittedAck) {
        agent.onAcknowledgmentCommitted(receipt.originalEnvelopeId, receipt.logicalMessageId,
            receipt.destination, receipt.conversationId, receipt.applicationSequence, receipt.visibleStatus)
        receipt.confirmedInviteId?.let(onBootstrapConfirmed)
    }

    suspend fun handleReadReceipt(envelope: SecureEnvelope, connection: com.torxone.app.connection.Connection? = null): String? {
        require(envelope.payload.isNotEmpty()) { "Read receipt payload is empty" }
        val receipt = com.torxone.app.protocol.ReadReceipt.fromByteArray(envelope.payload)
        Log.i(TAG, "[READ]")
        val authenticatedConnection = connection ?: throw SecurityException("Read receipt lacks authenticated connection")
        val target = messageDao.getById(receipt.upToMessageId) ?: return null
        require(target.direction == com.torxone.app.data.entity.MessageDirection.OUTGOING)
        require(target.conversationId == receipt.conversationId && envelope.conversationId == receipt.conversationId)
        val isGroup = groupService?.isGroupMessage(receipt.upToMessageId) ?: false
        if (!isGroup) {
            val binding = contactDao?.getByRelationshipId(authenticatedConnection.relationshipId)
                ?.let { it.conversationId to it.remoteIdentityId }
                ?: authenticatedContactProvider(authenticatedConnection.relationshipId)
                ?: throw SecurityException("Read receipt relationship unavailable")
            require(binding.first == target.conversationId && binding.second == envelope.senderIdentity)
            messageDao.markOutgoingReadUpTo(
                conversationId = target.conversationId,
                upToCreatedAt = target.createdAt,
                status = DeliveryStatus.READ.name,
                readAt = receipt.readAt
            )
        }
        groupService?.handleReadReceipt(receipt.upToMessageId, envelope.senderIdentity, receipt.readAt)
        return receipt.upToMessageId
    }

    suspend fun afterReadCommit(logicalMessageId: String) { agent.markRead(logicalMessageId) }
}
