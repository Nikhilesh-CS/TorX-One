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

class TorController(
    private val context: Context,
    private val onionEndpointManager: OnionEndpointManager
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<TorConnectionState>(TorConnectionState.Stopped)
    val state: StateFlow<TorConnectionState> = _state.asStateFlow()
    private val bound = AtomicBoolean(false)
    private var service: TorService? = null
    private var readySignal = CompletableDeferred<Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound.set(false)
            _state.value = TorConnectionState.Failed("Tor service disconnected")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(TorService.EXTRA_STATUS)) {
                TorService.STATUS_STARTING -> _state.value = TorConnectionState.Starting
                TorService.STATUS_ON -> {
                    val socksPort = service?.socksPort ?: 0
                    if (socksPort > 0) {
                        _state.value = TorConnectionState.Ready(socksPort, onionEndpointManager.onionAddress())
                        readySignal.complete(Unit)
                    } else _state.value = TorConnectionState.Failed("Tor started without a SOCKS listener")
                }
                TorService.STATUS_STOPPING -> _state.value = TorConnectionState.Stopped
                TorService.STATUS_OFF -> _state.value = TorConnectionState.Stopped
            }
        }
    }

    fun start() {
        if (bound.get()) return
        _state.value = TorConnectionState.Starting
        readySignal = CompletableDeferred()
        val torrc = TorService.getTorrc(appContext)
        torrc.parentFile?.mkdirs()
        torrc.writeText(onionEndpointManager.torrcLines().joinToString("\n", postfix = "\n"))
        LocalBroadcastManager.getInstance(appContext)
            .registerReceiver(statusReceiver, IntentFilter(TorService.ACTION_STATUS))
        val intent = Intent(appContext, TorService::class.java).apply {
            action = TorService.ACTION_START
            putExtra(TorService.EXTRA_PACKAGE_NAME, appContext.packageName)
        }
        bound.set(appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        if (!bound.get()) _state.value = TorConnectionState.Failed("Unable to bind embedded Tor service")
    }

    suspend fun awaitReady(timeoutMs: Long = 120_000): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { readySignal.await(); true } ?: false

    fun socksPort(): Int = (state.value as? TorConnectionState.Ready)?.socksPort ?: 0
    fun onionAddress(): String? = onionEndpointManager.onionAddress()

    fun stop() {
        if (bound.compareAndSet(true, false)) appContext.unbindService(connection)
        runCatching { LocalBroadcastManager.getInstance(appContext).unregisterReceiver(statusReceiver) }
        service = null
        _state.value = TorConnectionState.Stopped
        onionEndpointManager.stop()
    }
}
