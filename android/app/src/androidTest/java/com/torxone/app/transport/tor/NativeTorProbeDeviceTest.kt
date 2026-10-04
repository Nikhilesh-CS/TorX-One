package com.torxone.app.transport.tor

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.torproject.jni.TorService
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Read-only release probe. A passing test means capture ran, not that Tor is ready. */
@RunWith(AndroidJUnit4::class)
class NativeTorProbeDeviceTest {
    @Test fun captureNativeTorState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val delay = InstrumentationRegistry.getArguments().getString("snapshotDelayMs")
            ?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0L
        val samples = if (delay > 0) 3 else 0
        repeat(samples + 1) { sample ->
            if (sample > 0) SystemClock.sleep(delay / samples)
            val result = runCatching { snapshot() }.getOrElse {
                Bundle().apply { putString("capture", "UNAVAILABLE"); putString("error", errorClass(it)) }
            }
            result.putString("sample", if (sample == 0) "INITIAL" else if (sample == samples) "FINAL" else "INTERMEDIATE")
            result.putString("probeMeaning", "CAPTURE_ONLY")
            instrumentation.sendStatus(0, result)
        }
    }

    private fun snapshot(): Bundle {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val torrc = TorService.getTorrc(context)
        val privateRoot = File(context.applicationInfo.dataDir).canonicalFile
        val root = torrc.parentFile ?: return Bundle().apply { putString("capture", "NO_PRIVATE_ROOT") }
        fun privateFile(path: String): File? = runCatching {
            val file = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
            file.takeIf { it.path.startsWith(privateRoot.path + File.separator) }
        }.getOrNull()
        val directives = if (torrc.isFile && torrc.length() <= 65_536) runCatching {
            torrc.inputStream().use { input ->
                val bytes = ByteArray(65_537)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > 65_536) emptyList() else String(bytes, 0, count, Charsets.UTF_8).lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val split = line.indexOfFirst(Char::isWhitespace)
                        if (split < 0) null else line.substring(0, split) to line.substring(split).trim().removeSurrounding("\"")
                    }.toList()
            }
        }.getOrDefault(emptyList()) else emptyList()
        fun directive(name: String) = directives.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
        val data = privateFile(directive("DataDirectory") ?: File(root, "data").path)
        val socket = privateFile(directive("ControlSocket") ?: File(data ?: File(root, "data"), "ControlSocket").path)
        val onionDir = privateFile(directive("HiddenServiceDir") ?: File(privateRoot, "app_torx_onion_v3").path)
        val hostname = onionDir?.let { File(it, "hostname") }
        return Bundle().apply {
            putString("capture", "CAPTURED")
            putBoolean("torrcPresent", torrc.isFile)
            putBoolean("dataDirectoryPresent", data?.isDirectory == true)
            putBoolean("controlSocketConfigured", directive("ControlSocket") != null)
            putBoolean("controlSocketPresent", socket?.exists() == true)
            putBoolean("controlSocketPrivate", socket?.let(::ownedPrivate) == true)
            putBoolean("onionDirectoryPresent", onionDir?.isDirectory == true)
            putBoolean("onionDirectoryPrivate", onionDir?.let(::ownedPrivate) == true)
            putBoolean("hostnamePresent", hostname?.isFile == true)
            putBoolean("hostnameNonempty", hostname?.let { it.isFile && it.length() > 0 } == true)
            putBoolean("hostnameFormatValid", hostname?.let {
                it.isFile && it.length() in 1..128 && runCatching {
                    it.readText(Charsets.US_ASCII).trim().matches(Regex("[a-z2-7]{56}\\.onion"))
                }.getOrDefault(false)
            } == true)
            if (socket == null) putString("control", "OUTSIDE_PRIVATE_ROOT") else putAll(probeControl(socket))
        }
    }

    private fun ownedPrivate(file: File): Boolean = runCatching {
        val stat = Os.stat(file.path)
        stat.st_uid == Process.myUid() && (stat.st_mode and 63) == 0
    }.getOrDefault(false)

    /** The owner closes both sockets after 2s even if connect or a native read stalls. */
    private fun probeControl(endpoint: File): Bundle {
        val socket = LocalSocket()
        val stopped = AtomicBoolean(false)
        val tcpOwner = AtomicReference<Socket?>()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "native-tor-probe").apply { isDaemon = true } }
        val future = executor.submit<Bundle> {
            val result = Bundle().apply { putString("control", "CONNECTING"); putString("authentication", "NOT_ATTEMPTED") }
            try {
                // Create the lazy descriptor before options. SO_TIMEOUT also bounds connect
                // when the private Unix listener's accept backlog is full.
                socket.inputStream
                socket.soTimeout = 2_000
                socket.connect(LocalSocketAddress(endpoint.path, LocalSocketAddress.Namespace.FILESYSTEM))
                result.putString("control", "CONNECTED")
                val input = socket.inputStream
                val output = socket.outputStream
                fun command(text: String): List<String> {
                    output.write((text + "\r\n").toByteArray(Charsets.US_ASCII)); output.flush()
                    val lines = mutableListOf<String>()
                    repeat(16) {
                        val line = readLine(input)
                        if (line.length < 4 || line.take(3).toIntOrNull() == null || line[3] !in " -") throw ProtocolException()
                        lines += line
                        if (line[3] == ' ') return lines
                    }
                    throw ProtocolException()
                }
                val auth = command("AUTHENTICATE")
                val authClass = responseClass(auth.last())
                result.putString("authentication", authClass)
                if (authClass == "SUCCESS") {
                    val reply = command("GETINFO status/bootstrap-phase net/listeners/socks")
                    result.putString("controlResponse", responseClass(reply.last()))
                    val bootstrap = reply.firstOrNull { it.startsWith("250-status/bootstrap-phase=") || it.startsWith("250 status/bootstrap-phase=") }
                    result.putBoolean("bootstrapReported", bootstrap != null)
                    bootstrap?.let {
                        Regex("(?:^|\\s)PROGRESS=(\\d{1,3})(?:\\s|$)").find(it)?.groupValues?.get(1)?.toIntOrNull()
                            ?.takeIf { progress -> progress in 0..100 }?.let { progress -> result.putInt("bootstrapPercent", progress) }
                    }
                    val socks = reply.firstOrNull { it.startsWith("250-net/listeners/socks=") || it.startsWith("250 net/listeners/socks=") }
                    result.putBoolean("socksListenersReported", socks != null)
                    result.putBoolean("socksListenerPresent", socks?.substringAfter('=')?.trim()?.removeSurrounding("\"")?.isNotEmpty() == true)
                    result.putBoolean("socksHandshakeSucceeded", socks?.let { socksHandshake(it.substringAfter('='), tcpOwner, stopped) } == true)
                }
            } catch (error: Exception) {
                result.putString("control", "IO_FAILURE")
                result.putString("error", errorClass(error))
            } finally { closeLocalSocket(socket) }
            result
        }
        return try { future.get(2_000, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { Bundle().apply { putString("control", "DEADLINE"); putString("error", "TIMEOUT") } }
        catch (error: Exception) { Bundle().apply { putString("control", "IO_FAILURE"); putString("error", errorClass(error)) } }
        finally {
            stopped.set(true)
            closeLocalSocket(socket)
            runCatching { tcpOwner.getAndSet(null)?.close() }
            future.cancel(true)
            executor.shutdownNow()
        }
    }

    private fun closeLocalSocket(socket: LocalSocket) {
        // Shutdown wakes an in-flight native read before releasing the owned descriptor.
        try { socket.shutdownInput() } catch (_: IOException) { }
        try { socket.shutdownOutput() } catch (_: IOException) { }
        try { socket.close() } catch (_: IOException) { }
    }

    private fun socksHandshake(listeners: String, owner: AtomicReference<Socket?>, stopped: AtomicBoolean): Boolean {
        val addresses = Regex("\"(127\\.0\\.0\\.1|\\[::1\\]):(\\d{1,5})\"").findAll(listeners).take(2)
        for (address in addresses) {
            val port = address.groupValues[2].toIntOrNull()?.takeIf { it in 1..65_535 } ?: continue
            val socket = Socket()
            owner.set(socket)
            try {
                if (stopped.get()) return false
                socket.connect(InetSocketAddress(address.groupValues[1].removeSurrounding("[", "]"), port), 500)
                socket.soTimeout = 500
                socket.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
                val input = socket.getInputStream()
                if (input.read() == 5 && input.read() == 0) return true
            } catch (_: Exception) {
                // Handshake failures become one boolean; never emit the endpoint or exception text.
            } finally {
                runCatching { socket.close() }
                owner.compareAndSet(socket, null)
            }
        }
        return false
    }

    private class ProtocolException : Exception()

    private fun readLine(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 4_096) {
            val next = input.read()
            if (next < 0) throw EOFException()
            if (next == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(next)
        }
        throw ProtocolException()
    }

    private fun responseClass(line: String): String = when (line.take(3).toIntOrNull()) {
        250 -> "SUCCESS"
        514 -> "AUTH_REQUIRED"
        515 -> "AUTH_REJECTED"
        in 500..599 -> "SERVER_REJECTED"
        else -> "UNEXPECTED_RESPONSE"
    }

    private fun errorClass(error: Throwable): String {
        var cause: Throwable? = error
        repeat(8) {
            when (val current = cause) {
                is ErrnoException -> return when (current.errno) {
                    OsConstants.ENOENT -> "ENOENT"
                    OsConstants.ECONNREFUSED -> "ECONNREFUSED"
                    OsConstants.EACCES -> "EACCES"
                    OsConstants.ETIMEDOUT -> "ETIMEDOUT"
                    else -> "OTHER_ERRNO"
                }
                is SocketTimeoutException, is TimeoutException -> return "TIMEOUT"
                is EOFException -> return "EOF"
                is ProtocolException -> return "PROTOCOL"
                is SecurityException -> return "ACCESS_DENIED"
                null -> return "OTHER_FAILURE"
            }
            // Android LocalSocket JNI may report errno as an exact strerror message.
            if (cause is IOException) {
                when (cause?.message) {
                    Os.strerror(OsConstants.ENOENT) -> return "ENOENT"
                    Os.strerror(OsConstants.ECONNREFUSED) -> return "ECONNREFUSED"
                    Os.strerror(OsConstants.EACCES) -> return "EACCES"
                    Os.strerror(OsConstants.ETIMEDOUT) -> return "ETIMEDOUT"
                }
            }
            cause = cause?.cause
        }
        return "OTHER_FAILURE"
    }
}
