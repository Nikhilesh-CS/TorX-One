package com.torxone.app.network

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
import android.util.Base64

class TorManager(private val context: Context) {
    companion object {
        private const val TAG = "TorManager"
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 9050
        private const val CONTROL_PORT = 9051
        private const val LOCAL_PORT = 8765
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val BOOTSTRAP_TIMEOUT_MS = 120_000L
        private const val RESTART_BASE_DELAY_MS = 2_000L
        private const val RESTART_MAX_DELAY_MS = 60_000L
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
    @Volatile
    private var torProcess: Process? = null
    private var isRunning = AtomicBoolean(false)
    @Volatile
    private var processStartedAtMs: Long = 0L
    private var restartAttempts = 0
    private var restartJob: Job? = null
    private var watchdogJob: Job? = null

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
        _lastError.value = null
        updateState(TorState.Starting(0, "Initializing Tor..."))
        
        startLocalServer()
        ensureWatchdog()

        scope.launch {
            try {
                val torDir = File(context.filesDir, "tor").apply { mkdirs() }
                
                addTorLog("[TOR] Device ABI:\n${android.os.Build.SUPPORTED_ABIS.joinToString()}")

                val torBinary = resolveTorExecutable(torDir)
                if (torBinary == null) {
                    _lastError.value = "Tor executable not found or failed version check"
                    addTorLog("[ERROR] Tor executable missing or invalid")
                    isRunning.set(false)
                    updateState(TorState.Failed("Tor executable not found"))
                    return@launch
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

                val versionCheck = runTorVersionCheck(torBinary)
                if (!versionCheck.success) {
                    _lastError.value = versionCheck.output
                    addTorLog("[TOR] Execution test failed: ${versionCheck.output}")
                    isRunning.set(false)
                    updateState(TorState.Failed("Tor execution test failed"))
                    return@launch
                }

                addTorLog("[TOR] Execution test passed: ${versionCheck.output}")
                addTorLog("[TOR] Starting process")

                val hsDir = File(torDir, "hidden_service").apply {
                    mkdirs()
                    setReadable(false, false)
                    setWritable(false, false)
                    setExecutable(false, false)
                    setReadable(true, true)
                    setWritable(true, true)
                    setExecutable(true, true)
                }
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
                
                if (!isRunning.get()) return@launch

                val process = pb.start()
                torProcess = process
                processStartedAtMs = System.currentTimeMillis()
                
                if (!isRunning.get()) {
                    process.destroy()
                    return@launch
                }
                
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
                                            restartAttempts = 0
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
                                                restartAttempts = 0
                                                addTorLog("[TOR] Hidden service created")
                                                addTorLog("[TOR] Onion address generated: $onion")
                                                updateState(TorState.Connected(onion))
                                            } else {
                                                _lastError.value = "Hidden service hostname file missing"
                                                scheduleRestart("Hidden service hostname file missing")
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
                    _isTorReady.value = false
                    scheduleRestart("Tor process exited unexpectedly (code ${process.exitValue()})")
                }
            } catch (e: Exception) {
                _lastError.value = e.message ?: "Unknown error"
                addTorLog("[ERROR] Tor process failed: ${e.message}")
                _isTorReady.value = false
                if (isRunning.get()) {
                    scheduleRestart(e.message ?: "Unknown error")
                } else {
                    updateState(TorState.Failed(e.message ?: "Unknown error"))
                }
            }
        }
    }

    fun stop() {
        addTorLog("[STOP] Stopping Tor...")
        isRunning.set(false)
        restartJob?.cancel()
        watchdogJob?.cancel()
        restartJob = null
        watchdogJob = null
        socketServer?.stop()
        torProcess?.destroy()
        _isTorReady.value = false
        updateState(TorState.Stopped)
    }

    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive && isRunning.get()) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!isRunning.get()) break

                val process = torProcess
                val alive = process?.isAliveCompat() == true
                val state = _torState.value
                when {
                    process != null && !alive -> {
                        _isTorReady.value = false
                        scheduleRestart("Tor process is not alive")
                    }
                    state is TorState.Starting &&
                        processStartedAtMs > 0L &&
                        System.currentTimeMillis() - processStartedAtMs > BOOTSTRAP_TIMEOUT_MS -> {
                        addTorLog("[WATCHDOG] Bootstrap timed out; restarting Tor")
                        _isTorReady.value = false
                        process?.destroy()
                        scheduleRestart("Tor bootstrap timed out")
                    }
                }
            }
        }
    }

    private fun scheduleRestart(reason: String) {
        if (!isRunning.get()) return
        if (restartJob?.isActive == true) return
        restartAttempts += 1
        val delayMs = restartDelayMillis(restartAttempts)
        addTorLog("[RESTART] $reason. Retrying in ${delayMs}ms")
        updateState(TorState.Reconnecting(reason, restartAttempts))
        restartJob = scope.launch {
            delay(delayMs)
            if (!isRunning.get()) return@launch
            torProcess?.destroy()
            torProcess = null
            _isTorReady.value = false
            isRunning.set(false)
            start()
        }
    }

    private fun restartDelayMillis(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 5)
        return (RESTART_BASE_DELAY_MS * multiplier).coerceAtMost(RESTART_MAX_DELAY_MS)
    }

    private fun Process.isAliveCompat(): Boolean {
        return try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    /**
     * Data class to securely hold exported Tor keys in memory temporarily during a backup.
     */
    data class ExportedTorKeys(
        val pubKeyB64: String,
        val secKeyB64: String,
        val onionAddress: String
    )

    /**
     * Reads and exports the Tor keys directly from the filesystem.
     * Returns the keys encoded in Base64 for the binary files.
     */
    fun exportHiddenServiceKeys(): ExportedTorKeys {
        val torDir = File(context.filesDir, "tor")
        val hsDir = File(torDir, "hidden_service")
        
        val pubKeyFile = File(hsDir, "hs_ed25519_public_key")
        val secKeyFile = File(hsDir, "hs_ed25519_secret_key")
        val hostnameFile = File(hsDir, "hostname")

        if (!pubKeyFile.exists() || !secKeyFile.exists() || !hostnameFile.exists()) {
            throw IllegalStateException("Tor hidden service keys do not exist on disk.")
        }

        val pubKeyB64 = Base64.encodeToString(pubKeyFile.readBytes(), Base64.NO_WRAP)
        val secKeyB64 = Base64.encodeToString(secKeyFile.readBytes(), Base64.NO_WRAP)
        val hostname = hostnameFile.readText().trim()

        return ExportedTorKeys(pubKeyB64, secKeyB64, hostname)
    }

    /**
     * Safely injects restored identity keys into the hidden_service directory.
     * Must be called while Tor is NOT running.
     */
    fun importHiddenServiceKeys(pubKeyB64: String, secKeyB64: String, onionAddress: String) {
        try {
            val torDir = File(context.filesDir, "tor")
            val hsDir = File(torDir, "hidden_service").apply { mkdirs() }
            
            val pubKeyFile = File(hsDir, "hs_ed25519_public_key")
            val secKeyFile = File(hsDir, "hs_ed25519_secret_key")
            val hostnameFile = File(hsDir, "hostname")

            // Write raw binary keys
            pubKeyFile.writeBytes(Base64.decode(pubKeyB64, Base64.NO_WRAP))
            secKeyFile.writeBytes(Base64.decode(secKeyB64, Base64.NO_WRAP))
            
            // Write hostname (onion address)
            hostnameFile.writeText(onionAddress + "\n")
            
            addTorLog("[RESTORE] Tor hidden service keys imported successfully.")
        } catch (e: Exception) {
            addTorLog("[ERROR] Failed to import Tor keys: ${e.message}")
            throw e
        }
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
            socket.soTimeout = timeoutMs
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
        return findPackagedNativeTor() ?: File(context.filesDir, "tor/tor")
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

    private fun resolveTorExecutable(torDir: File): File? {
        // Modern Android mounts the app data directory with execution blocked. The
        // APK's native library directory is executable, so run the packaged PIE in
        // place instead of copying it to filesDir and hitting EACCES (error 13).
        File(torDir, "tor").delete()

        val packagedTor = findPackagedNativeTor() ?: run {
            addTorLog("[TOR] Packaged native Tor not found")
            return null
        }
        addTorLog("[TOR] Using packaged native Tor from nativeLibraryDir")
        addTorLog("[TOR] Packaged native binary type: ${describeBinaryType(packagedTor)}")

        val versionCheck = runTorVersionCheck(packagedTor)
        if (versionCheck.success) return packagedTor

        addTorLog("[TOR] Packaged native Tor failed: ${versionCheck.output}")
        return null
    }

    private fun findPackagedNativeTor(): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val libTor = File(nativeDir, "libtor.so")
        if (libTor.exists()) return libTor
        return nativeDir.listFiles()
            ?.filter { it.isFile }
            ?.firstOrNull { file ->
                file.name.contains("tor", ignoreCase = true) &&
                    !file.name.contains("crypto", ignoreCase = true) &&
                    !file.name.contains("ssl", ignoreCase = true)
            }
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
