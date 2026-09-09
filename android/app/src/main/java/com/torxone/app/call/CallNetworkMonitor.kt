package com.torxone.app.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monitors default network transitions (e.g. Wi-Fi <-> Cellular handover) during active calls.
 * Fires a debounced/coalesced handover callback so WebRTC can trigger a single ICE restart
 * rather than thrashing multiple restarts during rapid network flaps.
 */
class CallNetworkMonitor(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val debounceMs: Long = 1200L,
    private val onNetworkHandover: () -> Unit
) {
    companion object {
        private const val TAG = "CallNetworkMonitor"
    }

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val isRegistered = AtomicBoolean(false)
    private var lastNetworkId: Long? = null
    private var debounceJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val netId = try {
                val handle = network.networkHandle
                if (handle != 0L) handle else network.hashCode().toLong()
            } catch (e: Throwable) {
                network.hashCode().toLong()
            }
            Log.d(TAG, "Network available: $netId (previous: $lastNetworkId)")
            if (lastNetworkId != null && lastNetworkId != netId) {
                Log.i(TAG, "Network handover detected from $lastNetworkId to $netId (debouncing ${debounceMs}ms)")
                scheduleHandover()
            }
            lastNetworkId = netId
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: ${network.networkHandle}")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            Log.d(TAG, "Network capabilities changed: internet=$hasInternet, wifi=$isWifi, cell=$isCellular")
        }
    }

    private fun scheduleHandover() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            Log.i(TAG, "Executing debounced network handover callback")
            onNetworkHandover()
        }
    }

    fun start() {
        if (connectivityManager == null) {
            Log.w(TAG, "ConnectivityManager unavailable, cannot monitor network")
            return
        }
        if (isRegistered.getAndSet(true)) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
                Log.d(TAG, "Default network callback registered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
            isRegistered.set(false)
        }
    }

    fun stop() {
        debounceJob?.cancel()
        debounceJob = null
        if (!isRegistered.getAndSet(false)) return
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Network callback unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback: ${e.message}")
        }
        lastNetworkId = null
    }
}
