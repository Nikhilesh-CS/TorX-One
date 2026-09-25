package com.torxone.app

import android.app.Application
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.OutboxStore
import com.torxone.app.agent.ProcessedEnvelope
import com.torxone.app.agent.ProcessedEnvelopeStore
import com.torxone.app.agent.TorXAgent
import com.torxone.app.chat.ChatService
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.crypto.RoomSessionStore
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.OutboxEntity
import com.torxone.app.data.entity.ProcessedEnvelopeEntity
import com.torxone.app.identity.IdentityRepository
import com.torxone.app.identity.KeystoreIdentityRepository
import com.torxone.app.incoming.*
import com.torxone.app.transport.TransportRouter
import com.torxone.app.transport.nearby.NearbyTransport
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking

/**
 * TorX One Application.
 *
 * Strict startup sequence (Section 49):
 * Application → Database → Keystore/Identity → Crypto → ConnectionManager →
 * TransportRouter → Agent → IncomingDispatcher → Nearby
 */
class TorXOneApplication : Application() {

    lateinit var database: TorXDatabase
        private set

    lateinit var identityRepository: IdentityRepository
        private set

    lateinit var sessionCrypto: SessionCrypto
        private set

    lateinit var connectionManager: ConnectionManager
        private set

    lateinit var activeConversationTracker: ActiveConversationTracker
        private set

    lateinit var transportRouter: TransportRouter
        private set

    lateinit var agent: TorXAgent
        private set

    lateinit var chatService: ChatService
        private set

    lateinit var incomingTransportHub: IncomingTransportHub
        private set

    lateinit var nearbyTransport: NearbyTransport
        private set

    override fun onCreate() {
        super.onCreate()

        // 1. Database
        database = TorXDatabase.getInstance(this)

        // 2. Keystore / Identity
        identityRepository = KeystoreIdentityRepository(this, database.pendingInviteDao())

        // 3. Crypto / Session
        val sessionStore = RoomSessionStore(database.sessionDao(), database.skippedKeyDao())
        sessionCrypto = DoubleRatchetSessionCrypto(sessionStore)

        // 4. Connection Manager & Active Conversation Tracker (Restore persisted connections, Section 6)
        connectionManager = ConnectionManager()
        runBlocking {
            connectionManager.restoreFromDatabase(database.connectionDao())
        }
        activeConversationTracker = ActiveConversationTracker()

        // 5. Transport Router
        transportRouter = TransportRouter()

        // 6. TorXAgent
        agent = TorXAgent(
            transportRouter = transportRouter,
            outboxStore = createOutboxStore(),
            processedStore = createProcessedStore()
        )

        // 7. Incoming Dispatcher & Hub
        val chatReceiver = ChatReceiver(
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            activeConversationTracker = activeConversationTracker
        )
        val receiptHandler = DeliveryReceiptHandler(
            messageDao = database.messageDao(),
            outboxDao = database.outboxDao(),
            agent = agent
        )
        val incomingDispatcher = IncomingDispatcher(
            connectionManager = connectionManager,
            sessionCrypto = sessionCrypto,
            processedEnvelopeDao = database.processedEnvelopeDao(),
            chatReceiver = chatReceiver,
            deliveryReceiptHandler = receiptHandler,
            agent = agent,
            localIdentityIdProvider = {
                runBlocking { identityRepository.loadIdentity()?.identityId }
            },
            transactionRunner = { block -> database.withTransaction { block() } },
            pendingInviteDao = database.pendingInviteDao(),
            identityRepository = identityRepository,
            connectionDao = database.connectionDao(),
            contactDao = database.contactDao(),
            conversationDao = database.conversationDao()
        )
        incomingTransportHub = IncomingTransportHub(incomingDispatcher)

        // 8. Nearby Transport
        nearbyTransport = NearbyTransport(
            context = this,
            incomingTransportHub = incomingTransportHub,
            connectionManager = connectionManager,
            agent = agent
        )
        transportRouter.registerTransport(nearbyTransport)

        // 9. Chat Feature Service
        chatService = ChatService(
            database = database,
            sessionCrypto = sessionCrypto,
            connectionManager = connectionManager,
            agent = agent,
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            outboxDao = database.outboxDao()
        )

        // 10. Start background agent and transport
        agent.start()
        nearbyTransport.start()
    }

    private fun createOutboxStore(): OutboxStore {
        val dao = database.outboxDao()
        return object : OutboxStore {
            override suspend fun insert(item: DeliveryItem) {
                dao.insert(
                    OutboxEntity(
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
                        createdAt = item.createdAt,
                        updatedAt = item.updatedAt
                    )
                )
            }

            override suspend fun getPendingItems(): List<DeliveryItem> {
                return dao.getPending().map { entity ->
                    DeliveryItem(
                        deliveryId = entity.deliveryId,
                        logicalMessageId = entity.logicalMessageId,
                        conversationId = entity.conversationId,
                        connectionId = entity.connectionId,
                        queueAddress = entity.queueAddress,
                        ciphertext = entity.ciphertext,
                        queueAuthenticator = entity.queueAuthenticator,
                        status = DeliveryStatus.valueOf(entity.status),
                        attemptCount = entity.attemptCount,
                        nextAttemptAt = entity.nextAttemptAt,
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt
                    )
                }
            }

            override suspend fun updateStatus(deliveryId: String, status: DeliveryStatus) {
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

    private fun createProcessedStore(): ProcessedEnvelopeStore {
        val dao = database.processedEnvelopeDao()
        return object : ProcessedEnvelopeStore {
            override suspend fun isProcessed(envelopeId: String): Boolean {
                return dao.isProcessed(envelopeId)
            }

            override suspend fun isMessageProcessed(logicalMessageId: String): Boolean {
                return dao.isMessageProcessed(logicalMessageId)
            }

            override suspend fun markProcessed(record: ProcessedEnvelope) {
                dao.insert(
                    ProcessedEnvelopeEntity(
                        envelopeId = record.envelopeId,
                        logicalMessageId = record.logicalMessageId,
                        processedAt = record.processedAt
                    )
                )
            }
        }
    }
}
