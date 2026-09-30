package com.torxone.app.transport.tor

import android.content.*
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.torproject.jni.TorService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import java.io.File

class TorController(
    private val context: Context,
    private val onionEndpointManager: OnionEndpointManager
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<TorConnectionState>(TorConnectionState.Stopped)
    val state: StateFlow<TorConnectionState> = _state.asStateFlow()
    private val bound = AtomicBoolean(false)
    @Volatile private var service: TorService? = null
    private var readySignal = CompletableDeferred<Unit>()
    @Volatile private var torReportedOn = false
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readinessMonitor: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
            updateReady()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound.set(false)
            _state.value = TorConnectionState.Failed("Tor service disconnected")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TorService.ACTION_ERROR) {
                val reason = intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Embedded Tor failed to start"
                android.util.Log.e("TorController", reason)
                _state.value = TorConnectionState.Failed(reason)
                return
            }
            android.util.Log.i("TorController", "Tor status=${intent?.getStringExtra(TorService.EXTRA_STATUS)}")
            when (intent?.getStringExtra(TorService.EXTRA_STATUS)) {
                TorService.STATUS_STARTING -> _state.value = TorConnectionState.Starting
                TorService.STATUS_ON -> {
                    torReportedOn = true
                    updateReady()
                }
                TorService.STATUS_STOPPING, TorService.STATUS_OFF -> {
                    torReportedOn = false
                    _state.value = TorConnectionState.Stopped
                }
            }
        }
    }

    private fun updateReady() {
        val socksPort = service?.socksPort ?: 0
        if (torReportedOn && socksPort > 0) {
            _state.value = TorConnectionState.Ready(socksPort, onionEndpointManager.onionAddress())
            readySignal.complete(Unit)
        }
    }

    private fun monitorReadiness() {
        readinessMonitor?.cancel()
        readinessMonitor = monitorScope.launch {
            while (isActive && bound.get()) {
                val activeService = service
                if (activeService != null && activeService.socksPort > 0) {
                    val bootstrapped = runCatching {
                        activeService.getInfo("status/bootstrap-phase").contains("PROGRESS=100")
                    }.getOrDefault(false)
                    if (bootstrapped) {
                        torReportedOn = true
                        updateReady()
                    }
                }
                delay(1_000)
            }
        }
    }

    fun start() {
        if (bound.get()) return
        _state.value = TorConnectionState.Starting
        torReportedOn = false
        readySignal = CompletableDeferred()
        val torrc = TorService.getTorrc(appContext)
        torrc.parentFile?.mkdirs()
        // The embedded library observes socket creation, but a stale socket from a
        // previous process can be opened before the new Tor instance binds it.
        // Remove only the socket node; preserve Tor state and onion identity keys.
        val controlSocket = File(torrc.parentFile, "data/ControlSocket")
        if (controlSocket.exists()) {
            check(controlSocket.delete()) { "Unable to remove stale Tor control socket" }
        }
        torrc.writeText(onionEndpointManager.torrcLines().joinToString("\n", postfix = "\n"))
        LocalBroadcastManager.getInstance(appContext)
            .registerReceiver(statusReceiver, IntentFilter(TorService.ACTION_STATUS).apply {
                addAction(TorService.ACTION_ERROR)
            })
        val intent = Intent(appContext, TorService::class.java).apply {
            action = TorService.ACTION_START
            putExtra(TorService.EXTRA_PACKAGE_NAME, appContext.packageName)
        }
        bound.set(appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        if (!bound.get()) _state.value = TorConnectionState.Failed("Unable to bind embedded Tor service")
        else monitorReadiness()
    }

    suspend fun awaitReady(timeoutMs: Long = 120_000): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { readySignal.await(); true } ?: false

    fun socksPort(): Int = (state.value as? TorConnectionState.Ready)?.socksPort ?: 0
    fun onionAddress(): String? = onionEndpointManager.onionAddress()

    fun stop() {
        readinessMonitor?.cancel()
        if (bound.compareAndSet(true, false)) appContext.unbindService(connection)
        runCatching { LocalBroadcastManager.getInstance(appContext).unregisterReceiver(statusReceiver) }
        service = null
        torReportedOn = false
        _state.value = TorConnectionState.Stopped
        onionEndpointManager.stop()
    }
}
