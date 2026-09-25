package com.torxone.app

import android.app.Application
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.OutboxStore
import com.torxone.app.agent.ProcessedEnvelope
import com.torxone.app.agent.ProcessedEnvelopeStore
import com.torxone.app.agent.TorXAgent
import com.torxone.app.chat.ChatService
import com.torxone.app.profile.AppSettingsRepository
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
import kotlinx.coroutines.*

/**
 * TorX One Application.
 *
 * Strict startup sequence (Section 49):
 * Application → Database → Keystore/Identity → Crypto → ConnectionManager →
 * TransportRouter → Agent → IncomingDispatcher → Nearby
 */
class TorXOneApplication : Application() {

    companion object {
        lateinit var instance: TorXOneApplication
            private set
    }

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

    lateinit var appVisibilityTracker: com.torxone.app.notifications.AppVisibilityTracker
        private set

    lateinit var notificationManager: com.torxone.app.notifications.TorXNotificationManager
        private set

    lateinit var transportRouter: TransportRouter
        private set

    lateinit var agent: TorXAgent
        private set

    lateinit var chatService: ChatService
        private set

    lateinit var groupService: com.torxone.app.groups.GroupService
        private set

    lateinit var mediaService: com.torxone.app.media.MediaService
        private set

    lateinit var presenceService: com.torxone.app.chat.PresenceService
        private set

    lateinit var incomingTransportHub: IncomingTransportHub
        private set

    lateinit var nearbyTransport: NearbyTransport
        private set

    lateinit var settingsRepository: AppSettingsRepository
        private set

    lateinit var callManager: com.torxone.app.calls.CallManager
        private set

    lateinit var callNotificationManager: com.torxone.app.calls.CallNotificationManager
        private set

    lateinit var webRtcClient: com.torxone.app.calls.WebRtcClient
        private set

    lateinit var audioRouteManager: com.torxone.app.calls.AudioRouteManager
        private set

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var cachedLocalIdentityId: String? = null
        private set

    fun getLocalIdentityId(): String? {
        val cached = cachedLocalIdentityId
        if (cached != null) return cached
        return runBlocking {
            identityRepository.loadIdentity()?.identityId.also {
                cachedLocalIdentityId = it
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 0. App Lifecycle & Visibility Tracking
        appVisibilityTracker = com.torxone.app.notifications.AppVisibilityTracker()
        registerActivityLifecycleCallbacks(appVisibilityTracker)

        // 0b. Settings DataStore
        settingsRepository = AppSettingsRepository(this)

        // 1. Database
        database = TorXDatabase.getInstance(this)

        // 2. Keystore / Identity
        identityRepository = KeystoreIdentityRepository(this, database.pendingInviteDao())

        // 3. Crypto / Session
        val sessionStore = RoomSessionStore(database.sessionDao(), database.skippedKeyDao())
        sessionCrypto = DoubleRatchetSessionCrypto(sessionStore)

        // 4. Connection Manager & Active Conversation Tracker (Restore persisted connections, Section 6)
        connectionManager = ConnectionManager()
        activeConversationTracker = ActiveConversationTracker()
        applicationScope.launch {
            cachedLocalIdentityId = identityRepository.loadIdentity()?.identityId
            connectionManager.restoreFromDatabase(database.connectionDao())
        }

        // 4b. Notification Authority
        notificationManager = com.torxone.app.notifications.TorXNotificationManager(
            context = this,
            activeConversationTracker = activeConversationTracker,
            appVisibilityTracker = appVisibilityTracker,
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            localMessageStateDao = database.localMessageStateDao(),
            contactDao = database.contactDao()
        )

        // 5. Transport Router
        transportRouter = TransportRouter()

        // 6. TorXAgent
        agent = TorXAgent(
            transportRouter = transportRouter,
            outboxStore = createOutboxStore(),
            processedStore = createProcessedStore()
        )

        // 7. Direct Route Table & Presence Service
        val directRouteTable = com.torxone.app.transport.nearby.DirectRouteTable()
        presenceService = com.torxone.app.chat.PresenceService(
            connectionManager = connectionManager,
            sessionCrypto = sessionCrypto,
            agent = agent,
            directRouteTable = directRouteTable,
            localIdentityIdProvider = { getLocalIdentityId() },
            appSettingsRepository = settingsRepository
        )
        val presenceHandler = PresenceHandler(presenceService)
        val typingHandler = TypingHandler(presenceService)
        val reactionHandler = ReactionHandler(
            reactionDao = database.reactionDao(),
            messageDao = database.messageDao()
        )
        val editHandler = EditHandler(
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            notificationManager = notificationManager
        )
        val deleteHandler = DeleteHandler(
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            notificationManager = notificationManager
        )

        // 7b. Media Service & Handler
        mediaService = com.torxone.app.media.MediaService(
            context = this,
            sessionCrypto = sessionCrypto,
            connectionManager = connectionManager,
            agent = agent,
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            mediaDao = database.mediaDao(),
            mediaTransferDao = database.mediaTransferDao(),
            outboxDao = database.outboxDao(),
            localIdentityIdProvider = { getLocalIdentityId() },
            appSettingsRepository = settingsRepository,
            transactionRunner = { block -> database.withTransaction { block() } }
        )
        val mediaHandler = MediaHandler(
            mediaService = mediaService,
            notificationManager = notificationManager
        )

        // 7c. Group Service & Handler
        groupService = com.torxone.app.groups.GroupService(
            groupDao = database.groupDao(),
            groupMemberDao = database.groupMemberDao(),
            groupMessageDeliveryDao = database.groupMessageDeliveryDao(),
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            reactionDao = database.reactionDao(),
            contactDao = database.contactDao(),
            outboxDao = database.outboxDao(),
            connectionManager = connectionManager,
            sessionCrypto = sessionCrypto,
            agent = agent,
            localIdentityIdProvider = { getLocalIdentityId() },
            transactionRunner = { block -> database.withTransaction { block() } }
        )
        val groupHandler = com.torxone.app.incoming.GroupHandler(
            groupDao = database.groupDao(),
            groupMemberDao = database.groupMemberDao(),
            conversationDao = database.conversationDao(),
            localIdentityIdProvider = { getLocalIdentityId() },
            notificationManager = notificationManager,
            transactionRunner = { block -> database.withTransaction { block() } }
        )

        // 7d. Call Subsystem
        callNotificationManager = com.torxone.app.calls.CallNotificationManager(this)
        audioRouteManager = com.torxone.app.calls.AudioRouteManager(this)
        val callService = com.torxone.app.calls.CallService(
            sessionCrypto = sessionCrypto,
            connectionManager = connectionManager,
            agent = agent,
            conversationDao = database.conversationDao(),
            callHistoryDao = database.callHistoryDao(),
            localIdentityIdProvider = { getLocalIdentityId() }
        )
        val localId = getLocalIdentityId() ?: ""
        callManager = com.torxone.app.calls.CallManager(
            callService = callService,
            localIdentityId = localId
        )
        webRtcClient = com.torxone.app.calls.WebRtcClient(this, callManager)
        webRtcClient.initialize()
        val callCoordinator = com.torxone.app.calls.CallCoordinator(
            context = this,
            callManager = callManager,
            webRtcClient = webRtcClient,
            audioRouteManager = audioRouteManager,
            callNotificationManager = callNotificationManager,
            contactDao = database.contactDao()
        )
        val callHandler = com.torxone.app.calls.CallHandler(callManager)

        // 8. Incoming Dispatcher & Hub
        val chatReceiver = ChatReceiver(
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            activeConversationTracker = activeConversationTracker,
            notificationManager = notificationManager
        )
        val receiptHandler = DeliveryReceiptHandler(
            messageDao = database.messageDao(),
            outboxDao = database.outboxDao(),
            agent = agent,
            groupService = groupService
        )
        val incomingDispatcher = IncomingDispatcher(
            connectionManager = connectionManager,
            sessionCrypto = sessionCrypto,
            processedEnvelopeDao = database.processedEnvelopeDao(),
            chatReceiver = chatReceiver,
            deliveryReceiptHandler = receiptHandler,
            agent = agent,
            localIdentityIdProvider = { getLocalIdentityId() },
            presenceHandler = presenceHandler,
            typingHandler = typingHandler,
            reactionHandler = reactionHandler,
            editHandler = editHandler,
            deleteHandler = deleteHandler,
            mediaHandler = mediaHandler,
            groupHandler = groupHandler,
            callHandler = callHandler,
            groupDao = database.groupDao(),
            groupMemberDao = database.groupMemberDao(),
            transactionRunner = { block -> database.withTransaction { block() } },
            pendingInviteDao = database.pendingInviteDao(),
            identityRepository = identityRepository,
            connectionDao = database.connectionDao(),
            contactDao = database.contactDao(),
            conversationDao = database.conversationDao()
        )
        incomingTransportHub = IncomingTransportHub(incomingDispatcher)

        // 9. Nearby Transport
        nearbyTransport = NearbyTransport(
            context = this,
            incomingTransportHub = incomingTransportHub,
            connectionManager = connectionManager,
            agent = agent,
            directRouteTable = directRouteTable,
            appSettingsRepository = settingsRepository
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
            outboxDao = database.outboxDao(),
            reactionDao = database.reactionDao(),
            localMessageStateDao = database.localMessageStateDao(),
            notificationManager = notificationManager,
            appSettingsRepository = settingsRepository
        )

        // 10. Start background agent and transport
        agent.start()
        nearbyTransport.start()

        // 11. Recover any interrupted media transfers & sweep orphan temp files
        applicationScope.launch {
            mediaService.recoverPendingTransfersOnStartup()
        }
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
                        updatedAt = item.updatedAt,
                        expectsAck = item.expectsAck
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
                        updatedAt = entity.updatedAt,
                        expectsAck = entity.expectsAck
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
