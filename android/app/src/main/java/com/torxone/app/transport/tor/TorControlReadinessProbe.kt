package com.torxone.app.transport.tor

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/** Owns each short-lived control connection, so a stalled library query cannot poison a restart. */
internal class TorControlReadinessProbe(
    private val connectionFactory: () -> Connection,
    private val timeoutMs: Long = 2_000L
) {
    interface Connection : Closeable {
        fun connect()
        val input: InputStream
        val output: OutputStream
        fun endpointUnavailable(error: IOException): Boolean = error is java.net.ConnectException
    }

    private val pending = ConcurrentHashMap.newKeySet<Connection>()

    suspend fun bootstrapComplete(): Boolean = exchange { connection ->
        connection.output.write("GETINFO status/bootstrap-phase\r\n".toByteArray(Charsets.US_ASCII))
        connection.output.flush()
        var complete = false
        var terminated = false
        repeat(16) {
            if (!terminated) {
                val line = readLine(connection.input)
                when {
                    line == "250 OK" -> terminated = true
                    line.startsWith("250-status/bootstrap-phase=") -> {
                        complete = Regex("(?:^|\\s)PROGRESS=100(?:\\s|$)").containsMatchIn(line.substringAfter('='))
                    }
                    else -> throw IOException("Unexpected Tor control response")
                }
            }
        }
        if (!terminated) throw IOException("Unterminated Tor control response")
        complete
    }

    /** Recovery uses an owned deadline rather than the library's unbounded control command wait. */
    suspend fun halt(): Boolean = exchange { connection ->
        connection.output.write("SIGNAL HALT\r\n".toByteArray(Charsets.US_ASCII))
        connection.output.flush()
        readLine(connection.input) == "250 OK"
    }

    suspend fun controlAvailable(): Boolean = exchange { true }

    /** A query timeout is NOT evidence the daemon stopped; only an unavailable connect is. */
    suspend fun endpointClosed(): Boolean = exchange(authenticate = false,
        ioResult = { connection, error -> connection.endpointUnavailable(error) }) { false }

    private suspend fun exchange(
        authenticate: Boolean = true,
        ioResult: (Connection, IOException) -> Boolean = { _, _ -> false },
        command: (Connection) -> Boolean
    ): Boolean {
        val connection = try { connectionFactory() } catch (_: IOException) { return false }
        pending.add(connection)
        try {
            return TorIoDeadline.watch(connection, timeoutMs).use {
                withTimeoutOrNull(timeoutMs) {
                    coroutineScope {
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            continuation.invokeOnCancellation { runCatching { connection.close() } }
                            launch(Dispatchers.IO) {
                                try {
                                    connection.connect()
                                    if (authenticate) {
                                        connection.output.write("AUTHENTICATE\r\n".toByteArray(Charsets.US_ASCII))
                                        connection.output.flush()
                                        if (readLine(connection.input) != "250 OK") throw IOException("Tor control authentication failed")
                                    }
                                    continuation.resume(command(connection))
                                } catch (error: Exception) { continuation.resumeWithException(error) }
                            }
                        }
                    }
                } ?: false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return ioResult(connection, error)
        } finally {
            pending.remove(connection)
            runCatching { connection.close() }
        }
    }

    fun cancelPending() { pending.forEach { runCatching { it.close() } } }

    private fun readLine(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 4_096) {
            val next = input.read()
            if (next < 0) throw EOFException("Truncated Tor control response")
            if (next == 10) return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
            bytes.write(next)
        }
        throw IOException("Tor control response exceeds limit")
    }
}
