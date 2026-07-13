package com.torxone.app.network

import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/** Local TCP server exposed as a Tor hidden service. */
class TorSocketServer(
    private val port: Int,
    private val onLineReceived: (String) -> Unit
) {
    companion object {
        private const val TAG = "TorSocketServer"
        private const val MAX_LINE_CHARS = MeshProtocol.MAX_FRAME_BYTES
    }

    private val running = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running.getAndSet(true)) return
        serverThread = Thread {
            try {
                // Bind to localhost only — Tor connects via 127.0.0.1
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                Log.d(TAG, "[SERVER] Listening on 127.0.0.1:$port")
                while (running.get()) {
                    val client = serverSocket?.accept() ?: break
                    Log.d(TAG, "[SERVER] Incoming connection from ${client.remoteSocketAddress}")
                    Thread {
                        handleClient(client)
                    }.start()
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "[SERVER] Server error: ${e.message}", e)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverThread?.interrupt()
        serverThread = null
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            client.use { sock ->
                val input = sock.getInputStream()
                val buffer = StringBuilder()
                while (running.get()) {
                    val next = input.read()
                    if (next == -1) break

                    if (next == '\n'.code) {
                        val line = buffer.toString().trim()
                        buffer.clear()
                        if (line.isNotBlank()) onLineReceived(line)
                    } else if (next != '\r'.code) {
                        if (buffer.length >= MAX_LINE_CHARS) {
                            Log.w(TAG, "Dropping oversized Tor frame")
                            break
                        }
                        buffer.append(next.toChar())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
        }
    }
}
