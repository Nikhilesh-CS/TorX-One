package com.torxone.app.transport.tor

import com.torxone.app.protocol.ProtocolLimits
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.Socket

class TorPersistentStreamTest {
    @Test fun globalActiveLimitQueuesOtherRelationshipsWithoutSplittingTheirLanes() = runBlocking {
        val entered = java.util.concurrent.atomic.AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        val sockets = java.util.concurrent.CopyOnWriteArrayList<MemorySocket>()
        val manager = TorPeerConnectionManager(maxActive = 2, maxCallers = 8,
            socketFactory = { MemorySocket().also(sockets::add) }, connectSocket = { _, _ ->
                entered.incrementAndGet()
                assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
            })
        val sends = (1..4).map { peer -> async { manager.send("peer-$peer", route, 9050, onion, byteArrayOf(1)) } }
        try {
            withTimeout(1_000) { while (entered.get() < 2) delay(1) }
            delay(50)
            assertEquals(2, entered.get())
            assertEquals(2, sockets.size)
            release.countDown()
            withTimeout(2_000) { sends.awaitAll() }
            assertEquals(4, entered.get())
        } finally { release.countDown(); sends.forEach { it.cancel() }; manager.closeAll() }
    }

    @Test fun sameRelationshipWaiterDoesNotConsumeAnotherRelationshipsActivePermit() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val closed = java.util.concurrent.CountDownLatch(1)
        val dead = TorRoute("d".repeat(56) + ".onion")
        val manager = TorPeerConnectionManager(maxActive = 2, socketFactory = {
            object : Socket() {
                private var closedFlag = false
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun close() { closedFlag = true; closed.countDown() }
                override fun isClosed() = closedFlag
            }
        }, connectSocket = { _, target ->
            if (target == dead) { entered.complete(Unit); closed.await(); throw IOException("Closed") }
        })
        val stalled = launch { runCatching { manager.send("dead", dead, 9050, onion, byteArrayOf(1)) } }
        val same = launch(start = CoroutineStart.UNDISPATCHED) {
            entered.await()
            runCatching { manager.send("dead", dead, 9050, onion, byteArrayOf(2)) }
        }
        try {
            withTimeout(1_000) { entered.await() }
            withTimeout(1_000) { manager.send("healthy", route, 9050, onion, byteArrayOf(3)) }
            assertTrue(stalled.isActive)
            assertTrue(same.isActive)
        } finally { same.cancelAndJoin(); stalled.cancelAndJoin(); manager.closeAll() }
    }

    @Test fun slowSocketCloseNeverOwnsAnotherRelationshipsBookkeepingGuard() = runBlocking {
        val closeEntered = CompletableDeferred<Unit>()
        val releaseClose = java.util.concurrent.CountDownLatch(1)
        var created = 0
        val manager = TorPeerConnectionManager(socketFactory = {
            val index = created++
            object : Socket() {
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun close() {
                    if (index == 0) { closeEntered.complete(Unit); releaseClose.await() }
                }
            }
        }, connectSocket = { _, _ -> })
        manager.send("a", route, 9050, onion, byteArrayOf(1))
        manager.send("b", route, 9050, onion, byteArrayOf(2))
        val closing = launch(Dispatchers.IO) { manager.invalidate("a") }
        try {
            withTimeout(1_000) { closeEntered.await() }
            withTimeout(1_000) { manager.send("b", route, 9050, onion, byteArrayOf(3)) }
            assertEquals(2, created)
        } finally { releaseClose.countDown(); closing.join(); manager.closeAll() }
    }

    @Test fun realSocksNegotiationCancellationClosesProxyTcpImmediately() = runBlocking {
        val proxy = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val entered = CompletableDeferred<Unit>()
        val server = async(Dispatchers.IO) {
            proxy.accept().use { socket ->
                socket.soTimeout = 5000
                val input = DataInputStream(socket.getInputStream())
                assertEquals(5, input.readUnsignedByte())
                val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                entered.complete(Unit)
                // Do not reply to SOCKS negotiation. Cancellation must close this TCP stream.
                input.read()
            }
        }
        val manager = TorPeerConnectionManager()
        val sender = launch { manager.send("peer", route, proxy.localPort, onion, byteArrayOf(1)) }
        try {
            withTimeout(2000) { entered.await() }
            withTimeout(1000) { sender.cancelAndJoin() }
            assertEquals(-1, withTimeout(2000) { server.await() })
        } finally { sender.cancel(); manager.closeAll(); proxy.close() }
    }

    @Test fun defaultSocketConnectUsesNamedOnionBudget() = runBlocking {
        var actualTimeout = 0
        val socket = object : Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
                actualTimeout = timeout
            }
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        }
        val manager = TorPeerConnectionManager(socketFactory = { socket })
        try {
            manager.send("peer", route, 9050, onion, byteArrayOf(1))
            assertEquals(45_000, actualTimeout)
            assertEquals(45_000L, com.torxone.app.transport.DeliveryTimeouts.SOCKS_ONION_CONNECT_MS)
        } finally { manager.closeAll() }
    }

    @Test fun configuredConnectBudgetReachesActualSocket() = runBlocking {
        var actualTimeout = 0
        val socket = object : Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
                actualTimeout = timeout
            }
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        }
        val manager = TorPeerConnectionManager(socketFactory = { socket }, connectTimeoutMs = 5_000)
        try {
            manager.send("peer", route, 9050, onion, byteArrayOf(1))
            assertEquals(5_000, actualTimeout)
        } finally { manager.closeAll() }
    }

    @Test fun stalledConnectHasItsOwnSocketDeadline() = runBlocking {
        val closed = java.util.concurrent.CountDownLatch(1)
        val socket = object : Socket() {
            override fun close() { closed.countDown() }
            override fun isClosed() = closed.count == 0L
        }
        val manager = TorPeerConnectionManager(socketFactory = { socket }, connectTimeoutMs = 20,
            connectSocket = { _, _ ->
                assertTrue("Connection socket deadline must fire", closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
                throw IOException("Synthetic connect deadline")
            })
        try {
            try { manager.send("peer", route, 9050, onion, byteArrayOf(1)); fail("Expected deadline failure") }
            catch (_: IOException) { assertTrue(socket.isClosed) }
        } finally { manager.closeAll() }
    }
    @Test fun collidingRelationshipIdsAndManyPeersNeverWaitForHungPeer() = runBlocking {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val entered = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val deadRoute = TorRoute("d".repeat(56) + ".onion")
        val blockedSocket = java.util.concurrent.atomic.AtomicReference<Socket>()
        val manager = TorPeerConnectionManager(socketFactory = { object : Socket() {
            private var shut = false
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun close() { shut = true; if (blockedSocket.get() === this) closed.countDown() }
            override fun isClosed() = shut
        } }, connectSocket = { socket, target ->
            if (target == deadRoute) {
                blockedSocket.set(socket)
                entered.countDown()
                closed.await()
                throw IOException("Closed blocked socket")
            }
        })
        val stalled = launch { runCatching { manager.send("Aa", deadRoute, 9050, onion, byteArrayOf(1)) } }
        try {
            withTimeout(2000) { while (entered.count > 0) delay(1) }
            withTimeout(2000) {
                manager.send("BB", route, 9050, onion, byteArrayOf(2))
                (1..64).map { id -> async { manager.send("healthy-$id", route, 9050, onion, byteArrayOf(3)) } }.awaitAll()
            }
            assertTrue(stalled.isActive)
        } finally { stalled.cancelAndJoin(); manager.closeAll() }
        assertEquals(0, manager.retainedLaneCount())
    }

    @Test fun parentCancellationClosesNativeConnectImmediately() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val socket = object : Socket() {
            override fun close() { closed.countDown() }
            override fun isClosed() = closed.count == 0L
        }
        val manager = TorPeerConnectionManager(socketFactory = { socket }, connectSocket = { _, _ ->
            entered.countDown(); closed.await(); throw IOException("Cancelled")
        })
        val job = launch { manager.send("peer", route, 9050, onion, byteArrayOf(1)) }
        withTimeout(2000) { while (entered.count > 0) delay(1) }
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(socket.isClosed)
        assertEquals(0, manager.retainedLaneCount())
        manager.closeAll()
    }

    @Test fun invalidationAfterMissingAckReconnectsOnlyItsOwnPeer() = runBlocking {
        val sockets = java.util.concurrent.CopyOnWriteArrayList<MemorySocket>()
        val manager = TorPeerConnectionManager(socketFactory = { MemorySocket().also(sockets::add) }, connectSocket = { _, _ -> })
        try {
            manager.send("a", route, 9050, onion, byteArrayOf(1))
            manager.send("b", route, 9050, onion, byteArrayOf(2))
            manager.invalidate("a")
            assertTrue(sockets[0].isClosed); assertFalse(sockets[1].isClosed)
            manager.send("b", route, 9050, onion, byteArrayOf(3))
            assertEquals(2, sockets.size)
            manager.send("a", route, 9050, onion, byteArrayOf(4))
            assertEquals(3, sockets.size)
        } finally { manager.closeAll() }
    }

    @Test fun failedRemoteStreamIsRemovedAndNextAttemptReconnects() = runBlocking {
        var broken = false
        var count = 0
        val manager = TorPeerConnectionManager(socketFactory = {
            count++
            object : Socket() {
                override fun getOutputStream(): OutputStream = object : OutputStream() {
                    override fun write(value: Int) { if (broken) throw IOException("Remote closed") }
                }
            }
        }, connectSocket = { _, _ -> })
        try {
            manager.send("peer", route, 9050, onion, byteArrayOf(1))
            broken = true
            try { manager.send("peer", route, 9050, onion, byteArrayOf(2)); fail("Expected remote failure") }
            catch (_: IOException) {}
            assertEquals(0, manager.retainedLaneCount())
            broken = false
            manager.send("peer", route, 9050, onion, byteArrayOf(2))
            assertEquals(2, count)
        } finally { manager.closeAll() }
    }

    @Test fun laneChurnIsBoundedAndCleanupNeverSplitsActiveLock() = runBlocking {
        val manager = TorPeerConnectionManager(socketFactory = { MemorySocket() }, connectSocket = { _, _ -> }, maxPeers = 4)
        try {
            repeat(100) { manager.send("peer-$it", route, 9050, onion, byteArrayOf(1)) }
            assertTrue(manager.retainedLaneCount() <= 4)
            coroutineScope { (1..32).map { async { manager.send("same", route, 9050, onion, byteArrayOf(1)) } }.awaitAll() }
            assertTrue(manager.retainedLaneCount() <= 4)
        } finally { manager.closeAll() }
    }

    private val onion = "a".repeat(56) + ".onion"
    private val route = TorRoute("b".repeat(56) + ".onion")
    private class MemorySocket : Socket() {
        val bytes = ByteArrayOutputStream()
        private var closed = false
        override fun getOutputStream(): OutputStream = bytes
        override fun close() { closed = true }
        override fun isClosed(): Boolean = closed
    }

    @Test fun multipleMessagesReuseOneSocketAndOneHeader() = runBlocking {
        val sockets = mutableListOf<MemorySocket>()
        val manager = TorPeerConnectionManager(socketFactory = { MemorySocket().also(sockets::add) }, connectSocket = { _, _ -> })
        try {
            manager.send("relationship", route, 9050, onion, byteArrayOf(1, 2))
            manager.send("relationship", route, 9050, onion, byteArrayOf(3, 4))
            assertEquals(1, sockets.size)
            val input = DataInputStream(ByteArrayInputStream(sockets.single().bytes.toByteArray()))
            assertEquals(onion, TorStreamFraming.readHeader(input))
            assertArrayEquals(byteArrayOf(1, 2), TorStreamFraming.readFrame(input))
            assertArrayEquals(byteArrayOf(3, 4), TorStreamFraming.readFrame(input))
            assertNull(TorStreamFraming.readFrame(input))
        } finally { manager.closeAll() }
    }

    @Test fun concurrentWritesStayFramedAndUseOneSocket() = runBlocking {
        val sockets = mutableListOf<MemorySocket>()
        val manager = TorPeerConnectionManager(socketFactory = { MemorySocket().also(sockets::add) }, connectSocket = { _, _ -> })
        try {
            coroutineScope { (1..20).map { value -> async { manager.send("peer", route, 9050, onion, byteArrayOf(value.toByte())) } }.awaitAll() }
            assertEquals(1, sockets.size)
            val input = DataInputStream(ByteArrayInputStream(sockets.single().bytes.toByteArray()))
            TorStreamFraming.readHeader(input)
            val values = generateSequence { TorStreamFraming.readFrame(input)?.single()?.toInt() }.toSet()
            assertEquals((1..20).toSet(), values)
        } finally { manager.closeAll() }
    }

    @Test fun networkChangeClosesStreamAndNextSendReconnects() = runBlocking {
        val sockets = mutableListOf<MemorySocket>()
        val manager = TorPeerConnectionManager(socketFactory = { MemorySocket().also(sockets::add) }, connectSocket = { _, _ -> })
        manager.send("peer", route, 9050, onion, byteArrayOf(1))
        manager.closeAll()
        assertTrue(sockets.single().isClosed)
        try {
            manager.send("peer", route, 9050, onion, byteArrayOf(2))
            assertEquals(2, sockets.size)
        } finally { manager.closeAll() }
    }

    @Test fun idleAndChangedEndpointReconnect() = runBlocking {
        var now = 0L
        val sockets = mutableListOf<MemorySocket>()
        val manager = TorPeerConnectionManager(socketFactory = { MemorySocket().also(sockets::add) }, connectSocket = { _, _ -> }, clock = { now }, idleMs = 10)
        try {
            manager.send("peer", route, 9050, onion, byteArrayOf(1))
            now = 11
            manager.send("peer", route, 9050, onion, byteArrayOf(2))
            assertTrue(sockets.first().isClosed)
            manager.send("peer", TorRoute("c".repeat(56) + ".onion"), 9050, onion, byteArrayOf(3))
            assertEquals(3, sockets.size)
            assertTrue(sockets[1].isClosed)
        } finally { manager.closeAll() }
    }

    @Test fun poolEvictsOldestPeer() = runBlocking {
        val sockets = mutableListOf<MemorySocket>()
        val manager = TorPeerConnectionManager(socketFactory = { MemorySocket().also(sockets::add) }, connectSocket = { _, _ -> }, maxPeers = 1)
        try {
            manager.send("one", route, 9050, onion, byteArrayOf(1))
            manager.send("two", route, 9050, onion, byteArrayOf(2))
            assertTrue(sockets.first().isClosed)
            assertFalse(sockets.last().isClosed)
        } finally { manager.closeAll() }
    }

    @Test fun cleanEofAndLegacySingleFrameAreAccepted() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { TorStreamFraming.writeHeader(this, onion); TorStreamFraming.writeFrame(this, byteArrayOf(9)) }
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(onion, TorStreamFraming.readHeader(input))
        assertArrayEquals(byteArrayOf(9), TorStreamFraming.readFrame(input))
        assertNull(TorStreamFraming.readFrame(input))
    }

    @Test fun malformedLengthsAndTruncationAreRejected() {
        fun reject(bytes: ByteArray) {
            try { TorStreamFraming.readFrame(DataInputStream(ByteArrayInputStream(bytes))); fail("Malformed frame accepted") }
            catch (_: EOFException) {} catch (_: IllegalArgumentException) {}
        }
        listOf(0, -1, ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES + 1).forEach { length ->
            val bytes = ByteArrayOutputStream(); DataOutputStream(bytes).writeInt(length); reject(bytes.toByteArray())
        }
        reject(byteArrayOf(0, 0))
        reject(byteArrayOf(0, 0, 0, 2, 1))
    }

    @Test fun realTcpReceiverReadsFramesBeforeEof() = runBlocking {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val received = async(Dispatchers.IO) {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = DataInputStream(socket.getInputStream())
                assertEquals(onion, TorStreamFraming.readHeader(input))
                List(3) { TorStreamFraming.readFrame(input)!!.single().toInt() }
            }
        }
        val manager = TorPeerConnectionManager(socketFactory = { Socket() }, connectSocket = { socket, _ ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 5_000)
        })
        try {
            (1..3).forEach { manager.send("peer", route, 9050, onion, byteArrayOf(it.toByte())) }
            assertEquals(listOf(1, 2, 3), withTimeout(5_000) { received.await() })
        } finally { manager.closeAll(); server.close() }
    }

    @Test fun networkChangeDuringConnectCannotPublishStaleSocket() = runBlocking {
        val socket = MemorySocket()
        lateinit var manager: TorPeerConnectionManager
        manager = TorPeerConnectionManager(socketFactory = { socket }, connectSocket = { _, _ -> manager.closeAll() })
        try { manager.send("peer", route, 9050, onion, byteArrayOf(1)); fail("Stale socket accepted") }
        catch (_: IOException) {}
        assertTrue(socket.isClosed)
    }
}
