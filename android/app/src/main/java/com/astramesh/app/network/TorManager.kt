package com.astramesh.app.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Build

class TorManager(private val context: Context) {
    companion object {
        private const val TAG = "TorManager"
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 9050
        private const val CONTROL_PORT = 9051
        private const val LOCAL_PORT = 8765
    }

    private val _torState = MutableStateFlow<TorState>(TorState.Idle)
    val torState: StateFlow<TorState> = _torState

    private val _torStatus = MutableStateFlow("Idle")
    val torStatus: StateFlow<String> = _torStatus

    private val _onionAddress = MutableStateFlow("")
    val onionAddress: StateFlow<String> = _onionAddress
    
    private val _activeConnections = MutableStateFlow<List<String>>(emptyList())
    val activeConnections: StateFlow<List<String>> = _activeConnections

    private val _isTorReady = MutableStateFlow(false)
    val isTorReady: StateFlow<Boolean> = _isTorReady

    var onTorMessageReceived: ((String) -> Unit)? = null
    private var socketServer: TorSocketServer? = null

    private val _torLogs = MutableStateFlow<List<String>>(emptyList())
    val torLogs: StateFlow<List<String>> = _torLogs

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _lastPing = MutableStateFlow<String?>(null)
    val lastPing: StateFlow<String?> = _lastPing

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var torProcess: Process? = null
    private var isRunning = AtomicBoolean(false)

    private fun addTorLog(msg: String) {
        val current = _torLogs.value.toMutableList()
        if (current.size > 100) current.removeAt(0)
        current.add(msg)
        _torLogs.value = current
        Log.d(TAG, msg)
    }

    private fun updateState(state: TorState) {
        _torState.value = state
        _torStatus.value = state.getDisplayText()
        addTorLog("[STATE] ${state.getDisplayText()}")
    }
    
    fun setLastPing(latencyMs: Long) {
        _lastPing.value = "${latencyMs}ms"
    }

    fun start() {
        if (_torState.value is TorState.Failed) {
            torProcess?.destroy()
            torProcess = null
            isRunning.set(false)
            _isTorReady.value = false
        }
        
        val isAlive = try {
            torProcess?.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
        
        if (isAlive || isRunning.getAndSet(true)) {
            addTorLog("[START] Already running, skipping")
            return
        }
        
        addTorLog("[TOR] Starting process")
        updateState(TorState.Starting(0, "Initializing Tor..."))
        
        startLocalServer()

        scope.launch {
            try {
                val torDir = File(context.filesDir, "tor").apply { mkdirs() }
                
                addTorLog("[TOR] Device ABI:\n${android.os.Build.SUPPORTED_ABIS.joinToString()}")

                val nativeDir = File(context.applicationInfo.nativeLibraryDir)
                val libTor = File(nativeDir, "libtor.so")
                
                val torBinary: File
                if (libTor.exists()) {
                    torBinary = libTor
                    addTorLog("[TOR] Found libtor.so in nativeLibraryDir")
                } else {
                    val fallback = nativeDir.listFiles()?.firstOrNull { it.name.contains("tor") && !it.name.contains("crypto") && !it.name.contains("ssl") }
                    if (fallback != null) {
                        torBinary = fallback
                        addTorLog("[TOR] Found fallback ${fallback.name} in nativeLibraryDir")
                    } else {
                        // Very old Android versions might still allow execution from filesDir, but this is a last resort.
                        torBinary = File(torDir, "tor")
                        if (!torBinary.exists()) {
                            var extracted = false
                            for (abi in android.os.Build.SUPPORTED_ABIS) {
                                try {
                                    context.assets.open("tor/$abi/tor").use { input ->
                                        torBinary.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    extracted = true
                                    addTorLog("[TOR] Extracted from assets for $abi to filesDir")
                                    break
                                } catch (e: Exception) {
                                }
                            }
                            if (!extracted) {
                                _lastError.value = "Tor binary not found in native or assets"
                                addTorLog("[ERROR] Tor binary missing")
                                updateState(TorState.Failed("Tor binary not found"))
                                return@launch
                            }
                        }
                    }
                }

                if (!torBinary.canExecute()) {
                    if (!torBinary.setExecutable(true)) {
                        addTorLog("[WARNING] Could not set executable flag on ${torBinary.absolutePath}. OS might deny execution.")
                    } else {
                        addTorLog("[TOR] Executable permissions granted")
                    }
                }

                addTorLog("[TOR] Binary path:\n${torBinary.absolutePath}")
                addTorLog("[TOR] Exists: ${torBinary.exists()}")
                addTorLog("[TOR] Executable: ${torBinary.canExecute()}")
                
                addTorLog("[TOR] Starting process")
                addTorLog("[TOR] Binary type: ${describeBinaryType(torBinary)}")

                var versionCheck = runTorVersionCheck(torBinary)
                if (!versionCheck.success) {
                    addTorLog("[TOR] Execution test failed: ${versionCheck.output}")
                    addTorLog("[TOR] Trying bundled asset replacement before giving up")

                    if (extractTorExecutableFromAssets(torBinary)) {
                        torBinary.setExecutable(true)
                        addTorLog("[TOR] Replacement binary type: ${describeBinaryType(torBinary)}")
                        versionCheck = runTorVersionCheck(torBinary)
                    }

                    if (!versionCheck.success) {
                        _lastError.value = versionCheck.output
                        addTorLog("[TOR] Execution test failed: ${versionCheck.output}")
                        updateState(TorState.Failed("Tor execution test failed"))
                        return@launch
                    }
                }

                addTorLog("[TOR] Execution test passed: ${versionCheck.output}")
                addTorLog("[TOR] Starting process")

                val hsDir = File(torDir, "hidden_service").apply { mkdirs() }
                val torrc = File(torDir, "torrc")

                torrc.writeText("""
                    SocksPort $SOCKS_PORT
                    ControlPort $CONTROL_PORT
                    DataDirectory ${torDir.absolutePath}
                    HiddenServiceDir ${hsDir.absolutePath}
                    HiddenServicePort $LOCAL_PORT 127.0.0.1:$LOCAL_PORT
                    Log notice stdout
                """.trimIndent())

                addTorLog("[START] torrc written to ${torrc.absolutePath}")
                addTorLog("[TOR] SOCKS proxy started on port $SOCKS_PORT")
                updateState(TorState.Starting(10, "Starting Daemon..."))

                val pb = ProcessBuilder(torBinary.absolutePath, "-f", torrc.absolutePath)
                pb.directory(torDir)
                pb.redirectErrorStream(true)
                
                val process = pb.start()
                torProcess = process
                addTorLog("[START] Tor process started (PID tracking unavailable on Android)")

                // Read output to monitor bootstrap
                launch {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (line.contains("Bootstrapped")) {
                                    val match = Regex("Bootstrapped (\\d+)%").find(line)
                                    val progress = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                    
                                    if (progress == 100) {
                                        addTorLog("[TOR] Bootstrap 100%")
                                        updateState(TorState.Starting(100, "Bootstrap 100%"))
                                        
                                        // Give Tor a moment to write the hostname file
                                        delay(1000)
                                        val hostnameFile = File(hsDir, "hostname")
                                        if (hostnameFile.exists()) {
                                            val onion = hostnameFile.readText().trim()
                                            _onionAddress.value = onion
                                            _isTorReady.value = true
                                            addTorLog("[TOR] Hidden service created")
                                            addTorLog("[TOR] Onion address generated: $onion")
                                            updateState(TorState.Connected(onion))
                                        } else {
                                            addTorLog("[ERROR] hostname file missing at ${hostnameFile.absolutePath}")
                                            // Retry reading hostname after more delay
                                            delay(3000)
                                            if (hostnameFile.exists()) {
                                                val onion = hostnameFile.readText().trim()
                                                _onionAddress.value = onion
                                                _isTorReady.value = true
                                                addTorLog("[TOR] Hidden service created")
                                                addTorLog("[TOR] Onion address generated: $onion")
                                                updateState(TorState.Connected(onion))
                                            } else {
                                                _lastError.value = "Hidden service hostname file missing"
                                                updateState(TorState.Failed("Hidden service hostname file missing"))
                                            }
                                        }
                                    } else {
                                        addTorLog("[TOR] Bootstrap $progress%")
                                        updateState(TorState.Starting(progress, "Bootstrap $progress%"))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        addTorLog("[ERROR] Error reading Tor output: ${e.message}")
                    }
                }

                process.waitFor()
                addTorLog("[PROCESS] Tor process exited with code: ${process.exitValue()}")
                if (isRunning.get()) {
                    _lastError.value = "Tor process exited unexpectedly (code ${process.exitValue()})"
                    updateState(TorState.Failed("Tor process exited unexpectedly (code ${process.exitValue()})"))
                    _isTorReady.value = false
                    // Attempt auto-restart after a delay
                    delay(10_000)
                    if (isRunning.get()) {
                        addTorLog("[RESTART] Attempting automatic restart...")
                        isRunning.set(false)
                        start()
                    }
                }
            } catch (e: Exception) {
                _lastError.value = e.message ?: "Unknown error"
                addTorLog("[ERROR] Tor process failed: ${e.message}")
                updateState(TorState.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    fun stop() {
        addTorLog("[STOP] Stopping Tor...")
        isRunning.set(false)
        socketServer?.stop()
        torProcess?.destroy()
        _isTorReady.value = false
        updateState(TorState.Stopped)
    }

    fun createTorSocket(onionHost: String, port: Int = LOCAL_PORT, timeoutMs: Int = 60_000): Socket? {
        if (!_isTorReady.value) {
            addTorLog("[SOCKET] Tor not ready, cannot create socket to $onionHost")
            return null
        }
        return try {
            addTorLog("[SOCKET] Connecting to $onionHost:$port via SOCKS5 proxy")
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(SOCKS_HOST, SOCKS_PORT))
            val socket = Socket(proxy)
            // Use createUnresolved to prevent local DNS leak which breaks SOCKS
            socket.connect(InetSocketAddress.createUnresolved(onionHost, port), timeoutMs)
            addTorLog("[SOCKET] Connected to $onionHost successfully")
            socket
        } catch (e: Exception) {
            _lastError.value = "Socket failed: ${e.message}"
            addTorLog("[SOCKET] Failed to connect to $onionHost via Tor: ${e.message}")
            null
        }
    }

    fun sendToOnion(onionHost: String, payload: String): Boolean {
        addTorLog("[TOR] Sending message to $onionHost")
        val socket = createTorSocket(onionHost) ?: return false
        return try {
            socket.getOutputStream().use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                out.flush()
            }
            addTorLog("[TOR] Packet sent successfully to $onionHost")
            true
        } catch (e: Exception) {
            _lastError.value = "Send failed: ${e.message}"
            addTorLog("[TOR] Delivery failed to $onionHost: ${e.message}")
            false
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun startLocalServer() {
        socketServer?.stop()
        socketServer = TorSocketServer(LOCAL_PORT) { line ->
            onTorMessageReceived?.invoke(line)
        }
        socketServer?.start()
        addTorLog("[TOR] Listening on port $LOCAL_PORT")
    }

    private fun getActiveTorBinary(): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val libTor = File(nativeDir, "libtor.so")
        if (libTor.exists()) return libTor
        val fallback = nativeDir.listFiles()?.firstOrNull { it.name.contains("tor") && !it.name.contains("crypto") && !it.name.contains("ssl") }
        if (fallback != null) return fallback
        return File(context.filesDir, "tor/tor")
    }

    fun testTorBinary(): String {
        val torBinary = getActiveTorBinary()
        if (!torBinary.exists()) return "Binary not found\nBinary type: missing"

        val versionCheck = runTorVersionCheck(torBinary)
        val status = if (versionCheck.success) "Execution test passed" else "Execution test failed"
        return "Binary type: ${describeBinaryType(torBinary)}\n$status: ${versionCheck.output}"
    }
    
    val torBinaryPath: String
        get() = getActiveTorBinary().absolutePath
        
    val torBinaryExists: Boolean
        get() = getActiveTorBinary().exists()
        
    val torBinaryExecutable: Boolean
        get() = getActiveTorBinary().canExecute()

    val torBinaryType: String
        get() = describeBinaryType(getActiveTorBinary())

    private data class TorVersionCheck(
        val success: Boolean,
        val output: String
    )

    private fun runTorVersionCheck(torBinary: File): TorVersionCheck {
        return try {
            val process = ProcessBuilder(torBinary.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return TorVersionCheck(false, "tor --version timed out")
            }

            val output = process.inputStream.bufferedReader().readText().trim()
            val summary = output.ifEmpty { "Process exited with code ${process.exitValue()}" }
            TorVersionCheck(process.exitValue() == 0 && output.contains("Tor", ignoreCase = true), summary)
        } catch (e: Exception) {
            TorVersionCheck(false, "tor --version failed: ${e.message}")
        }
    }

    private fun extractTorExecutableFromAssets(torBinary: File): Boolean {
        for (abi in Build.SUPPORTED_ABIS) {
            try {
                val assetPath = "tor/$abi/tor"
                context.assets.open(assetPath).use { input ->
                    torBinary.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                addTorLog("[TOR] Binary found in assets for $abi")
                return true
            } catch (_: Exception) {
                addTorLog("[TOR] No binary in assets for $abi")
            }
        }
        return false
    }

    private fun describeBinaryType(file: File): String {
        if (!file.exists()) return "missing"

        return try {
            file.inputStream().use { input ->
                val header = ByteArray(18)
                if (input.read(header) < header.size) return "unknown: too small"
                if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) {
                    return "unknown: not ELF"
                }

                val elfClass = when (header[4].toInt()) {
                    1 -> "ELF 32-bit"
                    2 -> "ELF 64-bit"
                    else -> "ELF unknown-class"
                }
                val littleEndian = header[5].toInt() == 1
                val typeValue = if (littleEndian) {
                    (header[16].toInt() and 0xff) or ((header[17].toInt() and 0xff) shl 8)
                } else {
                    ((header[16].toInt() and 0xff) shl 8) or (header[17].toInt() and 0xff)
                }

                val type = when (typeValue) {
                    2 -> "executable"
                    3 -> "shared object / PIE"
                    else -> "type $typeValue"
                }
                "$elfClass $type"
            }
        } catch (e: Exception) {
            "unknown: ${e.message}"
        }
    }
}
