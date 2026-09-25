package com.torxone.app.transport.nearby

import android.util.Log
import com.google.android.gms.nearby.connection.*

/**
 * Adapter interface decoupling Google Play Services Nearby Connections Client.
 * Enables production GmsNearbyConnectionsAdapter and in-memory simulated test adapters.
 */
interface NearbyConnectionsAdapter {
    fun startAdvertising(endpointName: String, serviceId: String, callback: ConnectionLifecycleCallback): Boolean
    fun startDiscovery(serviceId: String, callback: EndpointDiscoveryCallback): Boolean
    fun stopAdvertising()
    fun stopDiscovery()
    fun stopAllEndpoints()
    fun requestConnection(endpointName: String, endpointId: String, callback: ConnectionLifecycleCallback): Boolean
    fun acceptConnection(endpointId: String, callback: PayloadCallback): Boolean
    fun rejectConnection(endpointId: String): Boolean
    fun disconnectFromEndpoint(endpointId: String)
    fun sendPayload(endpointId: String, payload: Payload, onFailure: (Exception) -> Unit)
}

class GmsNearbyConnectionsAdapter(
    private val client: ConnectionsClient
) : NearbyConnectionsAdapter {

    companion object {
        private const val TAG = "GmsNearbyAdapter"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    override fun startAdvertising(endpointName: String, serviceId: String, callback: ConnectionLifecycleCallback): Boolean {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(endpointName, serviceId, callback, options)
            .addOnFailureListener { e -> Log.e(TAG, "startAdvertising failed: ${e.message}") }
        return true
    }

    override fun startDiscovery(serviceId: String, callback: EndpointDiscoveryCallback): Boolean {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(serviceId, callback, options)
            .addOnFailureListener { e -> Log.e(TAG, "startDiscovery failed: ${e.message}") }
        return true
    }

    override fun stopAdvertising() {
        client.stopAdvertising()
    }

    override fun stopDiscovery() {
        client.stopDiscovery()
    }

    override fun stopAllEndpoints() {
        client.stopAllEndpoints()
    }

    override fun requestConnection(endpointName: String, endpointId: String, callback: ConnectionLifecycleCallback): Boolean {
        client.requestConnection(endpointName, endpointId, callback)
            .addOnFailureListener { e -> Log.e(TAG, "requestConnection failed: ${e.message}") }
        return true
    }

    override fun acceptConnection(endpointId: String, callback: PayloadCallback): Boolean {
        client.acceptConnection(endpointId, callback)
            .addOnFailureListener { e -> Log.e(TAG, "acceptConnection failed: ${e.message}") }
        return true
    }

    override fun rejectConnection(endpointId: String): Boolean {
        client.rejectConnection(endpointId)
            .addOnFailureListener { e -> Log.e(TAG, "rejectConnection failed: ${e.message}") }
        return true
    }

    override fun disconnectFromEndpoint(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
    }

    override fun sendPayload(endpointId: String, payload: Payload, onFailure: (Exception) -> Unit) {
        client.sendPayload(endpointId, payload)
            .addOnFailureListener(onFailure)
    }
}
