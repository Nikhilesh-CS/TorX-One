package com.torxone.app

import android.app.Application
import com.torxone.app.agent.TorXAgent
import com.torxone.app.data.TorXDatabase
import com.torxone.app.transport.TransportRouter

/**
 * TorX One Application.
 *
 * Initializes the core architecture stack:
 * Database → TransportRouter → TorXAgent
 */
class TorXOneApplication : Application() {

    lateinit var database: TorXDatabase
        private set

    lateinit var transportRouter: TransportRouter
        private set

    lateinit var agent: TorXAgent
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize database
        database = TorXDatabase.getInstance(this)

        // Initialize transport router (transports registered later)
        transportRouter = TransportRouter()

        // Initialize TorXAgent — the single delivery authority
        agent = TorXAgent(
            transportRouter = transportRouter,
            outboxStore = createOutboxStore(),
            processedStore = createProcessedStore()
        )

        // Start the agent
        agent.start()
    }

    private fun createOutboxStore(): com.torxone.app.agent.OutboxStore {
        val dao = database.outboxDao()
        return object : com.torxone.app.agent.OutboxStore {
            override suspend fun insert(item: com.torxone.app.agent.DeliveryItem) {
                dao.insert(com.torxone.app.data.entity.OutboxEntity(
                    deliveryId = item.deliveryId,
                    logicalMessageId = item.logicalMessageId,
                    conversationId = item.conversationId,
                    connectionId = item.connectionId,
                    queueAddress = item.queueAddress,
                    ciphertext = item.ciphertext,
                    queueAuthenticator = item.queueAuthenticator,
                    status = item.status.name,
                    attemptCount = item.attemptCount,
                    nextAttemptAt = item.nextAttemptAt,
                    createdAt = item.createdAt
                ))
            }

            override suspend fun getPendingItems(): List<com.torxone.app.agent.DeliveryItem> {
                return dao.getPending().map { entity ->
                    com.torxone.app.agent.DeliveryItem(
                        deliveryId = entity.deliveryId,
                        logicalMessageId = entity.logicalMessageId,
                        conversationId = entity.conversationId,
                        connectionId = entity.connectionId,
                        queueAddress = entity.queueAddress,
                        ciphertext = entity.ciphertext,
                        queueAuthenticator = entity.queueAuthenticator,
                        status = com.torxone.app.agent.DeliveryStatus.valueOf(entity.status),
                        attemptCount = entity.attemptCount,
                        nextAttemptAt = entity.nextAttemptAt,
                        createdAt = entity.createdAt
                    )
                }
            }

            override suspend fun updateStatus(deliveryId: String, status: com.torxone.app.agent.DeliveryStatus) {
                dao.updateStatus(deliveryId, status.name)
            }

            override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long) {
                dao.updateRetry(deliveryId, attemptCount, nextAttemptAt)
            }

            override suspend fun removeByMessageId(logicalMessageId: String) {
                dao.removeByMessageId(logicalMessageId)
            }
        }
    }

    private fun createProcessedStore(): com.torxone.app.agent.ProcessedEnvelopeStore {
        val dao = database.processedEnvelopeDao()
        return object : com.torxone.app.agent.ProcessedEnvelopeStore {
            override suspend fun isProcessed(envelopeId: String): Boolean {
                return dao.isProcessed(envelopeId)
            }

            override suspend fun isMessageProcessed(logicalMessageId: String): Boolean {
                return dao.isMessageProcessed(logicalMessageId)
            }

            override suspend fun markProcessed(record: com.torxone.app.agent.ProcessedEnvelope) {
                dao.insert(com.torxone.app.data.entity.ProcessedEnvelopeEntity(
                    envelopeId = record.envelopeId,
                    logicalMessageId = record.logicalMessageId,
                    processedAt = record.processedAt
                ))
            }
        }
    }
}
