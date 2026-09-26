package com.torxone.app.transport.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.incoming.IncomingTransportHub
import com.torxone.app.profile.AppSettingsRepository
import com.torxone.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * NearbyTransport — Production-quality Google Nearby Connections direct pipe.
 *
 * Implements:
 * - Exact peer binding and endpoint <-> relationship mapping via DirectRouteTable
 * - Connection authentication handshake (HELLO -> AUTH_PROOF -> AUTH_OK) before user traffic
 * - Deterministic tie-breaker arbitration avoiding double connection races
 * - Automatic reconnect and outbox recovery (TorXAgent.triggerImmediateRetry())
 * - Transfer timeout cleanup (no leaked pendingTransfers)
 * - Per-endpoint bounded send concurrency and backpressure control
 * - BYTES payload size enforcement (MAX_DIRECT_FRAME_SIZE = 64KB)
 * - Transport health state machine (DISCONNECTED -> DISCOVERING -> CONNECTING -> AUTHENTICATING -> READY)
 * - Stale endpoint detection and heartbeat ping/pong
 */
class NearbyTransport(
    private val context: Context,
    private val incomingTransportHub: IncomingTransportHub,
    private val connectionManager: ConnectionManager? = null,
    private val agent: TorXAgent? = null,
    customEndpointName: String? = null,
    adapter: NearbyConnectionsAdapter? = null,
    val directRouteTable: DirectRouteTable = DirectRouteTable(),
    private val appSettingsRepository: AppSettingsRepository? = null
) : Transport {

    companion object {
        private const val TAG = "NearbyTransport"
        private const val SERVICE_ID = "com.torxone.mesh"
        private const val MAX_CONCURRENT_TRANSFERS_PER_PEER = 2
        private const val TRANSFER_TIMEOUT_MS = 15_000L
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val STALE_PING_THRESHOLD_MS = 30_000L
        private const val STALE_DISCONNECT_THRESHOLD_MS = 60_000L
    }

    override val type: TransportType = TransportType.NEARBY

    private val connectionsAdapter: NearbyConnectionsAdapter =
        adapter ?: GmsNearbyConnectionsAdapter(Nearby.getConnectionsClient(context))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Deterministic tie-breaker for duplicate connection arbitration
    val localTieBreaker: Long = customEndpointName?.removePrefix("TorX_")?.toLongOrNull(16)
        ?: (SecureRandom().nextLong() and Long.MAX_VALUE)
    val localEndpointName: String = customEndpointName ?: "TorX_${localTieBreaker.toString(16)}"

    private val _healthState = MutableStateFlow(TransportHealthState.DISCONNECTED)
    val healthState: Flow<TransportHealthState> = _healthState.asStateFlow()

    private val _availability = MutableStateFlow<TransportAvailability>(TransportAvailability.Unavailable("Not started"))
    override fun availability(): Flow<TransportAvailability> = _availability.asStateFlow()

    private val pendingTransfers = ConcurrentHashMap<Long, CompletableDeferred<TransportResult>>()
    private val payloadIdToEndpoint = ConcurrentHashMap<Long, String>()
    private val endpointSemaphores = ConcurrentHashMap<String, Semaphore>()

    private val localChallenges = ConcurrentHashMap<String, ByteArray>()
    private val remoteChallenges = ConcurrentHashMap<String, ByteArray>()

    private var heartbeatJob: Job? = null
    private val secureRandom = SecureRandom()

    @Volatile
    private var autoConnectEnabled: Boolean = true

    init {
        appSettingsRepository?.let { repo ->
            scope.launch {
                repo.autoConnectNearby.collect { enabled ->
                    autoConnectEnabled = enabled
                    Log.d(TAG, "autoConnectNearby setting updated: $enabled")
                }
            }
        }
    }

    private fun getSemaphoreForEndpoint(endpointId: String): Semaphore =
        endpointSemaphores.computeIfAbsent(endpointId) { Semaphore(MAX_CONCURRENT_TRANSFERS_PER_PEER) }

    fun bindQueueToEndpoint(queueAddress: String, endpointId: String) {
        directRouteTable.bindQueue(queueAddress, endpointId)
        Log.d(TAG, "[ROUTING] Bound queue ${queueAddress.take(8)} -> endpoint $endpointId")
    }

    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            if (bytes.size > MAX_DIRECT_FRAME_SIZE) {
                Log.w(TAG, "[RX REJECT] Received frame exceeding MAX_DIRECT_FRAME_SIZE: ${bytes.size} bytes")
                return
            }

            directRouteTable.updateLastSeen(endpointId)

            val frame = NearbyWireFrame.decode(bytes)
            when (frame) {
                is NearbyWireFrame.Data -> {
                    Log.d(TAG, "[RX DATA] Received ${frame.payload.size} data bytes from endpoint $endpointId")
                    scope.launch {
                        incomingTransportHub.onRawFrameReceived(frame.payload, TransportType.NEARBY)
                    }
                }
                is NearbyWireFrame.Control.Hello -> {
                    handleHelloReceived(endpointId, frame)
                }
                is NearbyWireFrame.Control.AuthProof -> {
                    handleAuthProofReceived(endpointId, frame)
                }
                is NearbyWireFrame.Control.AuthOk -> {
                    handleAuthOkReceived(endpointId, frame)
                }
                is NearbyWireFrame.Control.Ping -> {
                    sendControlMessage(endpointId, NearbyWireFrame.Control.Pong)
                }
                is NearbyWireFrame.Control.Pong -> {
                    Log.d(TAG, "[HEARTBEAT] Received PONG from endpoint $endpointId")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val deferred = pendingTransfers[update.payloadId] ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    Log.i(TAG, "[TX COMPLETE] Payload ${update.payloadId} transferred to $endpointId")
                    deferred.complete(TransportResult.Accepted(TransportType.NEARBY))
                    pendingTransfers.remove(update.payloadId)
                    payloadIdToEndpoint.remove(update.payloadId)
                }
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    Log.w(TAG, "[TX FAIL] Payload ${update.payloadId} failed with status=${update.status}")
                    deferred.complete(TransportResult.Failed(TransportType.NEARBY, "Transfer failed with status ${update.status}"))
                    pendingTransfers.remove(update.payloadId)
                    payloadIdToEndpoint.remove(update.payloadId)
                }
                PayloadTransferUpdate.Status.IN_PROGRESS -> {}
            }
        }
    }

    enum class EndpointHandshakeState {
        CONNECTED,
        HELLO_EXCHANGED,
        AUTH_OK_EXPECTED,
        READY
    }

    private val endpointHandshakeStates = ConcurrentHashMap<String, EndpointHandshakeState>()
    private val endpointExpectedAuthOk = ConcurrentHashMap<String, String>()
    private val pendingInviteIds = ConcurrentHashMap.newKeySet<String>()
    private val scannedInviteIds = ConcurrentHashMap.newKeySet<String>()

    fun registerPendingInvite(inviteId: String) {
        pendingInviteIds.add(inviteId)
    }

    fun unregisterPendingInvite(inviteId: String) {
        pendingInviteIds.remove(inviteId)
    }

    fun registerScannedInvite(inviteId: String) {
        scannedInviteIds.add(inviteId)
    }

    fun unregisterScannedInvite(inviteId: String) {
        scannedInviteIds.remove(inviteId)
    }

    fun bindInviteEndpoint(inviteId: String, endpointId: String) {
        val queueAddress = "invite-$inviteId"
        directRouteTable.bindQueue(queueAddress, endpointId)
        Log.i(TAG, "[BOOTSTRAP ROUTE] Explicitly bound $queueAddress -> endpoint $endpointId")
    }

    fun computeRelHint(relationshipId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest("torx-rel-hint-v1:$relationshipId".toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    fun computeInviteHint(inviteId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest("torx-invite-hint-v1:$inviteId".toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun buildHelloFeatures(): List<String> {
        val features = mutableListOf("direct-chat-v1", "fast-ack-v1")
        val activeConnections = connectionManager?.getAllActiveConnections() ?: emptyList()
        for (conn in activeConnections) {
            features.add("rel-hint:${computeRelHint(conn.relationshipId)}")
        }
        for (invId in pendingInviteIds) {
            features.add("invite-hint:${computeInviteHint(invId)}")
        }
        for (invId in scannedInviteIds) {
            features.add("invite-hint:${computeInviteHint(invId)}")
        }
        return features
    }

    val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            if (!autoConnectEnabled) {
                Log.w(TAG, "autoConnectNearby disabled; rejecting incoming connection from $endpointId")
                connectionsAdapter.rejectConnection(endpointId)
                return
            }
            Log.d(TAG, "Connection initiated from $endpointId (${connectionInfo.endpointName}), accepting")
            _healthState.value = TransportHealthState.CONNECTING
            connectionsAdapter.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                Log.i(TAG, "Connected to Nearby endpoint: $endpointId, beginning auth handshake")
                directRouteTable.registerEndpoint(endpointId)
                endpointHandshakeStates[endpointId] = EndpointHandshakeState.CONNECTED
                _healthState.value = TransportHealthState.AUTHENTICATING

                // Begin TorX connection handshake: Send HELLO with fresh challenge
                val challenge = ByteArray(16).apply { secureRandom.nextBytes(this) }
                localChallenges[endpointId] = challenge

                val hello = NearbyWireFrame.Control.Hello(
                    protocolVersion = NEARBY_PROTOCOL_VERSION,
                    peerTieBreaker = localTieBreaker,
                    supportedFeatures = buildHelloFeatures(),
                    maxFrameSize = MAX_DIRECT_FRAME_SIZE,
                    challenge = challenge
                )
                sendControlMessage(endpointId, hello)
                updateAvailability()
            } else {
                Log.w(TAG, "Connection failed to endpoint: $endpointId (${resolution.status.statusMessage})")
                cleanupEndpoint(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "Disconnected from Nearby endpoint: $endpointId")
            cleanupEndpoint(endpointId)
        }
    }

    val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val remoteName = info.endpointName
            Log.d(TAG, "Discovered endpoint $endpointId ($remoteName)")

            if (!autoConnectEnabled) {
                Log.d(TAG, "autoConnectNearby is disabled in settings; suppressing auto connection to $endpointId")
                return
            }

            // Deterministic collision arbitration:
            // Compare tie-breaker tokens to ensure only one peer initiates connection request
            val remoteTieBreaker = remoteName.removePrefix("TorX_").toLongOrNull(16)
            if (remoteTieBreaker != null) {
                if (localTieBreaker > remoteTieBreaker) {
                    Log.i(TAG, "[ARBITRATION] Local ($localTieBreaker) > Remote ($remoteTieBreaker): requesting connection to $endpointId")
                    connectionsAdapter.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
                } else {
                    Log.i(TAG, "[ARBITRATION] Local ($localTieBreaker) <= Remote ($remoteTieBreaker): waiting for remote peer to request")
                }
            } else {
                // Peer using non-standard naming format: request connection
                connectionsAdapter.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost Nearby endpoint: $endpointId")
        }
    }

    private fun handleHelloReceived(endpointId: String, hello: NearbyWireFrame.Control.Hello) {
        Log.d(TAG, "[HANDSHAKE] Received HELLO from $endpointId (v=${hello.protocolVersion}, maxFrame=${hello.maxFrameSize})")
        remoteChallenges[endpointId] = hello.challenge
        endpointHandshakeStates[endpointId] = EndpointHandshakeState.HELLO_EXCHANGED

        // If this node hasn't sent its challenge yet, send HELLO
        if (!localChallenges.containsKey(endpointId)) {
            val challenge = ByteArray(16).apply { secureRandom.nextBytes(this) }
            localChallenges[endpointId] = challenge
            val myHello = NearbyWireFrame.Control.Hello(
                protocolVersion = NEARBY_PROTOCOL_VERSION,
                peerTieBreaker = localTieBreaker,
                supportedFeatures = buildHelloFeatures(),
                maxFrameSize = MAX_DIRECT_FRAME_SIZE,
                challenge = challenge
            )
            sendControlMessage(endpointId, myHello)
        }

        // 1. Process invite hints for deterministic bootstrap route binding
        val remoteInviteHints = hello.supportedFeatures.filter { it.startsWith("invite-hint:") }.map { it.removePrefix("invite-hint:") }.toSet()
        val allLocalInvites = pendingInviteIds + scannedInviteIds
        for (invId in allLocalInvites) {
            val hint = computeInviteHint(invId)
            if (hint in remoteInviteHints || invId in remoteInviteHints) {
                bindInviteEndpoint(invId, endpointId)
            }
        }

        // 2. Prove active relationship capability:
        // Do not broadcast entire address book to unauthenticated peers
        val activeConnections = connectionManager?.getAllActiveConnections() ?: emptyList()
        val remoteRelHints = hello.supportedFeatures.filter { it.startsWith("rel-hint:") }.map { it.removePrefix("rel-hint:") }.toSet()

        val matchingConnections = if (remoteRelHints.isNotEmpty()) {
            activeConnections.filter { conn ->
                val hint = computeRelHint(conn.relationshipId)
                hint in remoteRelHints || conn.relationshipId in remoteRelHints || remoteRelHints.any { r -> conn.relationshipId.startsWith(r) }
            }
        } else {
            val boundRel = directRouteTable.getRelationshipForEndpoint(endpointId)
            if (boundRel != null) {
                activeConnections.filter { it.relationshipId == boundRel }
            } else if (activeConnections.size == 1) {
                activeConnections
            } else {
                emptyList()
            }
        }

        for (conn in matchingConnections) {
            val proofData = hello.challenge + "torx-nearby-auth-v1".toByteArray(Charsets.UTF_8)
            val proof = IdentityCrypto.hmacSha256(conn.sendAuth, proofData)
            val authProofMsg = NearbyWireFrame.Control.AuthProof(
                relationshipId = conn.relationshipId,
                proof = proof,
                sendQueueId = conn.sendQueueId,
                recvQueueId = conn.recvQueueId
            )
            endpointHandshakeStates[endpointId] = EndpointHandshakeState.AUTH_OK_EXPECTED
            endpointExpectedAuthOk[endpointId] = conn.relationshipId
            sendControlMessage(endpointId, authProofMsg)
        }
    }

    private fun handleAuthProofReceived(endpointId: String, proofMsg: NearbyWireFrame.Control.AuthProof) {
        val challenge = localChallenges[endpointId]
        if (challenge == null) {
            Log.w(TAG, "[AUTH FAIL] No local challenge found for endpoint $endpointId")
            connectionsAdapter.disconnectFromEndpoint(endpointId)
            cleanupEndpoint(endpointId)
            return
        }

        val conn = connectionManager?.getConnectionByRelationship(proofMsg.relationshipId)
        if (conn == null || conn.sendQueueId.isBlank() || conn.recvQueueId.isBlank()) {
            Log.w(TAG, "[AUTH] Unknown or invalid relationship ${proofMsg.relationshipId.take(8)} from $endpointId")
            connectionsAdapter.disconnectFromEndpoint(endpointId)
            cleanupEndpoint(endpointId)
            return
        }

        val proofData = challenge + "torx-nearby-auth-v1".toByteArray(Charsets.UTF_8)
        val expectedProof = IdentityCrypto.hmacSha256(conn.recvAuth, proofData)

        if (MessageDigest.isEqual(expectedProof, proofMsg.proof)) {
            Log.i(TAG, "[AUTH SUCCESS] Verified relationship ${conn.relationshipId.take(8)} with endpoint $endpointId")
            directRouteTable.bindRoute(
                relationshipId = conn.relationshipId,
                endpointId = endpointId,
                state = RouteState.READY,
                sendQueueId = conn.sendQueueId,
                recvQueueId = conn.recvQueueId
            )
            endpointHandshakeStates[endpointId] = EndpointHandshakeState.READY
            sendControlMessage(endpointId, NearbyWireFrame.Control.AuthOk(conn.relationshipId))
            _healthState.value = TransportHealthState.READY
            updateAvailability()

            // Reconnect recovery: trigger immediate retry so all queued messages flush
            agent?.triggerImmediateRetry()
        } else {
            Log.e(TAG, "[AUTH REJECT] Invalid capability proof for relationship ${conn.relationshipId.take(8)} from $endpointId")
            // Disconnect peer upon cryptographic authentication failure (M25)
            connectionsAdapter.disconnectFromEndpoint(endpointId)
            cleanupEndpoint(endpointId)
        }
    }

    private fun handleAuthOkReceived(endpointId: String, authOk: NearbyWireFrame.Control.AuthOk) {
        val expectedState = endpointHandshakeStates[endpointId]
        val expectedRel = endpointExpectedAuthOk[endpointId]
        if (expectedState != EndpointHandshakeState.AUTH_OK_EXPECTED || expectedRel != authOk.relationshipId) {
            Log.w(TAG, "[AUTH OK REJECT] Unexpected AUTH_OK from $endpointId for relationship ${authOk.relationshipId.take(8)} (state=$expectedState, expectedRel=$expectedRel)")
            connectionsAdapter.disconnectFromEndpoint(endpointId)
            cleanupEndpoint(endpointId)
            return
        }

        val conn = connectionManager?.getConnectionByRelationship(authOk.relationshipId)
        if (conn == null || conn.sendQueueId.isBlank() || conn.recvQueueId.isBlank()) {
            Log.w(TAG, "[AUTH OK REJECT] Missing or invalid connection for relationship ${authOk.relationshipId}")
            connectionsAdapter.disconnectFromEndpoint(endpointId)
            cleanupEndpoint(endpointId)
            return
        }

        Log.i(TAG, "[AUTH OK] Peer confirmed route for relationship ${authOk.relationshipId.take(8)}")
        directRouteTable.bindRoute(
            relationshipId = authOk.relationshipId,
            endpointId = endpointId,
            state = RouteState.READY,
            sendQueueId = conn.sendQueueId,
            recvQueueId = conn.recvQueueId
        )
        endpointHandshakeStates[endpointId] = EndpointHandshakeState.READY
        _healthState.value = TransportHealthState.READY
        updateAvailability()

        // Reconnect recovery: trigger immediate retry
        agent?.triggerImmediateRetry()
    }

    private fun sendControlMessage(endpointId: String, msg: NearbyWireFrame.Control) {
        val encoded = msg.encode()
        val payload = Payload.fromBytes(encoded)
        connectionsAdapter.sendPayload(endpointId, payload) { e ->
            Log.e(TAG, "Failed to send control message to $endpointId: ${e.message}")
        }
    }

    private fun cleanupEndpoint(endpointId: String) {
        directRouteTable.removeEndpoint(endpointId)
        localChallenges.remove(endpointId)
        remoteChallenges.remove(endpointId)
        endpointSemaphores.remove(endpointId)
        endpointHandshakeStates.remove(endpointId)
        endpointExpectedAuthOk.remove(endpointId)

        // Cancel all pending transfers to this endpoint
        val iterator = payloadIdToEndpoint.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value == endpointId) {
                pendingTransfers[entry.key]?.complete(
                    TransportResult.Failed(type, "Endpoint $endpointId disconnected during transfer")
                )
                pendingTransfers.remove(entry.key)
                iterator.remove()
            }
        }

        updateAvailability()
    }

    fun start() {
        Log.i(TAG, "Starting Nearby advertising and discovery (tieBreaker=${localTieBreaker.toString(16)})")
        _healthState.value = TransportHealthState.DISCOVERING
        connectionsAdapter.startAdvertising(localEndpointName, SERVICE_ID, connectionLifecycleCallback)
        connectionsAdapter.startDiscovery(SERVICE_ID, endpointDiscoveryCallback)
        startHeartbeat()
        updateAvailability()
    }

    fun stop() {
        Log.i(TAG, "Stopping Nearby transport")
        heartbeatJob?.cancel()
        connectionsAdapter.stopAdvertising()
        connectionsAdapter.stopDiscovery()
        connectionsAdapter.stopAllEndpoints()
        directRouteTable.clear()
        pendingTransfers.clear()
        payloadIdToEndpoint.clear()
        localChallenges.clear()
        remoteChallenges.clear()
        endpointSemaphores.clear()
        _healthState.value = TransportHealthState.DISCONNECTED
        updateAvailability()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                for (endpointId in directRouteTable.getAllEndpoints()) {
                    val lastSeen = directRouteTable.getLastSeen(endpointId)
                    val idleMs = now - lastSeen

                    if (idleMs > STALE_DISCONNECT_THRESHOLD_MS) {
                        Log.w(TAG, "[STALE] Endpoint $endpointId silent for ${idleMs}ms, disconnecting")
                        connectionsAdapter.disconnectFromEndpoint(endpointId)
                        cleanupEndpoint(endpointId)
                    } else if (idleMs > STALE_PING_THRESHOLD_MS) {
                        Log.d(TAG, "[HEARTBEAT] Pinging idle endpoint $endpointId")
                        sendControlMessage(endpointId, NearbyWireFrame.Control.Ping)
                    }
                }
            }
        }
    }

    override suspend fun send(
        destination: TransportDestination,
        payload: ByteArray
    ): TransportResult {
        // 1. Frame framing limits
        if (payload.size > MAX_DIRECT_FRAME_SIZE) {
            return TransportResult.Failed(
                type,
                "Payload size (${payload.size} bytes) exceeds MAX_DIRECT_FRAME_SIZE ($MAX_DIRECT_FRAME_SIZE bytes)"
            )
        }

        // 2. Exact peer routing via DirectRouteTable
        val targetEndpoint = if (destination.address.startsWith("invite-")) {
            // Explicit bootstrap route binding: must be explicitly bound to specific endpoint
            directRouteTable.getEndpointForQueue(destination.address)
        } else {
            val ep = directRouteTable.getEndpointForQueue(destination.address)
            if (ep != null && directRouteTable.isEndpointReady(ep)) {
                ep
            } else {
                null
            }
        }

        if (targetEndpoint == null) {
            val reason = if (destination.address.startsWith("invite-")) {
                "No bound bootstrap endpoint for ${destination.address.take(16)}"
            } else {
                "No authenticated READY route for destination ${destination.address.take(8)}"
            }
            return TransportResult.Failed(type, reason)
        }

        // 3. Bounded concurrency / Flow control backpressure
        val semaphore = getSemaphoreForEndpoint(targetEndpoint)
        val acquired = withTimeoutOrNull(5000L) {
            semaphore.acquire()
            true
        } ?: false

        if (!acquired) {
            return TransportResult.Failed(type, "Backpressure: endpoint $targetEndpoint send queue is full")
        }

        // Wrap payload in canonical NearbyWireFrame.Data
        val wireBytes = NearbyWireFrame.Data(payload).encode()
        val nearbyPayload = Payload.fromBytes(wireBytes)
        val deferred = CompletableDeferred<TransportResult>()

        pendingTransfers[nearbyPayload.id] = deferred
        payloadIdToEndpoint[nearbyPayload.id] = targetEndpoint

        try {
            connectionsAdapter.sendPayload(targetEndpoint, nearbyPayload) { e ->
                Log.e(TAG, "sendPayload call failed: ${e.message}")
                deferred.complete(TransportResult.Failed(type, e.message ?: "sendPayload failed"))
                pendingTransfers.remove(nearbyPayload.id)
                payloadIdToEndpoint.remove(nearbyPayload.id)
            }

            // Wait for PayloadTransferUpdate.Status.SUCCESS callback
            return withTimeout(TRANSFER_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingTransfers.remove(nearbyPayload.id)
            payloadIdToEndpoint.remove(nearbyPayload.id)
            return TransportResult.Failed(type, "Transfer timed out awaiting SUCCESS callback")
        } catch (e: Exception) {
            pendingTransfers.remove(nearbyPayload.id)
            payloadIdToEndpoint.remove(nearbyPayload.id)
            return TransportResult.Failed(type, e.message ?: "Send exception")
        } finally {
            semaphore.release()
        }
    }

    private fun updateAvailability() {
        val readyRoutes = directRouteTable.getAllReadyRoutes()
        _availability.value = if (readyRoutes.isNotEmpty()) {
            TransportAvailability.Available
        } else {
            TransportAvailability.Unavailable("No authenticated Nearby routes")
        }
    }
}
