package com.torxone.app.transport.tor

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TorListenerAdmissionTest {
    @Test fun unauthenticatedFloodCannotConsumeAuthenticatedPoolOrChangeVerifiedIdentity() {
        val admission = TorListenerAdmission(capacity = 6, unauthenticatedCapacity = 2, perRelationshipCapacity = 2)
        val ticket = admission.start()
        val sockets = List(6) { Socket() }
        try {
            assertTrue(admission.register(ticket, sockets[0]))
            assertTrue(admission.authenticate(ticket, sockets[0], "peer-a"))
            assertFalse(admission.authenticate(ticket, sockets[0], "peer-b"))
            assertTrue(admission.register(ticket, sockets[1]))
            assertTrue(admission.register(ticket, sockets[2]))
            assertFalse(admission.register(ticket, sockets[3]))
            assertTrue(admission.authenticate(ticket, sockets[1], "peer-a"))
            assertTrue(admission.register(ticket, sockets[3]))
            assertFalse(admission.authenticate(ticket, sockets[2], "peer-a"))
            assertTrue(admission.authenticate(ticket, sockets[2], "peer-b"))
            assertTrue(admission.authenticate(ticket, sockets[3], "peer-b"))
            assertTrue(admission.register(ticket, sockets[4]))
            assertTrue(admission.register(ticket, sockets[5]))
            assertEquals(6, admission.snapshot().size)
            admission.release(sockets[0])
            assertTrue(admission.authenticate(ticket, sockets[4], "peer-a"))
            // A duplicate authentication callback does not count the stream twice.
            assertTrue(admission.authenticate(ticket, sockets[4], "peer-a"))
        } finally { admission.stop().forEach { it.close() }; sockets.forEach { it.close() } }
    }

    @Test fun stoppedSocketCannotClaimRelationshipInNewListenerGeneration() {
        val admission = TorListenerAdmission()
        val socket = Socket()
        val ticket = admission.start()
        try {
            assertTrue(admission.register(ticket, socket))
            admission.stop()
            admission.start()
            assertFalse(admission.authenticate(ticket, socket, "peer"))
            assertTrue(admission.snapshot().isEmpty())
        } finally { socket.close(); admission.stop() }
    }

    @Test fun shutdownRejectsSocketAcceptedBeforeStopButRegisteredAfterStop() = runBlocking {
        val admission = TorListenerAdmission()
        val ticket = admission.start()
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = Socket("127.0.0.1", listener.localPort)
        client.soTimeout = 1_000
        val accepted = CompletableDeferred<Unit>()
        val register = CompletableDeferred<Unit>()
        val handler = async(Dispatchers.IO) {
            val socket = listener.accept()
            try {
                accepted.complete(Unit)
                register.await()
                assertFalse(admission.register(ticket, socket))
            } finally { socket.close() }
        }
        try {
            withTimeout(1_000) { accepted.await() }
            assertTrue(admission.stop().isEmpty())
            listener.close()
            register.complete(Unit)
            withTimeout(1_000) { handler.await() }
            assertEquals(-1, client.getInputStream().read())
            assertFalse(admission.isOpen())
            assertTrue(admission.snapshot().isEmpty())
        } finally { register.complete(Unit); handler.cancel(); client.close(); listener.close() }
    }

    @Test fun stoppedGenerationCannotAdmitOrCloseNewGenerationClients() {
        val admission = TorListenerAdmission(1)
        val oldTicket = admission.start()
        val oldSocket = Socket()
        val freshSocket = Socket()
        val staleSocket = Socket()
        try {
            assertTrue(admission.register(oldTicket, oldSocket))
            val stoppedClients = admission.stop()
            assertEquals(listOf(oldSocket), stoppedClients)
            val freshTicket = admission.start()
            assertFalse(admission.register(oldTicket, staleSocket))
            assertTrue(admission.register(freshTicket, freshSocket))
            stoppedClients.forEach { it.close() }
            admission.release(oldSocket)
            assertTrue(oldSocket.isClosed)
            assertFalse(freshSocket.isClosed)
            assertEquals(listOf(freshSocket), admission.snapshot())
        } finally { admission.stop().forEach { it.close() }; oldSocket.close(); freshSocket.close(); staleSocket.close() }
    }
}
