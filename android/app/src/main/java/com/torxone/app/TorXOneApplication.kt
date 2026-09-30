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
import com.torxone.app.transport.tor.OnionEndpointManager
import com.torxone.app.transport.tor.TorBootstrapManager
import com.torxone.app.transport.tor.TorController
import com.torxone.app.transport.tor.TorHealthMonitor
import com.torxone.app.transport.tor.TorRouteManager
import com.torxone.app.transport.tor.TorTransport
import androidx.room.withTransaction
import com.torxone.app.crypto.AndroidKeystoreKeyProtector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow

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

    lateinit var torXRadioManager: com.torxone.app.transport.lora.TorXRadioManager
        private set
    lateinit var haLowGatewayManager: com.torxone.app.transport.halow.HaLowGatewayManager
        private set
    lateinit var globalGatewayRoutes: com.torxone.app.transport.gateway.GatewayRouteDirectory
        private set

    lateinit var sessionStore: RoomSessionStore
        private set

    lateinit var keyProtector: com.torxone.app.security.SecurityRepository
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

    lateinit var torRouteManager: TorRouteManager
        private set
    lateinit var onionEndpointManager: OnionEndpointManager
        private set
    lateinit var torController: TorController
        private set
    lateinit var torBootstrapManager: TorBootstrapManager
        private set
    lateinit var torHealthMonitor: TorHealthMonitor
        private set
    lateinit var torTransport: TorTransport
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

    sealed class AppInitState {
        data object Initializing : AppInitState()
        data class Ready(val identityId: String?) : AppInitState()
        data class Failed(val error: Throwable) : AppInitState()
    }

    private val _initState = kotlinx.coroutines.flow.MutableStateFlow<AppInitState>(AppInitState.Initializing)
    val initState: kotlinx.coroutines.flow.StateFlow<AppInitState> = _initState.asStateFlow()

    @Volatile
    private var runtimeInitialized = false

    @Volatile
    var cachedLocalIdentityId: String? = null
        private set

    fun getLocalIdentityId(): String? = cachedLocalIdentityId

    fun requireLocalIdentityId(): String = identityRepository.requireLocalIdentityId()

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 0. App Lifecycle & Visibility Tracking
        appVisibilityTracker = com.torxone.app.notifications.AppVisibilityTracker()
        registerActivityLifecycleCallbacks(appVisibilityTracker)

        // 0b. Settings DataStore
        settingsRepository = AppSettingsRepository(this)

        // 1. Single Keystore-backed authority for every persisted application secret.
        keyProtector = com.torxone.app.security.VersionedSecurityRepository(AndroidKeystoreKeyProtector())

        // 2. Database. Its SQLCipher passphrase is versioned by the same authority.
        database = TorXDatabase.getInstance(
            this,
            com.torxone.app.data.DatabasePassphraseProvider(this, keyProtector)
        )

        // 3. Identity
        identityRepository = KeystoreIdentityRepository(
            context = this,
            pendingInviteDao = database.pendingInviteDao(),
            keyProtector = keyProtector
        )

        // Continuously observe authoritative identity state
        applicationScope.launch {
            identityRepository.identityState.collect { state ->
                when (state) {
                    is com.torxone.app.identity.IdentityState.Ready -> {
                        cachedLocalIdentityId = state.identity.identityId
                        if (runtimeInitialized && _initState.value !is AppInitState.Failed) {
                            _initState.value = AppInitState.Ready(state.identity.identityId)
                        }
                    }
                    is com.torxone.app.identity.IdentityState.NoIdentity -> {
                        cachedLocalIdentityId = null
                        if (runtimeInitialized && _initState.value !is AppInitState.Failed) {
                            _initState.value = AppInitState.Ready(null)
                        }
                    }
                    is com.torxone.app.identity.IdentityState.Failed -> {
                        _initState.value = AppInitState.Failed(state.error)
                    }
                    is com.torxone.app.identity.IdentityState.Loading -> {
                        _initState.value = AppInitState.Initializing
                    }
                }
            }
        }

        // 3. Crypto / Session
        sessionStore = RoomSessionStore(database.sessionDao(), database.skippedKeyDao(), keyProtector) { block ->
            database.withTransaction { block() }
        }
        sessionCrypto = DoubleRatchetSessionCrypto(sessionStore)

        // 4. Connection Manager & Active Conversation Tracker
        connectionManager = ConnectionManager(database.connectionDao(), keyProtector)
        activeConversationTracker = ActiveConversationTracker()

        // 4b. Notification Authority (inject appSettingsRepository, M17)
        notificationManager = com.torxone.app.notifications.TorXNotificationManager(
            context = this,
            activeConversationTracker = activeConversationTracker,
            appVisibilityTracker = appVisibilityTracker,
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            localMessageStateDao = database.localMessageStateDao(),
            contactDao = database.contactDao(),
            appSettingsRepository = settingsRepository
        )

        // 5. Transport Router
        transportRouter = TransportRouter()
        val radioTrustStore = com.torxone.app.transport.lora.TorXRadioTrustStore(this)
        torXRadioManager = com.torxone.app.transport.lora.TorXRadioManager(
            scope = applicationScope,
            discovery = com.torxone.app.transport.lora.BleTorXRadioDiscovery(this),
            linkFactory = com.torxone.app.transport.lora.TorXRadioLinkFactory {
                com.torxone.app.transport.lora.BleTorXRadioLink(this)
            },
            isTrustedIdentity = radioTrustStore::isTrusted,
            trustIdentity = radioTrustStore::trust
        )
        transportRouter.registerTransport(com.torxone.app.transport.lora.LoRaTransport(torXRadioManager))
        val haLowTrustStore = com.torxone.app.transport.halow.HaLowGatewayTrustStore(this)
        haLowGatewayManager = com.torxone.app.transport.halow.HaLowGatewayManager(
            scope = applicationScope,
            discovery = com.torxone.app.transport.halow.AndroidHaLowGatewayDiscovery(this),
            linkFactory = com.torxone.app.transport.halow.HaLowGatewayLinkFactory {
                com.torxone.app.transport.halow.TcpHaLowGatewayLink()
            },
            isTrusted = haLowTrustStore::isTrusted,
            saveTrust = haLowTrustStore::trust
        )
        transportRouter.registerTransport(com.torxone.app.transport.halow.HaLowTransport(haLowGatewayManager))
        globalGatewayRoutes = com.torxone.app.transport.gateway.GatewayRouteDirectory()
        transportRouter.registerTransport(com.torxone.app.transport.gateway.GlobalGatewayTransport(
            localGateway = haLowGatewayManager,
            routes = globalGatewayRoutes
        ))

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
            contactDao = database.contactDao(),
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

        // One sequencing lock authority is shared by chat, groups, and media.
        // Feature-local coordinators can race and commit the same relationship sequence.
        val relationshipSendCoordinator = com.torxone.app.connection.RelationshipSendCoordinator(
            database = database,
            connectionManager = connectionManager,
            sessionStore = sessionStore,
            sessionCrypto = sessionCrypto,
            connectionDao = database.connectionDao(),
            agent = agent
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
            transactionRunner = { block -> database.withTransaction { block() } },
            contactDao = database.contactDao(),
            relationshipSendCoordinator = relationshipSendCoordinator,
            sessionStore = sessionStore,
            dedicatedMediaTransport = com.torxone.app.media.RoutedDedicatedMediaTransport(transportRouter),
            groupMessageDeliveryDao = database.groupMessageDeliveryDao()
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
            groupControlDao = database.groupControlDao(),
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            reactionDao = database.reactionDao(),
            contactDao = database.contactDao(),
            outboxDao = database.outboxDao(),
            connectionManager = connectionManager,
            sessionCrypto = sessionCrypto,
            agent = agent,
            localIdentityIdProvider = { getLocalIdentityId() },
            transactionRunner = { block -> database.withTransaction { block() } },
            relationshipSendCoordinator = relationshipSendCoordinator,
            sessionStore = sessionStore
        )
        val groupHandler = com.torxone.app.incoming.GroupHandler(
            groupDao = database.groupDao(),
            groupMemberDao = database.groupMemberDao(),
            conversationDao = database.conversationDao(),
            contactDao = database.contactDao(),
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
            localIdentityIdProvider = { getLocalIdentityId() },
            relationshipSendCoordinator = relationshipSendCoordinator,
            outboxDao = database.outboxDao()
        )
        val localId = getLocalIdentityId() ?: ""
        callManager = com.torxone.app.calls.CallManager(
            callService = callService,
            localIdentityId = localId,
            localIdentityIdProvider = { getLocalIdentityId() }
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
        val callHandler = com.torxone.app.calls.CallHandler(callManager, database.contactDao())

        // Route persistence must exist before bootstrap dispatch so the responder
        // can bind its authenticated permanent reverse route before sending ACK.
        torRouteManager = TorRouteManager(this)

        // 8. Incoming Dispatcher & Hub
        val chatReceiver = ChatReceiver(
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            activeConversationTracker = activeConversationTracker,
            notificationManager = notificationManager,
            contactDao = database.contactDao(),
            groupDao = database.groupDao()
        )
        val receiptHandler = DeliveryReceiptHandler(
            messageDao = database.messageDao(),
            contactDao = database.contactDao(),
            outboxDao = database.outboxDao(),
            agent = agent,
            groupService = groupService,
            bootstrapStateDao = database.bootstrapStateDao(),
            connectionDao = database.connectionDao(),
            pairRelationshipDao = database.pairRelationshipDao(),
            transactionRunner = { block -> database.withTransaction { block() } }
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
            conversationDao = database.conversationDao(),
            sessionStore = sessionStore,
            consumedInviteDao = database.consumedInviteDao(),
            bootstrapStateDao = database.bootstrapStateDao(),
            pairRelationshipDao = database.pairRelationshipDao(),
            keyProtector = keyProtector,
            torRouteManager = torRouteManager,
            consolidateDirectChats = { com.torxone.app.contacts.consolidateDirectConversations(database) }
        )
        incomingTransportHub = IncomingTransportHub(
            dispatcher = incomingDispatcher,
            dedicatedMediaFrameHandler = mediaService::handleDedicatedMediaFrame
        )
        applicationScope.launch {
            torXRadioManager.incomingFrames.collect { frame ->
                val payload = runCatching {
                    com.torxone.app.transport.lora.LoRaTransport.decodeReceivedPacket(frame).second
                }.getOrNull() ?: return@collect
                incomingTransportHub.onRawFrameReceived(payload, com.torxone.app.transport.TransportType.LORA)
            }
        }
        applicationScope.launch {
            haLowGatewayManager.incomingFrames.collect { bytes ->
                val frame = runCatching { com.torxone.app.transport.halow.HaLowGatewayProtocol.decode(bytes) }.getOrNull()
                    ?: return@collect
                if (frame.kind != com.torxone.app.transport.halow.HaLowGatewayProtocol.Kind.RECEIVED_PACKET) return@collect
                val payload = runCatching {
                    com.torxone.app.transport.halow.HaLowGatewayProtocol.decodeRoutedPayload(frame.payload).second
                }.getOrNull() ?: return@collect
                incomingTransportHub.onRawFrameReceived(payload, com.torxone.app.transport.TransportType.WIFI_HALOW)
            }
        }

        // 9. Tor transport: opaque TorX ciphertext over an embedded v3 onion service.
        onionEndpointManager = OnionEndpointManager(
            this, incomingTransportHub, torRouteManager, connectionManager
        )
        torController = TorController(this, onionEndpointManager)
        torBootstrapManager = TorBootstrapManager(torController)
        torHealthMonitor = TorHealthMonitor(torController)
        torTransport = TorTransport(torController, torRouteManager)
        transportRouter.registerTransport(torTransport)

        // 9b. Nearby Transport
        nearbyTransport = NearbyTransport(
            context = this,
            incomingTransportHub = incomingTransportHub,
            connectionManager = connectionManager,
            agent = agent,
            directRouteTable = directRouteTable,
            appSettingsRepository = settingsRepository
        )
        transportRouter.registerTransport(nearbyTransport)

        // 9c. Offline multi-hop mesh. Nearby remains the authenticated hop link;
        // mesh packets carry opaque TorX ciphertext and select one next hop.
        val meshPeers = com.torxone.app.transport.mesh.MeshPeerDirectory()
        val meshRoutes = com.torxone.app.transport.mesh.MeshRoutingTable()
        val meshRelayPolicy = com.torxone.app.transport.mesh.MeshRelayPolicy(relayEnabled = true)
        val durableRelayStore = com.torxone.app.transport.mesh.DurableRelayStore(
            database.relayQueueDao(), meshRelayPolicy
        )
        val knownMeshSigningKeys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val meshNodeByQueue = java.util.concurrent.ConcurrentHashMap<String, String>()
        val meshDiscovery = com.torxone.app.transport.mesh.MeshRouteDiscovery(
            routingTable = meshRoutes,
            peerDirectory = meshPeers,
            publicKeyForNode = { knownMeshSigningKeys[it] }
        )
        suspend fun sendMeshToPeer(peer: com.torxone.app.transport.mesh.MeshPeer, bytes: ByteArray): Boolean =
            nearbyTransport.send(
                com.torxone.app.transport.TransportDestination(peer.sendQueueAddress), bytes
            ) is com.torxone.app.transport.TransportResult.Accepted

        val meshNetwork = com.torxone.app.transport.mesh.MeshNetworkLayer(
            localNodeId = "identity-pending",
            localNodeIdProvider = { getLocalIdentityId() ?: "identity-pending" },
            routingTable = meshRoutes,
            duplicateCache = com.torxone.app.transport.mesh.MeshDuplicateCache(),
            relayPolicy = meshRelayPolicy,
            deliverLocal = { payload -> incomingDispatcher.dispatch(payload, com.torxone.app.transport.TransportType.RELAY) },
            sendToNextHop = { nodeId, packet ->
                meshPeers.get(nodeId)?.let { sendMeshToPeer(it, packet) } ?: false
            },
            durableRelayStore = durableRelayStore,
            sendCustodyAck = { nodeId, ack ->
                meshPeers.get(nodeId)?.let { sendMeshToPeer(it, ack) } ?: false
            }
        )
        val meshTransport = com.torxone.app.transport.mesh.MeshTransport(
            localNodeIdProvider = { getLocalIdentityId() },
            routingTable = meshRoutes,
            peerDirectory = meshPeers,
            peerLink = com.torxone.app.transport.mesh.MeshPeerLink { peer, packet -> sendMeshToPeer(peer, packet) },
            nodeIdForAddress = { meshNodeByQueue[it] }
        )
        transportRouter.registerTransport(meshTransport)

        incomingTransportHub.meshFrameHandler = { bytes, _, relationshipId ->
            if (relationshipId == null) {
                false
            } else {
                val contact = database.contactDao().getByRelationshipId(relationshipId)
                val peerNodeId = contact?.remoteIdentityId?.takeIf { contact.isRemoteIdentityKnown }
                if (peerNodeId == null) {
                    false
                } else if (com.torxone.app.transport.mesh.MeshCustodyAckCodec.isAck(bytes)) {
                    meshNetwork.receiveCustodyAck(peerNodeId, bytes)
                } else if (com.torxone.app.transport.mesh.MeshPacketCodec.isMeshPacket(bytes)) {
                    meshNetwork.receive(peerNodeId, bytes) !is com.torxone.app.transport.mesh.MeshReceiveResult.Rejected
                } else {
                    val announcement = runCatching {
                        com.torxone.app.transport.mesh.MeshRouteAnnouncementCodec.decode(bytes)
                    }.getOrNull()
                    val accepted = announcement?.let { meshDiscovery.receive(peerNodeId, it) } ?: false
                    if (accepted) {
                        val acceptedAnnouncement = requireNotNull(announcement)
                        val propagated = acceptedAnnouncement.copy(
                            advertisedHopCount = acceptedAnnouncement.advertisedHopCount + 1
                        )
                        if (propagated.advertisedHopCount < com.torxone.app.transport.mesh.MeshPacketCodec.MAX_TTL) {
                            val encoded = com.torxone.app.transport.mesh.MeshRouteAnnouncementCodec.encode(propagated)
                            meshPeers.all().filter { it.nodeId != peerNodeId }.forEach { sendMeshToPeer(it, encoded) }
                        }
                        meshNetwork.retryStored()
                    }
                    accepted
                }
            }
        }

        directRouteTable.addRouteListener { relationshipId, state, _ ->
            applicationScope.launch {
                val contact = database.contactDao().getByRelationshipId(relationshipId) ?: return@launch
                val nodeId = contact.remoteIdentityId.takeIf { contact.isRemoteIdentityKnown } ?: return@launch
                if (state == com.torxone.app.transport.nearby.RouteState.READY) {
                    val connection = connectionManager.getConnectionByRelationship(relationshipId) ?: return@launch
                    knownMeshSigningKeys[nodeId] = contact.signingPublicKey.copyOf()
                    meshNodeByQueue[connection.sendQueueId] = nodeId
                    val peer = com.torxone.app.transport.mesh.MeshPeer(
                        nodeId, relationshipId, connection.sendQueueId, contact.signingPublicKey, relayAllowed = true
                    )
                    meshPeers.authenticated(peer)
                    meshRelayPolicy.allow(nodeId)
                    val now = System.currentTimeMillis()
                    meshRoutes.installAuthenticated(
                        com.torxone.app.transport.mesh.MeshRoute(nodeId, nodeId, 1, now, now + 120_000, nodeId)
                    )
                    meshNetwork.retryStored()
                    val identity = identityRepository.loadIdentity() ?: return@launch
                    val announcement = meshDiscovery.createLocalAnnouncement(
                        identity.identityId, now, now + 120_000, identity.signingPrivateKey
                    )
                    sendMeshToPeer(peer, com.torxone.app.transport.mesh.MeshRouteAnnouncementCodec.encode(announcement))
                } else if (state == com.torxone.app.transport.nearby.RouteState.DISCONNECTED) {
                    meshPeers.disconnected(nodeId)
                    meshRelayPolicy.revoke(nodeId)
                    meshRoutes.removePeer(nodeId)
                }
            }
        }

        applicationScope.launch {
            // Populate trust and queue bindings for known contacts, including a
            // destination currently reachable only through mesh relays.
            database.contactDao().getAll().forEach { contact ->
                if (contact.isRemoteIdentityKnown) {
                    knownMeshSigningKeys[contact.remoteIdentityId] = contact.signingPublicKey.copyOf()
                    connectionManager.getConnectionByRelationship(contact.relationshipId)?.let { connection ->
                        meshNodeByQueue[connection.sendQueueId] = contact.remoteIdentityId
                    }
                }
            }
            while (isActive) {
                delay(60_000)
                val identity = identityRepository.loadIdentity() ?: continue
                val now = System.currentTimeMillis()
                val localAnnouncement = meshDiscovery.createLocalAnnouncement(
                    identity.identityId, now, now + 120_000, identity.signingPrivateKey
                )
                val encoded = com.torxone.app.transport.mesh.MeshRouteAnnouncementCodec.encode(localAnnouncement)
                meshPeers.all().forEach { peer ->
                    meshRoutes.installAuthenticated(
                        com.torxone.app.transport.mesh.MeshRoute(
                            peer.nodeId, peer.nodeId, 1, now, now + 120_000, peer.nodeId
                        )
                    )
                    sendMeshToPeer(peer, encoded)
                }
            }
        }
        applicationScope.launch {
            while (isActive) {
                delay(15_000)
                durableRelayStore.prune()
                meshNetwork.retryStored()
            }
        }

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
            appSettingsRepository = settingsRepository,
            sessionStore = sessionStore,
            mediaStorage = com.torxone.app.media.MediaStorage(this),
            relationshipSendCoordinator = relationshipSendCoordinator
        )

        // 10. Explicit asynchronous application initialization
        applicationScope.launch {
            try {
                val identity = identityRepository.loadIdentity()
                cachedLocalIdentityId = identity?.identityId
                database.withTransaction {
                    for (contact in database.contactDao().getAll()) {
                        val relationship = database.pairRelationshipDao().getById(contact.relationshipId) ?: continue
                        if (relationship.cryptoFormatVersion in 0..2) {
                            val raw = if (relationship.cryptoFormatVersion <= 1 && relationship.rootSecret.size == 32) relationship.rootSecret
                                else keyProtector.unwrap(relationship.rootSecret)
                            require(raw.size == 32) { "Invalid relationship secret length" }
                            database.pairRelationshipDao().upsert(relationship.copy(
                                rootSecret = keyProtector.wrap(raw), cryptoFormatVersion = 3
                            ))
                        } else {
                            require(relationship.cryptoFormatVersion == 3)
                            require(keyProtector.unwrap(relationship.rootSecret).size == 32)
                        }
                    }
                }
                com.torxone.app.contacts.consolidateDirectConversations(database)
                connectionManager.restoreFromDatabase(database.connectionDao())

                // Start background agent and transport only AFTER async initialization completes
                agent.start()
                torBootstrapManager.start()
                com.torxone.app.transport.tor.NetworkRecoveryMonitor(
                    this@TorXOneApplication, applicationScope, torTransport,
                    retry = { agent.triggerImmediateRetry() }
                ).start()
                applicationScope.launch {
                    torController.state.collect { state ->
                        if (state is com.torxone.app.transport.tor.TorConnectionState.Ready) {
                            agent.triggerImmediateRetry()
                        }
                    }
                }
                if (com.torxone.app.ui.permissions.PermissionHelper.arePermissionsGranted(
                        this@TorXOneApplication,
                        com.torxone.app.ui.permissions.PermissionHelper.getNearbyPermissions()
                    )
                ) {
                    nearbyTransport.start()
                    runCatching { torXRadioManager.start() }
                        .onFailure { android.util.Log.w("TorXOneApplication", "TorX Radio discovery unavailable", it) }
                }
                runCatching { haLowGatewayManager.start() }
                    .onFailure { android.util.Log.w("TorXOneApplication", "TorX HaLow discovery unavailable", it) }

                // Recover any interrupted media transfers, fan-outs, and incomplete bootstraps
                mediaService.recoverPendingTransfersOnStartup()
                groupService.recoverPendingFanout()
                recoverIncompleteBootstraps()

                // Anchor Tor, Nearby, and outbox processing to a sticky foreground
                // service after every successful process initialization.
                runCatching { com.torxone.app.service.TorXCoreService.start(this@TorXOneApplication) }
                    .onFailure { android.util.Log.w("TorXOneApplication", "Core foreground service start deferred", it) }

                runtimeInitialized = true
                _initState.value = AppInitState.Ready(cachedLocalIdentityId)
            } catch (e: Throwable) {
                android.util.Log.e("TorXOneApplication", "Async app initialization failed", e)
                _initState.value = AppInitState.Failed(e)
            }
        }
    }

    private suspend fun recoverIncompleteBootstraps() {
        try {
            val incomplete = database.bootstrapStateDao().getIncompleteBootstraps()
            for (state in incomplete) {
                // Restore the initiator-side invite authorization before retrying a
                // bootstrap queued before process death. Without this, Nearby rejects
                // the resumed peer even though all durable crypto state is present.
                if (state.isInitiator) {
                    nearbyTransport.registerScannedInvite(state.inviteId)
                }
                when (state.status) {
                    com.torxone.app.data.entity.BootstrapStatus.LOCAL_ESTABLISHED -> {
                        val session = sessionStore.loadSession(state.relationshipId)
                        if (session != null) {
                            database.bootstrapStateDao().updateStatus(state.relationshipId, com.torxone.app.data.entity.BootstrapStatus.CRYPTO_READY)
                        } else {
                            database.bootstrapStateDao().updateStatus(
                                state.relationshipId,
                                com.torxone.app.data.entity.BootstrapStatus.FAILED_RECOVERABLE,
                                error = "Ratchet uninitialized before crash"
                            )
                        }
                    }
                    com.torxone.app.data.entity.BootstrapStatus.CRYPTO_READY -> {
                        val outbox = database.outboxDao().getPending()
                        val hasItem = outbox.any { it.queueAddress == "invite-${state.inviteId}" }
                        if (hasItem) {
                            database.bootstrapStateDao().updateStatus(state.relationshipId, com.torxone.app.data.entity.BootstrapStatus.BOOTSTRAP_QUEUED)
                        }
                    }
                    com.torxone.app.data.entity.BootstrapStatus.BOOTSTRAP_QUEUED -> {
                        agent.wake()
                    }
                    com.torxone.app.data.entity.BootstrapStatus.REMOTE_CONFIRMED -> {
                        database.withTransaction {
                            database.connectionDao().updateStateByRelationship(state.relationshipId, "ACTIVE")
                            database.pairRelationshipDao().updateState(state.relationshipId, "ACTIVE")
                            database.bootstrapStateDao().updateStatus(state.relationshipId, com.torxone.app.data.entity.BootstrapStatus.ACTIVE)
                        }
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TorXOneApplication", "Error during bootstrap restart recovery: ${e.message}", e)
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
                        priority = item.priority,
                        attemptCount = item.attemptCount,
                        nextAttemptAt = item.nextAttemptAt,
                        createdAt = item.createdAt,
                        updatedAt = item.updatedAt,
                        expectsAck = item.expectsAck,
                        applicationSequence = item.applicationSequence,
                        relationshipId = item.relationshipId
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
                        priority = entity.priority,
                        attemptCount = entity.attemptCount,
                        nextAttemptAt = entity.nextAttemptAt,
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt,
                        expectsAck = entity.expectsAck,
                        applicationSequence = entity.applicationSequence,
                        relationshipId = entity.relationshipId
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

            override suspend fun removeByDeliveryId(deliveryId: String) {
                dao.removeByDeliveryId(deliveryId)
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
