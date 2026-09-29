package com.torxone.app.transport.halow

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Finds TorX gateway services announced by an attached/local HaLow bridge. */
class AndroidHaLowGatewayDiscovery(context: Context) : HaLowGatewayDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val found = MutableSharedFlow<HaLowGatewayCandidate>(extraBufferCapacity = 16)
    override val candidates: Flow<HaLowGatewayCandidate> = found
    private var running = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) { running = false }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { running = false }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { running = false }
        @Suppress("DEPRECATION")
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType != HaLowGatewayProtocol.SERVICE_TYPE) return
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    val networkHandle = if (Build.VERSION.SDK_INT >= 34) resolved.network?.networkHandle else null
                    found.tryEmit(HaLowGatewayCandidate(resolved.serviceName, host, resolved.port, networkHandle))
                }
            })
        }
    }

    override suspend fun start() {
        if (running) return
        running = true
        nsd.discoverServices(HaLowGatewayProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override suspend fun stop() {
        if (running) runCatching { nsd.stopServiceDiscovery(listener) }
        running = false
    }
}
