package com.torxone.app.transport.tor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Process lifetime observer of Android's validated default Internet network. */
class NetworkRecoveryMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val transport: TorTransport,
    private val retry: suspend () -> Unit,
    private val closeIncoming: () -> Unit = {}
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var retryJob: Job? = null
    private var validatedNetwork: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (validated && validatedNetwork != network) {
                validatedNetwork = network
                transport.closePendingConnections()
                closeIncoming()
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(750)
                    android.util.Log.i("NetworkRecovery", "Validated default network changed; retrying pending delivery")
                    retry()
                }
            } else if (!validated && validatedNetwork == network) {
                validatedNetwork = null
                retryJob?.cancel()
                transport.closePendingConnections()
                closeIncoming()
            }
        }

        override fun onLost(network: Network) {
            if (validatedNetwork == network) {
                validatedNetwork = null
                retryJob?.cancel()
                transport.closePendingConnections()
                closeIncoming()
            }
        }
    }

    fun start() = connectivity.registerDefaultNetworkCallback(callback)
}
