package com.torxone.app.transport.tor

import android.content.*
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.torproject.jni.TorService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TorController(
    context: Context,
    private val onionEndpointManager: OnionEndpointManager
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<TorConnectionState>(TorConnectionState.Stopped)
    val state: StateFlow<TorConnectionState> = _state.asStateFlow()
    private val desired = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private val bindingGeneration = AtomicLong()
    @Volatile private var service: TorService? = null
    @Volatile private var torReportedOn = false
    @Volatile private var bootstrapComplete = false
    @Volatile private var socksAvailable = false
    private val readinessGate = TorReadinessGate()
    private val recovery = TorRecoveryBudget()
    private val failures = Channel<Long>(Channel.CONFLATED)
    private val serviceActions = Mutex()
    private val lifecycleGuard = Any()
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readinessMonitor: Job? = null
    private var supervisor: Job? = null
    @Volatile private var registration: Registration? = null

    private class Registration(val connection: ServiceConnection, val receiver: BroadcastReceiver, var bound: Boolean = false) {
        @Volatile var service: TorService? = null
    }

    private val controlProbe = TorControlReadinessProbe({
        val torrc = TorService.getTorrc(appContext)
        AndroidTorControlConnection(File(torrc.parentFile, "data/ControlSocket").absolutePath)
    })

    private fun owns(lifecycle: Long, binding: Long): Boolean = desired.get() && recovery.isCurrent(lifecycle) && bindingGeneration.get() == binding

    private fun createRegistration(lifecycle: Long, binding: Long): Registration {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val nativeService = (binder as? TorService.LocalBinder)?.service
                // A stop may invalidate publication before this callback. Retain the owned
                // service anyway so retirement can prove its native thread has terminated.
                registration?.takeIf { it.connection === this }?.service = nativeService
                if (!owns(lifecycle, binding)) return
                invalidateReadiness(allowProbes = true)
                service = nativeService
                monitorReadiness()
                updateReady()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (!owns(lifecycle, binding)) return
                service = null
                fail(lifecycle, "Tor service disconnected")
            }
            override fun onBindingDied(name: ComponentName?) {
                if (owns(lifecycle, binding)) fail(lifecycle, "Tor service binding lost")
            }
            override fun onNullBinding(name: ComponentName?) {
                if (owns(lifecycle, binding)) fail(lifecycle, "Tor service binding rejected")
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!owns(lifecycle, binding)) return
                if (intent?.action == TorService.ACTION_ERROR) {
                    // Tor's raw exception may contain filesystem paths or endpoint identifiers.
                    fail(lifecycle, "Embedded Tor failed")
                    return
                }
                when (intent?.getStringExtra(TorService.EXTRA_STATUS)) {
                    TorService.STATUS_STARTING -> {
                        invalidateReadiness()
                        _state.value = TorConnectionState.Starting
                    }
                    TorService.STATUS_ON -> {
                        if (readinessGate.ticket() == null) {
                            invalidateReadiness(allowProbes = true)
                            monitorReadiness()
                        }
                        torReportedOn = true
                        updateReady()
                    }
                    TorService.STATUS_STOPPING, TorService.STATUS_OFF -> fail(lifecycle, "Embedded Tor stopped")
                }
            }
        }
        return Registration(connection, receiver)
    }

    private fun fail(lifecycle: Long, reason: String) {
        if (!desired.get() || !recovery.isCurrent(lifecycle)) return
        invalidateReadiness()
        _state.value = TorConnectionState.Failed(reason)
        failures.trySend(lifecycle)
    }

    private fun updateReady() {
        val socksPort = service?.socksPort ?: 0
        if (torReportedOn && bootstrapComplete && socksAvailable && socksPort > 0 && onionEndpointManager.isListening()) {
            val onion = onionEndpointManager.onionAddress()
            _state.value = if (onion == null) TorConnectionState.OnionPublishing else TorConnectionState.Ready(socksPort, onion)
        } else if (_state.value is TorConnectionState.Ready) {
            _state.value = TorConnectionState.Failed("Tor local readiness lost")
        }
    }

    private fun monitorReadiness() {
        readinessMonitor?.cancel()
        val epoch = readinessGate.ticket() ?: return
        readinessMonitor = monitorScope.launch {
            while (isActive && bound.get() && readinessGate.isCurrent(epoch)) {
                val activeService = service
                val bootstrapped = activeService != null && activeService.socksPort > 0 && controlProbe.bootstrapComplete()
                val socksResponding = bootstrapped && localSocksResponds(activeService!!.socksPort)
                ensureActive()
                readinessGate.publish(epoch) {
                    if (service === activeService) {
                        bootstrapComplete = bootstrapped
                        socksAvailable = socksResponding
                        if (bootstrapped) torReportedOn = true
                        updateReady()
                    }
                }
                delay(if (_state.value is TorConnectionState.Ready) 10_000 else 1_000)
            }
        }
    }

    private fun invalidateReadiness(allowProbes: Boolean = false) {
        readinessGate.reset(allowProbes) {
            torReportedOn = false
            bootstrapComplete = false
            socksAvailable = false
        }
        readinessMonitor?.cancel()
        controlProbe.cancelPending()
    }

    private fun localSocksResponds(port: Int): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
            socket.soTimeout = 500
            socket.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
            val input = socket.getInputStream()
            input.read() == 5 && input.read() == 0
        }
    }.getOrDefault(false)

    /** Explicit retry preserves identity and queued ciphertext while renewing the repair budget. */
    fun retry(): Boolean = synchronized(lifecycleGuard) {
        if (_state.value !is TorConnectionState.Failed && _state.value !is TorConnectionState.Stopped) {
            return@synchronized false
        }
        // Explicit user retry gets a fresh budget. Normal service starts remain idempotent.
        // startOwnedService serializes retirement before creating another daemon.
        stop()
        start()
        true
    }

    /** One supervisor per requested lifecycle; startup/probe loss gets a bounded recovery budget. */
    fun start() = synchronized(lifecycleGuard) {
        if (!desired.compareAndSet(false, true)) return@synchronized
        val lifecycle = recovery.start()
        _state.value = TorConnectionState.Starting
        supervisor = monitorScope.launch {
            var started = startOwnedService(lifecycle)
            var unhealthySince = System.nanoTime()
            while (isActive && recovery.isCurrent(lifecycle)) {
                if (_state.value is TorConnectionState.Ready) {
                    recovery.healthy(lifecycle)
                    unhealthySince = System.nanoTime()
                } else recovery.unhealthy(lifecycle)
                val failed = failures.tryReceive().getOrNull() == lifecycle
                if (!started || failed || System.nanoTime() - unhealthySince >= 120_000_000_000L) {
                    recovery.unhealthy(lifecycle)
                    val wait = recovery.nextDelay(lifecycle)
                    if (wait == null) {
                        if (recovery.isCurrent(lifecycle)) _state.value = TorConnectionState.Failed("Tor recovery budget exhausted; restart Tor to retry")
                        break
                    }
                    delay(wait)
                    if (!recovery.isCurrent(lifecycle)) break
                    started = startOwnedService(lifecycle)
                    unhealthySince = System.nanoTime()
                    continue
                }
                delay(1_000)
            }
        }
    }

    private suspend fun startOwnedService(lifecycle: Long): Boolean = serviceActions.withLock {
        if (!desired.get() || !recovery.isCurrent(lifecycle)) return@withLock false
        try {
            if (registration != null && !retireService()) return@withLock false
            if (!desired.get() || !recovery.isCurrent(lifecycle)) return@withLock false
            val torrc = TorService.getTorrc(appContext)
            torrc.parentFile?.mkdirs()
            val controlSocket = File(torrc.parentFile, "data/ControlSocket")
            if (controlSocket.exists()) check(controlSocket.delete()) { "Stale Tor control socket cannot be removed" }
            // Preserve Tor data and onion identity. The pinned library uses this private,
            // empty-credential control socket; no TCP control listener is exposed.
            torrc.writeText((onionEndpointManager.torrcLines() + "CookieAuthentication 0").joinToString("\n", postfix = "\n"))
            withContext(Dispatchers.Main.immediate) {
                if (!desired.get() || !recovery.isCurrent(lifecycle)) return@withContext false
                val binding = bindingGeneration.incrementAndGet()
                val owned = createRegistration(lifecycle, binding)
                registration = owned
                invalidateReadiness(allowProbes = true)
                _state.value = TorConnectionState.Starting
                LocalBroadcastManager.getInstance(appContext).registerReceiver(owned.receiver,
                    IntentFilter(TorService.ACTION_STATUS).apply { addAction(TorService.ACTION_ERROR) })
                owned.bound = appContext.bindService(Intent(appContext, TorService::class.java).apply {
                    action = TorService.ACTION_START
                }, owned.connection, Context.BIND_AUTO_CREATE)
                bound.set(owned.bound)
                if (owned.bound) monitorReadiness()
                else {
                    LocalBroadcastManager.getInstance(appContext).unregisterReceiver(owned.receiver)
                    registration = null
                    _state.value = TorConnectionState.Failed("Unable to bind embedded Tor service")
                }
                owned.bound
            }
        } catch (cancelled: CancellationException) {
            // start/stop can race while local listener setup is running on this I/O coroutine.
            // The next lifecycle cannot enter serviceActions until this cleanup releases it.
            if (!recovery.isCurrent(lifecycle)) onionEndpointManager.stop()
            throw cancelled
        }
        catch (_: Exception) {
            if (recovery.isCurrent(lifecycle)) _state.value = TorConnectionState.Failed("Unable to start embedded Tor")
            false
        }
    }

    /** Require native completion as well as socket closure before unbinding/replacing the daemon. */
    private suspend fun retireService(): Boolean {
        val owned = registration ?: return true
        withContext(Dispatchers.Main.immediate) {
            bindingGeneration.incrementAndGet()
            invalidateReadiness()
            LocalBroadcastManager.getInstance(appContext).unregisterReceiver(owned.receiver)
        }
        val stopped = withTimeoutOrNull(5_000) {
            var confirmed = false
            while (!confirmed) {
                // During startup the control socket can be absent while runMain is still alive.
                // Keep the binding and retry HALT until that same native thread is TERMINATED.
                controlProbe.halt()
                confirmed = TorNativeLifecycle.forService(owned.service).terminated() && controlProbe.endpointClosed()
                if (!confirmed) delay(100)
            }
            true
        } ?: false
        if (!stopped) return false
        withContext(Dispatchers.Main.immediate) {
            if (owned.bound) runCatching { appContext.unbindService(owned.connection) }
            owned.bound = false
            bound.set(false)
            if (registration === owned) registration = null
            service = null
        }
        // Native completion is proven above; this only lets Android deliver service destruction.
        delay(250)
        return true
    }

    suspend fun awaitReady(timeoutMs: Long = 120_000): Boolean = withTimeoutOrNull(timeoutMs) {
        state.first { it is TorConnectionState.Ready }; true
    } ?: false
    fun socksPort(): Int = (state.value as? TorConnectionState.Ready)?.socksPort ?: 0
    fun onionAddress(): String? = onionEndpointManager.onionAddress()

    fun stop() {
        synchronized(lifecycleGuard) {
            desired.set(false)
            recovery.stop()
            bindingGeneration.incrementAndGet()
            supervisor?.cancel()
            invalidateReadiness()
            _state.value = TorConnectionState.Stopped
            onionEndpointManager.stop()
            monitorScope.launch {
                serviceActions.withLock {
                    if (!desired.get() && !retireService()) _state.value = TorConnectionState.Failed("Tor shutdown could not be confirmed")
                }
            }
        }
    }
}
