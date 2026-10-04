package com.torxone.app.transport.tor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TorControlReadinessProbeTest {
    @Test fun ownedHaltWaitsForAcknowledgementAndClosedEndpointBeforeRestart() = runBlocking {
        val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val port = listener.localPort
        val sockets = CopyOnWriteArrayList<Socket>()
        val server = async(Dispatchers.IO) {
            listener.accept().use { socket ->
                socket.soTimeout = 2_000
                val input = socket.getInputStream().bufferedReader()
                val output = socket.getOutputStream()
                assertEquals("AUTHENTICATE", input.readLine())
                output.write("250 OK\r\n".toByteArray()); output.flush()
                assertEquals("SIGNAL HALT", input.readLine())
                output.write("250 OK\r\n".toByteArray()); output.flush()
                listener.close()
            }
        }
        val probe = TorControlReadinessProbe({ tcpConnection(port, sockets) }, 200)
        try {
            assertTrue(withTimeout(2_000) { probe.halt() })
            withTimeout(2_000) { server.await() }
            assertTrue(probe.endpointClosed())
            assertTrue(sockets.all { it.isClosed })
        } finally { listener.close(); server.cancel(); probe.cancelPending() }
    }

    @Test fun unresponsiveQueryIsNotProofOfDaemonShutdown() = runBlocking {
        val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val port = listener.localPort
        val server = async(Dispatchers.IO) {
            listener.accept().use { socket ->
                socket.soTimeout = 2_000
                assertEquals("AUTHENTICATE", socket.getInputStream().bufferedReader().readLine())
                assertEquals(-1, socket.getInputStream().read())
            }
            listener.accept().use { socket ->
                socket.soTimeout = 2_000
                assertEquals(-1, socket.getInputStream().read())
            }
        }
        val probe = TorControlReadinessProbe({ tcpConnection(port, CopyOnWriteArrayList()) }, 100)
        try {
            assertFalse(probe.bootstrapComplete())
            assertFalse(probe.endpointClosed())
            withTimeout(2_000) { server.await() }
            listener.close()
            assertTrue(probe.endpointClosed())
        } finally { listener.close(); server.cancel(); probe.cancelPending() }
    }

    @Test fun stalledQueryClosesOwnedConnectionAndNextQueryRecovers() = runBlocking {
        val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val sockets = CopyOnWriteArrayList<Socket>()
        val server = async(Dispatchers.IO) {
            listener.accept().use { socket ->
                socket.soTimeout = 2_000
                assertEquals("AUTHENTICATE", socket.getInputStream().bufferedReader().readLine())
                assertEquals(-1, socket.getInputStream().read())
            }
            listener.accept().use(::respondReady)
        }
        val probe = TorControlReadinessProbe({ tcpConnection(listener.localPort, sockets) }, 200)
        try {
            assertFalse(withTimeout(2_000) { probe.bootstrapComplete() })
            assertTrue(withTimeout(2_000) { probe.bootstrapComplete() })
            withTimeout(2_000) { server.await() }
            assertEquals(2, sockets.size)
            assertTrue(sockets.all { it.isClosed })
        } finally { probe.cancelPending(); listener.close(); server.cancel() }
    }

    @Test fun cancellationAndRestartCloseOldQueryWithoutPoisoningNewQuery() = runBlocking {
        val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val sockets = CopyOnWriteArrayList<Socket>()
        val entered = CompletableDeferred<Unit>()
        val server = async(Dispatchers.IO) {
            listener.accept().use { socket ->
                socket.soTimeout = 2_000
                assertEquals("AUTHENTICATE", socket.getInputStream().bufferedReader().readLine())
                entered.complete(Unit)
                assertEquals(-1, socket.getInputStream().read())
            }
            listener.accept().use(::respondReady)
        }
        val probe = TorControlReadinessProbe({ tcpConnection(listener.localPort, sockets) }, 5_000)
        val oldQuery = launch { probe.bootstrapComplete() }
        try {
            withTimeout(2_000) { entered.await() }
            withTimeout(1_000) { oldQuery.cancelAndJoin() }
            assertTrue(sockets.single().isClosed)
            assertTrue(withTimeout(2_000) { probe.bootstrapComplete() })
            withTimeout(2_000) { server.await() }
        } finally { oldQuery.cancel(); probe.cancelPending(); listener.close(); server.cancel() }
    }

    @Test fun explicitStopClosesQueryAndFreshProbeStillSucceeds() = runBlocking {
        val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val entered = CompletableDeferred<Unit>()
        val server = async(Dispatchers.IO) {
            listener.accept().use { socket ->
                socket.soTimeout = 2_000
                assertEquals("AUTHENTICATE", socket.getInputStream().bufferedReader().readLine())
                entered.complete(Unit)
                assertEquals(-1, socket.getInputStream().read())
            }
            listener.accept().use(::respondReady)
        }
        val probe = TorControlReadinessProbe({ tcpConnection(listener.localPort, CopyOnWriteArrayList()) }, 5_000)
        val oldQuery = async { probe.bootstrapComplete() }
        try {
            withTimeout(2_000) { entered.await() }
            probe.cancelPending()
            assertFalse(withTimeout(1_000) { oldQuery.await() })
            assertTrue(withTimeout(2_000) { probe.bootstrapComplete() })
            withTimeout(2_000) { server.await() }
        } finally { oldQuery.cancel(); probe.cancelPending(); listener.close(); server.cancel() }
    }

    @Test fun controlResponseMustAuthenticateCompleteAndContainExactProgress() = runBlocking {
        fun probe(response: String) = TorControlReadinessProbe({
            object : TorControlReadinessProbe.Connection {
                override fun connect() = Unit
                override val input = ByteArrayInputStream(response.toByteArray(Charsets.US_ASCII))
                override val output = ByteArrayOutputStream()
                override fun close() = Unit
            }
        })
        assertTrue(probe("250 OK\r\n250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done\r\n250 OK\r\n").bootstrapComplete())
        assertFalse(probe("250 OK\r\n250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=1000 TAG=done\r\n250 OK\r\n").bootstrapComplete())
        assertFalse(probe("515 Authentication failed\r\n").bootstrapComplete())
        assertFalse(probe("250 OK\r\n250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100\r\n").bootstrapComplete())
        assertFalse(probe("250 OK\r\n" + "x".repeat(4_097)).bootstrapComplete())
    }

    private fun tcpConnection(port: Int, sockets: MutableList<Socket>): TorControlReadinessProbe.Connection {
        val socket = Socket().also { sockets.add(it) }
        return object : TorControlReadinessProbe.Connection {
            override fun connect() { socket.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
            override val input get() = socket.getInputStream()
            override val output get() = socket.getOutputStream()
            override fun close() = socket.close()
        }
    }

    private fun respondReady(socket: Socket) {
        socket.soTimeout = 2_000
        val input = socket.getInputStream().bufferedReader()
        val output = socket.getOutputStream()
        assertEquals("AUTHENTICATE", input.readLine())
        output.write("250 OK\r\n".toByteArray(Charsets.US_ASCII)); output.flush()
        assertEquals("GETINFO status/bootstrap-phase", input.readLine())
        output.write("250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"\r\n250 OK\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }
}
