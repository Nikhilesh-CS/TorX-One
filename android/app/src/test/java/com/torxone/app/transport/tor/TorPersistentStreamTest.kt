package com.torxone.app.transport.tor

import com.torxone.app.protocol.ProtocolLimits
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.Socket

class TorPersistentStreamTest {
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
