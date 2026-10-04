package com.torxone.app.transport.tor

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException

/** Creates Android's lazy descriptor before setting options, including a connect timeout. */
internal class AndroidTorControlConnection(
    private val endpointPath: String,
    private val timeoutMs: Int = 2_000
) : TorControlReadinessProbe.Connection {
    private val socket = LocalSocket()
    private var unavailableConnectError: IOException? = null

    init { require(timeoutMs > 0) }

    override fun connect() {
        unavailableConnectError = null
        // getInputStream is a public lazy-create operation; it does not require a connection.
        // SO_TIMEOUT sets both receive/send timeouts, bounding a full Unix socket backlog.
        socket.inputStream
        socket.soTimeout = timeoutMs
        try {
            socket.connect(LocalSocketAddress(endpointPath, LocalSocketAddress.Namespace.FILESYSTEM))
        } catch (error: IOException) {
            if (isUnavailableConnect(error)) unavailableConnectError = error
            throw error
        }
    }

    override val input get() = socket.inputStream
    override val output get() = socket.outputStream

    // Only the same failed connect can prove absence. Read/auth/timeout failures cannot.
    override fun endpointUnavailable(error: IOException): Boolean = error === unavailableConnectError

    override fun close() {
        // A native recvmsg may retain the descriptor after close. Shutdown actively wakes it.
        try { socket.shutdownInput() } catch (_: IOException) { }
        try { socket.shutdownOutput() } catch (_: IOException) { }
        socket.close()
    }

    private fun isUnavailableConnect(error: IOException): Boolean {
        var cause: Throwable? = error
        repeat(8) {
            val current = cause ?: return false
            if (current is ErrnoException) {
                return current.errno == OsConstants.ENOENT || current.errno == OsConstants.ECONNREFUSED
            }
            // LocalSocket's JNI connect throws a plain IOException with strerror(errno),
            // without an ErrnoException cause. Compare exact platform strings, never substrings.
            if (current is IOException && (current.message == Os.strerror(OsConstants.ENOENT) ||
                    current.message == Os.strerror(OsConstants.ECONNREFUSED))) return true
            cause = current.cause
        }
        return false
    }
}
