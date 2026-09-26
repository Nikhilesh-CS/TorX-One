package com.torxone.app.transport.nearby

import com.google.android.gms.nearby.connection.*
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.OutboxStore
import com.torxone.app.agent.ProcessedEnvelopeStore
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.incoming.IncomingTransportHub
import com.torxone.app.transport.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class NearbyTransportTest {

    // ── Simulated in-memory network for testing NearbyTransport ──

    class TestNearbyNetwork {
        private val adapters = ConcurrentHashMap<String, SimulatedNearbyAdapter>()

        fun register(adapter: SimulatedNearbyAdapter) {
            adapters[adapter.endpointId] = adapter
        }

        fun unregister(endpointId: String) {
            adapters.remove(endpointId)
        }

        fun getAdapter(endpointId: String): SimulatedNearbyAdapter? = adapters[endpointId]

        fun broadcastDiscovery(finder: SimulatedNearbyAdapter) {
            for ((id, other) in adapters) {
                if (id != finder.endpointId && other.isAdvertising) {
                    val info = DiscoveredEndpointInfo(other.serviceId, other.endpointName)
                    finder.discoveryCallback?.onEndpointFound(id, info)
                }
            }
        }

        fun notifyNewAdvertiser(advertiser: SimulatedNearbyAdapter) {
            for ((id, other) in adapters) {
                if (id != advertiser.endpointId && other.isDiscovering) {
                    val info = DiscoveredEndpointInfo(advertiser.serviceId, advertiser.endpointName)
                    other.discoveryCallback?.onEndpointFound(advertiser.endpointId, info)
                }
            }
        }
    }

    class SimulatedNearbyAdapter(
        val endpointId: String,
        val network: TestNearbyNetwork
    ) : NearbyConnectionsAdapter {

        var endpointName: String = ""
        var serviceId: String = ""
        var isAdvertising = false
        var isDiscovering = false

        var lifecycleCallback: ConnectionLifecycleCallback? = null
        var discoveryCallback: EndpointDiscoveryCallback? = null
        var payloadCallback: PayloadCallback? = null

        val connectionRequests = ConcurrentHashMap<String, String>() // targetEndpointId -> requesterEndpointId
        val activeConnections = ConcurrentHashMap.newKeySet<String>()
        val acceptedPeers = ConcurrentHashMap.newKeySet<Pair<String, String>>()

        private val payloadCounter = AtomicLong(1000)

        init {
            network.register(this)
        }

        override fun startAdvertising(endpointName: String, serviceId: String, callback: ConnectionLifecycleCallback): Boolean {
            this.endpointName = endpointName
            this.serviceId = serviceId
            this.lifecycleCallback = callback
            this.isAdvertising = true
            network.notifyNewAdvertiser(this)
            return true
        }

        override fun startDiscovery(serviceId: String, callback: EndpointDiscoveryCallback): Boolean {
            this.serviceId = serviceId
            this.discoveryCallback = callback
            this.isDiscovering = true
            network.broadcastDiscovery(this)
            return true
        }

        override fun stopAdvertising() {
            isAdvertising = false
        }

        override fun stopDiscovery() {
            isDiscovering = false
        }

        override fun stopAllEndpoints() {
            for (peer in activeConnections) {
                disconnectFromEndpoint(peer)
            }
        }

        override fun requestConnection(endpointName: String, endpointId: String, callback: ConnectionLifecycleCallback): Boolean {
            this.lifecycleCallback = callback
            val target = network.getAdapter(endpointId) ?: return false
            connectionRequests[endpointId] = this.endpointId

            // In Nearby Connections, BOTH peers receive onConnectionInitiated
            val toTargetInfo = ConnectionInfo(endpointName, "1234", false)
            target.lifecycleCallback?.onConnectionInitiated(this.endpointId, toTargetInfo)

            val toSelfInfo = ConnectionInfo(target.endpointName, "1234", true)
            this.lifecycleCallback?.onConnectionInitiated(endpointId, toSelfInfo)
            return true
        }

        override fun acceptConnection(endpointId: String, callback: PayloadCallback): Boolean {
            this.payloadCallback = callback
            val target = network.getAdapter(endpointId) ?: return false

            acceptedPeers.add(this.endpointId to endpointId)

            // When BOTH peers have accepted the connection, complete it
            if (target.acceptedPeers.contains(endpointId to this.endpointId)) {
                activeConnections.add(endpointId)
                target.activeConnections.add(this.endpointId)

                val resolution = ConnectionResolution(com.google.android.gms.common.api.Status.RESULT_SUCCESS)
                this.lifecycleCallback?.onConnectionResult(endpointId, resolution)
                target.lifecycleCallback?.onConnectionResult(this.endpointId, resolution)
            }
            return true
        }

        override fun rejectConnection(endpointId: String): Boolean {
            val target = network.getAdapter(endpointId) ?: return false
            val resolution = ConnectionResolution(com.google.android.gms.common.api.Status.RESULT_CANCELED)
            target.lifecycleCallback?.onConnectionResult(this.endpointId, resolution)
            return true
        }

        override fun disconnectFromEndpoint(endpointId: String) {
            activeConnections.remove(endpointId)
            acceptedPeers.remove(this.endpointId to endpointId)
            this.lifecycleCallback?.onDisconnected(endpointId)

            val target = network.getAdapter(endpointId)
            target?.activeConnections?.remove(this.endpointId)
            target?.acceptedPeers?.remove(endpointId to this.endpointId)
            target?.lifecycleCallback?.onDisconnected(this.endpointId)
        }

        override fun sendPayload(endpointId: String, payload: Payload, onFailure: (Exception) -> Unit) {
            val target = network.getAdapter(endpointId)
            if (target == null || !activeConnections.contains(endpointId)) {
                onFailure(IllegalStateException("Endpoint $endpointId not connected"))
                return
            }

            // Deliver payload to target
            target.payloadCallback?.onPayloadReceived(this.endpointId, payload)

            // Notify sender of success
            val update = PayloadTransferUpdate.Builder()
                .setPayloadId(payload.id)
                .setStatus(PayloadTransferUpdate.Status.SUCCESS)
                .build()
            this.payloadCallback?.onPayloadTransferUpdate(endpointId, update)
        }
    }

    class DummyContext : android.content.ContextWrapper(null)

    class InMemoryOutbox : OutboxStore {
        val items = ConcurrentHashMap<String, DeliveryItem>()
        override suspend fun insert(item: DeliveryItem) { items[item.deliveryId] = item }
        override suspend fun getPendingItems(): List<DeliveryItem> = items.values.toList()
        override suspend fun updateStatus(deliveryId: String, status: DeliveryStatus) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(status = status) }
        }
        override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(attemptCount = attemptCount, nextAttemptAt = nextAttemptAt) }
        }
        override suspend fun removeByMessageId(logicalMessageId: String) {
            val key = items.values.find { it.logicalMessageId == logicalMessageId }?.deliveryId
            if (key != null) items.remove(key)
        }
    }

    class InMemoryProcessed : ProcessedEnvelopeStore {
        val processed = ConcurrentHashMap.newKeySet<String>()
        override suspend fun isProcessed(envelopeId: String): Boolean = processed.contains(envelopeId)
        override suspend fun isMessageProcessed(logicalMessageId: String): Boolean = false
        override suspend fun markProcessed(record: com.torxone.app.agent.ProcessedEnvelope) { processed.add(record.envelopeId) }
    }

    // ── Tests ──

    @Test
    fun testWireFramingCodec() {
        // 1. Data frame
        val dataBytes = "Hello TorX!".toByteArray()
        val dataFrame = NearbyWireFrame.Data(dataBytes)
        val encodedData = dataFrame.encode()
        val decodedData = NearbyWireFrame.decode(encodedData)
        assertTrue(decodedData is NearbyWireFrame.Data)
        assertArrayEquals(dataBytes, (decodedData as NearbyWireFrame.Data).payload)

        // 2. Hello control frame
        val challenge = ByteArray(16) { 0x42.toByte() }
        val helloFrame = NearbyWireFrame.Control.Hello(
            protocolVersion = 1,
            peerTieBreaker = 987654321L,
            supportedFeatures = listOf("chat", "ack"),
            maxFrameSize = 65536,
            challenge = challenge
        )
        val encodedHello = helloFrame.encode()
        val decodedHello = NearbyWireFrame.decode(encodedHello)
        assertTrue(decodedHello is NearbyWireFrame.Control.Hello)
        val hello = decodedHello as NearbyWireFrame.Control.Hello
        assertEquals(1, hello.protocolVersion)
        assertEquals(987654321L, hello.peerTieBreaker)
        assertEquals(listOf("chat", "ack"), hello.supportedFeatures)
        assertEquals(65536, hello.maxFrameSize)
        assertArrayEquals(challenge, hello.challenge)

        // 3. AuthProof frame
        val proof = ByteArray(32) { 0xAA.toByte() }
        val authProof = NearbyWireFrame.Control.AuthProof(
            relationshipId = "rel-123",
            proof = proof,
            sendQueueId = "queue-a2b",
            recvQueueId = "queue-b2a"
        )
        val decodedProof = NearbyWireFrame.decode(authProof.encode()) as NearbyWireFrame.Control.AuthProof
        assertEquals("rel-123", decodedProof.relationshipId)
        assertArrayEquals(proof, decodedProof.proof)
        assertEquals("queue-a2b", decodedProof.sendQueueId)
        assertEquals("queue-b2a", decodedProof.recvQueueId)

        // 4. AuthOk frame
        val authOk = NearbyWireFrame.Control.AuthOk("rel-123")
        val decodedOk = NearbyWireFrame.decode(authOk.encode()) as NearbyWireFrame.Control.AuthOk
        assertEquals("rel-123", decodedOk.relationshipId)

        // 5. Ping & Pong
        val ping = NearbyWireFrame.Control.Ping
        val decodedPing = NearbyWireFrame.decode(ping.encode())
        assertTrue(decodedPing is NearbyWireFrame.Control.Ping)

        val pong = NearbyWireFrame.Control.Pong
        val decodedPong = NearbyWireFrame.decode(pong.encode())
        assertTrue(decodedPong is NearbyWireFrame.Control.Pong)

        // 6. Backward compatibility fallback
        val rawUnknownBytes = byteArrayOf(0x99.toByte(), 0x88.toByte(), 0x77.toByte())
        val decodedFallback = NearbyWireFrame.decode(rawUnknownBytes)
        assertTrue(decodedFallback is NearbyWireFrame.Data)
        assertArrayEquals(rawUnknownBytes, (decodedFallback as NearbyWireFrame.Data).payload)
    }

    @Test
    fun testDirectRouteTableLifecycle() {
        val table = DirectRouteTable()

        table.bindRoute(
            relationshipId = "rel-1",
            endpointId = "ep-bob",
            state = RouteState.AUTHENTICATING,
            sendQueueId = "send-q1",
            recvQueueId = "recv-q1"
        )

        assertEquals("ep-bob", table.getEndpointForQueue("send-q1"))
        assertEquals("ep-bob", table.getEndpointForQueue("recv-q1"))
        assertEquals("rel-1", table.getRelationshipForEndpoint("ep-bob"))
        assertFalse("Route is not ready yet", table.isReady("rel-1"))

        // Transition to READY
        table.bindRoute(
            relationshipId = "rel-1",
            endpointId = "ep-bob",
            state = RouteState.READY,
            sendQueueId = "send-q1",
            recvQueueId = "recv-q1"
        )
        assertTrue(table.isReady("rel-1"))
        assertTrue(table.isEndpointReady("ep-bob"))
        assertEquals(1, table.getAllReadyRoutes().size)

        // Disconnect endpoint
        val affected = table.removeEndpoint("ep-bob")
        assertEquals(setOf("rel-1"), affected)
        assertNull(table.getEndpointForQueue("send-q1"))
        assertNull(table.getRouteByRelationship("rel-1"))
        assertFalse(table.isReady("rel-1"))
    }

    @Test
    fun testFrameSizeLimitEnforcement() = runBlocking {
        val network = TestNearbyNetwork()
        val adapter = SimulatedNearbyAdapter("ep-alice", network)
        val transport = NearbyTransport(
            context = DummyContext(),
            incomingTransportHub = IncomingTransportHub(),
            adapter = adapter
        )

        // Bind dummy route
        transport.directRouteTable.bindRoute("rel-1", "ep-bob", RouteState.READY, sendQueueId = "q-target")

        // Oversized payload (> 64KB)
        val oversizedPayload = ByteArray(MAX_DIRECT_FRAME_SIZE + 10)
        val result = transport.send(TransportDestination("q-target"), oversizedPayload)
        assertTrue(result is TransportResult.Failed)
        assertTrue((result as TransportResult.Failed).error.contains("MAX_DIRECT_FRAME_SIZE"))
    }

    @Test
    fun testDeterministicCollisionArbitration() {
        val network = TestNearbyNetwork()
        val adapterAlice = SimulatedNearbyAdapter("ep-alice", network)
        val adapterBob = SimulatedNearbyAdapter("ep-bob", network)

        val transportAlice = NearbyTransport(
            context = DummyContext(),
            incomingTransportHub = IncomingTransportHub(),
            customEndpointName = "TorX_100", // Tie breaker = 0x100 = 256
            adapter = adapterAlice
        )
        val transportBob = NearbyTransport(
            context = DummyContext(),
            incomingTransportHub = IncomingTransportHub(),
            customEndpointName = "TorX_050", // Tie breaker = 0x050 = 80
            adapter = adapterBob
        )

        // Alice starts advertising & discovering
        adapterAlice.startAdvertising("TorX_100", "com.torxone.mesh", transportAlice.connectionLifecycleCallback)
        // Bob starts advertising & discovering
        adapterBob.startAdvertising("TorX_050", "com.torxone.mesh", transportBob.connectionLifecycleCallback)

        // 1. Bob discovers Alice: Bob's tie breaker (80) <= Alice's (256), so Bob WAITS and does not request
        transportBob.endpointDiscoveryCallback.onEndpointFound("ep-alice", DiscoveredEndpointInfo("com.torxone.mesh", "TorX_100"))
        assertTrue("Bob must not request connection because Alice has higher tie-breaker", adapterBob.connectionRequests.isEmpty())

        // 2. Alice discovers Bob: Alice's tie breaker (256) > Bob's (80), so Alice REQUESTS connection
        transportAlice.endpointDiscoveryCallback.onEndpointFound("ep-bob", DiscoveredEndpointInfo("com.torxone.mesh", "TorX_050"))
        assertEquals("ep-bob", adapterAlice.connectionRequests.keys.firstOrNull())
    }

    @Test
    fun testFullNearbyConnectionAndAuthHandshake() = runBlocking {
        val network = TestNearbyNetwork()
        val adapterAlice = SimulatedNearbyAdapter("ep-alice", network)
        val adapterBob = SimulatedNearbyAdapter("ep-bob", network)

        val connManagerAlice = ConnectionManager()
        val connManagerBob = ConnectionManager()

        val relationshipId = "rel-alice-bob"
        val aToSendAuth = "secret-a2b-auth".toByteArray()
        val bToSendAuth = "secret-b2a-auth".toByteArray()

        connManagerAlice.registerConnection(
            Connection(
                relationshipId = relationshipId,
                generation = 1,
                sendQueueId = "q-a2b",
                recvQueueId = "q-b2a",
                sendAuth = aToSendAuth,
                recvAuth = bToSendAuth
            )
        )
        connManagerBob.registerConnection(
            Connection(
                relationshipId = relationshipId,
                generation = 1,
                sendQueueId = "q-b2a",
                recvQueueId = "q-a2b",
                sendAuth = bToSendAuth,
                recvAuth = aToSendAuth
            )
        )

        val receivedAlice = ConcurrentHashMap.newKeySet<String>()
        val receivedBob = ConcurrentHashMap.newKeySet<String>()

        val hubAlice = object : IncomingTransportHub() {
            override suspend fun onRawFrameReceived(rawBytes: ByteArray, transportType: TransportType): Boolean {
                receivedAlice.add(String(rawBytes))
                return true
            }
        }

        val hubBob = object : IncomingTransportHub() {
            override suspend fun onRawFrameReceived(rawBytes: ByteArray, transportType: TransportType): Boolean {
                receivedBob.add(String(rawBytes))
                return true
            }
        }

        val transportAlice = NearbyTransport(
            context = DummyContext(),
            incomingTransportHub = hubAlice,
            connectionManager = connManagerAlice,
            customEndpointName = "TorX_200",
            adapter = adapterAlice
        )
        val transportBob = NearbyTransport(
            context = DummyContext(),
            incomingTransportHub = hubBob,
            connectionManager = connManagerBob,
            customEndpointName = "TorX_100",
            adapter = adapterBob
        )

        transportAlice.start()
        transportBob.start()

        // Wait for handshake to authenticate route
        var attempts = 0
        while ((!transportAlice.directRouteTable.isReady(relationshipId) ||
                !transportBob.directRouteTable.isReady(relationshipId)) && attempts < 50) {
            delay(50)
            attempts++
        }

        assertTrue("Alice route must be authenticated and READY", transportAlice.directRouteTable.isReady(relationshipId))
        assertTrue("Bob route must be authenticated and READY", transportBob.directRouteTable.isReady(relationshipId))

        // Send user data from Alice to Bob
        val sendResult = transportAlice.send(TransportDestination("q-a2b"), "Hello from Alice!".toByteArray())
        assertTrue("Send from Alice must be Accepted", sendResult is TransportResult.Accepted)
        var waitBob = 0
        while (!receivedBob.contains("Hello from Alice!") && waitBob < 50) {
            delay(20)
            waitBob++
        }
        assertTrue("Bob must receive Alice's frame", receivedBob.contains("Hello from Alice!"))

        // Send user data from Bob to Alice
        val bobSendResult = transportBob.send(TransportDestination("q-b2a"), "Hello back from Bob!".toByteArray())
        assertTrue("Send from Bob must be Accepted", bobSendResult is TransportResult.Accepted)
        var waitAlice = 0
        while (!receivedAlice.contains("Hello back from Bob!") && waitAlice < 50) {
            delay(20)
            waitAlice++
        }
        assertTrue("Alice must receive Bob's frame", receivedAlice.contains("Hello back from Bob!"))

        transportAlice.stop()
        transportBob.stop()
    }

    @Test
    fun testMultiPeerRoutingIsolation() = runBlocking {
        val network = TestNearbyNetwork()
        val adapterAlice = SimulatedNearbyAdapter("ep-alice", network)
        val adapterBob = SimulatedNearbyAdapter("ep-bob", network)
        val adapterCharlie = SimulatedNearbyAdapter("ep-charlie", network)

        val connManagerAlice = ConnectionManager()
        val relBob = "rel-alice-bob"
        val relCharlie = "rel-alice-charlie"

        connManagerAlice.registerConnection(
            Connection(
                relationshipId = relBob,
                generation = 1,
                sendQueueId = "q-bob-send",
                recvQueueId = "q-bob-recv",
                sendAuth = "bAuth".toByteArray(),
                recvAuth = "bAuth".toByteArray()
            )
        )
        connManagerAlice.registerConnection(
            Connection(
                relationshipId = relCharlie,
                generation = 1,
                sendQueueId = "q-charlie-send",
                recvQueueId = "q-charlie-recv",
                sendAuth = "cAuth".toByteArray(),
                recvAuth = "cAuth".toByteArray()
            )
        )

        val bobReceived = mutableListOf<String>()
        val charlieReceived = mutableListOf<String>()

        adapterBob.payloadCallback = object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                val frame = NearbyWireFrame.decode(payload.asBytes()!!)
                if (frame is NearbyWireFrame.Data) bobReceived.add(String(frame.payload))
            }
            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
        }

        adapterCharlie.payloadCallback = object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                val frame = NearbyWireFrame.decode(payload.asBytes()!!)
                if (frame is NearbyWireFrame.Data) charlieReceived.add(String(frame.payload))
            }
            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
        }

        val transportAlice = NearbyTransport(
            context = DummyContext(),
            incomingTransportHub = IncomingTransportHub(),
            connectionManager = connManagerAlice,
            adapter = adapterAlice
        )
        adapterAlice.payloadCallback = transportAlice.payloadCallback

        // Connect endpoints
        adapterAlice.activeConnections.add("ep-bob")
        adapterAlice.activeConnections.add("ep-charlie")
        adapterBob.activeConnections.add("ep-alice")
        adapterCharlie.activeConnections.add("ep-alice")

        // Bind routes in Alice's DirectRouteTable
        transportAlice.directRouteTable.bindRoute(relBob, "ep-bob", RouteState.READY, sendQueueId = "q-bob-send")
        transportAlice.directRouteTable.bindRoute(relCharlie, "ep-charlie", RouteState.READY, sendQueueId = "q-charlie-send")

        // Alice sends message to Bob
        transportAlice.send(TransportDestination("q-bob-send"), "For Bob only".toByteArray())
        assertEquals(listOf("For Bob only"), bobReceived)
        assertTrue("Charlie must not receive Bob's message", charlieReceived.isEmpty())

        // Alice sends message to Charlie
        transportAlice.send(TransportDestination("q-charlie-send"), "For Charlie only".toByteArray())
        assertEquals(listOf("For Charlie only"), charlieReceived)
        assertEquals(listOf("For Bob only"), bobReceived)
    }

    @Test
    fun testDisconnectAndReconnectOutboxRecovery() = runBlocking {
        val network = TestNearbyNetwork()
        val adapterAlice = SimulatedNearbyAdapter("ep-alice", network)
        val adapterBob = SimulatedNearbyAdapter("ep-bob", network)

        val connManagerAlice = ConnectionManager()
        val relationshipId = "rel-reconnect-test"
        connManagerAlice.registerConnection(
            Connection(
                relationshipId = relationshipId,
                generation = 1,
                sendQueueId = "q-send",
                recvQueueId = "q-recv",
                sendAuth = "auth".toByteArray(),
                recvAuth = "auth".toByteArray()
            )
        )

        val outbox = InMemoryOutbox()
        val processed = InMemoryProcessed()
        val agent = TorXAgent(
            transportRouter = TransportRouter(),
            outboxStore = outbox,
            processedStore = processed,
            coroutineDispatcher = Dispatchers.Default
        )

        val transportAlice = NearbyTransport(
            context = DummyContext(),
            incomingTransportHub = IncomingTransportHub(),
            connectionManager = connManagerAlice,
            agent = agent,
            adapter = adapterAlice
        )

        // 1. Initial connection & route
        adapterAlice.activeConnections.add("ep-bob")
        transportAlice.directRouteTable.bindRoute(relationshipId, "ep-bob", RouteState.READY, sendQueueId = "q-send")
        assertTrue(transportAlice.directRouteTable.isReady(relationshipId))

        // 2. Disconnect Bob
        transportAlice.connectionLifecycleCallback.onDisconnected("ep-bob")
        assertFalse("Route must be unavailable after disconnect", transportAlice.directRouteTable.isReady(relationshipId))

        // Sending while offline fails gracefully
        val offlineSend = transportAlice.send(TransportDestination("q-send"), "Offline message".toByteArray())
        assertTrue(offlineSend is TransportResult.Failed)

        // 3. Reconnect Bob
        adapterAlice.activeConnections.add("ep-bob")
        transportAlice.connectionLifecycleCallback.onConnectionResult(
            "ep-bob",
            ConnectionResolution(com.google.android.gms.common.api.Status.RESULT_SUCCESS)
        )

        // Simulate Hello from Bob requesting relationship reconnection
        val bobHello = NearbyWireFrame.Control.Hello(
            protocolVersion = 1,
            peerTieBreaker = 999L,
            supportedFeatures = listOf("rel-hint:$relationshipId"),
            maxFrameSize = 65536,
            challenge = ByteArray(16)
        )
        transportAlice.payloadCallback.onPayloadReceived(
            "ep-bob",
            Payload.fromBytes(bobHello.encode())
        )

        // Simulate AuthOk received for reconnected peer
        transportAlice.payloadCallback.onPayloadReceived(
            "ep-bob",
            Payload.fromBytes(NearbyWireFrame.Control.AuthOk(relationshipId).encode())
        )

        // Verify route restored to READY
        assertTrue("Route must be READY again after reconnect", transportAlice.directRouteTable.isReady(relationshipId))
    }
}
