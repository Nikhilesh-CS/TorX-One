package com.torxone.app.transport.tor

import android.content.ContextWrapper
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.NoOpKeyProtector
import com.torxone.app.incoming.IncomingTransportHub
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OnionEndpointRecoveryTest {
    @Test fun acceptLoopFailureRebindsSamePortWithoutReplacingOnionIdentity() = runBlocking {
        val directory = Files.createTempDirectory("tor-listener-recovery").toFile()
        val hostname = directory.resolve("hostname").apply { writeText("a".repeat(56) + ".onion") }
        val key = directory.resolve("hs_ed25519_secret_key").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val servers = CopyOnWriteArrayList<ServerSocket>()
        val manager = manager(directory, IncomingTransportHub()) { port ->
            ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32)
                servers += this
            }
        }
        try {
            val port = manager.start()
            withTimeout(1_000) { while (!manager.isListening()) delay(1) }
            servers.single().close()
            withTimeout(3_000) { while (servers.size < 2 || !manager.isListening()) delay(10) }
            assertEquals(port, manager.localPort)
            assertEquals(port, servers.last().localPort)
            assertEquals("a".repeat(56) + ".onion", manager.onionAddress())
            assertArrayEquals(byteArrayOf(7, 8, 9), key.readBytes())
            manager.stop()
            assertFalse(manager.isListening())
            delay(1_100)
            assertEquals("Stop must invalidate scheduled recovery", 2, servers.size)
        } finally {
            manager.stop(); servers.forEach { it.close() }
            hostname.delete(); key.delete(); directory.delete()
        }
    }

    @Test fun unauthenticatedHeaderCannotClaimIdentityAndVerifiedIdentityCannotSwitch() = runBlocking {
        val directory = Files.createTempDirectory("tor-listener-authentication").toFile()
        val firstFrame = CompletableDeferred<Unit>()
        var callbacks = 0
        val hub = object : IncomingTransportHub() {
            override suspend fun onRawTorFrameReceived(rawBytes: ByteArray, onAuthenticatedRelationship: (String) -> Boolean): Boolean {
                val accepted = onAuthenticatedRelationship(if (++callbacks == 1) "verified-a" else "verified-b")
                if (callbacks == 1) firstFrame.complete(Unit)
                return accepted
            }
        }
        val manager = manager(directory, hub)
        val port = manager.start()
        val client = Socket("127.0.0.1", port)
        client.soTimeout = 1_000
        try {
            val output = DataOutputStream(client.getOutputStream())
            // A forged header has no effect on the verified identity quota.
            TorStreamFraming.writeHeader(output, "b".repeat(56) + ".onion")
            TorStreamFraming.writeFrame(output, byteArrayOf(1))
            withTimeout(1_000) { firstFrame.await() }
            TorStreamFraming.writeFrame(output, byteArrayOf(2))
            assertEquals(-1, withContext(Dispatchers.IO) { client.getInputStream().read() })
            assertEquals(2, callbacks)
        } finally { client.close(); manager.stop(); directory.delete() }
    }

    private fun manager(directory: java.io.File, hub: IncomingTransportHub, factory: ((Int) -> ServerSocket)? = null): OnionEndpointManager {
        val context = object : ContextWrapper(null) {
            override fun getDir(name: String, mode: Int): java.io.File = directory
        }
        return OnionEndpointManager(context, hub, TorRouteManager(), ConnectionManager(keyProtector = NoOpKeyProtector()), serverFactory = factory)
    }
}
