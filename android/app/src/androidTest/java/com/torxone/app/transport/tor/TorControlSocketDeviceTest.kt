package com.torxone.app.transport.tor

import android.content.pm.ApplicationInfo
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Debug-only adapter regressions. Every filesystem socket belongs to an isolated fixture. */
@RunWith(AndroidJUnit4::class)
class TorControlSocketDeviceTest {
    @Before fun requireDebugTarget() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo
        assumeTrue("Adapter regression tests require a debug target", (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }

    @Test(timeout = 10_000) fun missingEndpointIsClosedAndOnlyItsFailedConnectProvesAbsence(): Unit = runBlocking {
        SocketFixture(listen = false).use { fixture ->
            val connection = AndroidTorControlConnection(fixture.endpoint.path)
            connection.use {
                val error = connectFailure(connection)
                assertTrue(connection.endpointUnavailable(error))
                assertFalse(connection.endpointUnavailable(IOException(Os.strerror(OsConstants.ENOENT))))
            }
            assertTrue(probe(fixture).endpointClosed())
        }
    }

    @Test(timeout = 10_000) fun closedFilesystemListenerIsRefusedAndCountsAsClosed(): Unit = runBlocking {
        SocketFixture().use { fixture ->
            fixture.closeListener()
            assertTrue("Closing a filesystem listener must retain the refused endpoint", fixture.endpoint.exists())
            AndroidTorControlConnection(fixture.endpoint.path).use { connection ->
                val error = connectFailure(connection)
                assertTrue(connection.endpointUnavailable(error))
                assertFalse(connection.endpointUnavailable(IOException(Os.strerror(OsConstants.ECONNREFUSED))))
            }
            assertTrue(probe(fixture).endpointClosed())
        }
    }

    @Test(timeout = 10_000) fun acceptedEndpointDoesNotCountAsClosed(): Unit = runBlocking {
        SocketFixture().use { fixture ->
            val server = fixture.serve { it.inputStream.read() }
            assertFalse(probe(fixture).endpointClosed())
            assertEquals(-1, server.get(2, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 10_000) fun connectedControlSocketAuthenticatesAndReadsCompletedBootstrap(): Unit = runBlocking {
        SocketFixture().use { fixture ->
            val server = fixture.serve { socket ->
                assertEquals("AUTHENTICATE", readLine(socket))
                respond(socket, "250 OK\r\n")
                assertEquals("GETINFO status/bootstrap-phase", readLine(socket))
                respond(socket, "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"\r\n250 OK\r\n")
            }
            assertTrue(probe(fixture).bootstrapComplete())
            server.get(2, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 10_000) fun stalledAuthenticationReturnsFalseAndOwnerClosesTheSocket(): Unit = runBlocking {
        assertStallCloses(authenticated = false)
    }

    @Test(timeout = 10_000) fun stalledBootstrapReturnsFalseAndOwnerClosesTheSocket(): Unit = runBlocking {
        assertStallCloses(authenticated = true)
    }

    @Test(timeout = 10_000) fun saturatedBacklogConnectIsBoundedAndNeverProvesEndpointClosed(): Unit = runBlocking {
        SocketFixture().use { fixture ->
            fixture.saturateWithoutAccepting()
            assertEquals(0, fixture.acceptCount.get())
            val bounded = TorControlReadinessProbe({ AndroidTorControlConnection(fixture.endpoint.path, 300) }, 300)
            var started = SystemClock.elapsedRealtime()
            assertFalse(bounded.bootstrapComplete())
            assertTrue("Full-backlog bootstrap connect exceeded deadline", SystemClock.elapsedRealtime() - started < 3_000)
            started = SystemClock.elapsedRealtime()
            assertFalse("A full backlog is still an existing listener", bounded.endpointClosed())
            assertTrue("Full-backlog closed-endpoint probe exceeded deadline", SystemClock.elapsedRealtime() - started < 3_000)
            assertEquals("The fixture must not drain its saturated backlog", 0, fixture.acceptCount.get())
        }
    }

    private suspend fun assertStallCloses(authenticated: Boolean) {
        SocketFixture().use { fixture ->
            val server = fixture.serve { socket ->
                assertEquals("AUTHENTICATE", readLine(socket))
                if (authenticated) {
                    respond(socket, "250 OK\r\n")
                    assertEquals("GETINFO status/bootstrap-phase", readLine(socket))
                }
                socket.inputStream.read()
            }
            // The adapter timeout is longer than the probe deadline, exercising owned close.
            val bounded = TorControlReadinessProbe({ AndroidTorControlConnection(fixture.endpoint.path, 5_000) }, 300)
            val started = SystemClock.elapsedRealtime()
            assertFalse(bounded.bootstrapComplete())
            assertTrue("Stalled control query exceeded its bounded close deadline", SystemClock.elapsedRealtime() - started < 3_000)
            assertEquals("Owner must close the stalled connection", -1, server.get(2, TimeUnit.SECONDS))
            assertFalse(bounded.endpointClosed())
        }
    }

    private fun probe(fixture: SocketFixture) = TorControlReadinessProbe({ AndroidTorControlConnection(fixture.endpoint.path, 1_000) }, 1_000)

    private fun connectFailure(connection: AndroidTorControlConnection): IOException {
        try { connection.connect() } catch (error: IOException) { return error }
        throw AssertionError("Expected a failed control socket connect")
    }

    private fun readLine(socket: LocalSocket): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 128) {
            val next = socket.inputStream.read()
            if (next < 0) throw EOFException("Fixture command truncated")
            if (next == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(next)
        }
        throw IOException("Fixture command exceeds limit")
    }

    private fun respond(socket: LocalSocket, text: String) {
        socket.outputStream.apply { write(text.toByteArray(Charsets.US_ASCII)); flush() }
    }

    private class SocketFixture(listen: Boolean = true) : Closeable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val directory = File(context.cacheDir, "ctl-${UUID.randomUUID().toString().take(12)}").apply { check(mkdir()) }
        val endpoint = File(directory, "control")
        private val binding = LocalSocket()
        private val accepted = AtomicReference<LocalSocket?>()
        val acceptCount = AtomicInteger()
        private val queuedClients = mutableListOf<LocalSocket>()
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "tor-control-fixture").apply { isDaemon = true } }
        private var listener: LocalServerSocket? = null

        init {
            if (listen) {
                binding.bind(LocalSocketAddress(endpoint.path, LocalSocketAddress.Namespace.FILESYSTEM))
                listener = LocalServerSocket(binding.fileDescriptor)
            }
        }

        fun <T> serve(block: (LocalSocket) -> T): Future<T> = executor.submit<T> {
            val socket = checkNotNull(listener).accept()
            acceptCount.incrementAndGet()
            accepted.set(socket)
            try {
                socket.soTimeout = 3_000
                block(socket)
            } finally {
                runCatching { socket.close() }
                accepted.compareAndSet(socket, null)
            }
        }

        fun saturateWithoutAccepting() {
            Os.listen(binding.fileDescriptor, 1)
            repeat(8) {
                val socket = LocalSocket()
                queuedClients += socket
                val attempt = executor.submit<IOException?> {
                    // Filler connects have a kernel timeout and an independent caller bound.
                    socket.inputStream
                    socket.soTimeout = 200
                    try {
                        socket.connect(LocalSocketAddress(endpoint.path, LocalSocketAddress.Namespace.FILESYSTEM))
                        null
                    } catch (error: IOException) { error }
                }
                val error = try { attempt.get(1, TimeUnit.SECONDS) }
                catch (_: TimeoutException) {
                    runCatching { socket.close() }
                    attempt.cancel(true)
                    throw AssertionError("Bounded fixture connect did not return")
                }
                if (error != null) {
                    runCatching { socket.close() }
                    queuedClients.remove(socket)
                    assertTrue("Fixture must queue a client before reporting saturation", queuedClients.isNotEmpty())
                    assertTrue("Fixture must saturate through a bounded backlog error", backlogTimeout(error))
                    return
                }
            }
            throw AssertionError("Fixture listener did not reach its reduced backlog limit")
        }

        private fun backlogTimeout(error: IOException): Boolean {
            var cause: Throwable? = error
            repeat(8) {
                val current = cause ?: return false
                if (current is SocketTimeoutException) return true
                if (current is ErrnoException) return current.errno == OsConstants.EAGAIN || current.errno == OsConstants.ETIMEDOUT
                if (current is IOException && (current.message == Os.strerror(OsConstants.EAGAIN) || current.message == Os.strerror(OsConstants.ETIMEDOUT))) return true
                cause = current.cause
            }
            return false
        }

        fun closeListener() {
            runCatching { listener?.close() }
            listener = null
            runCatching { binding.close() }
        }

        override fun close() {
            runCatching { accepted.getAndSet(null)?.close() }
            queuedClients.forEach { runCatching { it.close() } }
            queuedClients.clear()
            closeListener()
            executor.shutdownNow()
            endpoint.delete()
            directory.delete()
        }
    }
}
