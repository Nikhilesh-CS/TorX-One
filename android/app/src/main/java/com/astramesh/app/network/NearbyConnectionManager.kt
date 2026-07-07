package com.astramesh.app.network

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NearbyDevice(
    val endpointId: String,
    val name: String
)

data class ConnectionRequest(
    val endpointId: String,
    val name: String
)

class NearbyConnectionManager(private val context: Context) {
    companion object {
        private const val TAG = "NearbyConn"
        private const val SERVICE_ID = "com.astramesh.app.nearby"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }

    private val _nearbyDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearbyDevices: StateFlow<List<NearbyDevice>> = _nearbyDevices

    private val _pendingRequests = MutableStateFlow<List<ConnectionRequest>>(emptyList())
    val pendingRequests: StateFlow<List<ConnectionRequest>> = _pendingRequests

    private val _connectedEndpoints = MutableStateFlow<Set<String>>(emptySet())
    val connectedEndpoints: StateFlow<Set<String>> = _connectedEndpoints

    private val _connectionStatus = MutableStateFlow("Idle")
    val connectionStatus: StateFlow<String> = _connectionStatus

    var onMessageReceived: ((endpointId: String, message: String) -> Unit)? = null
    var onConnectionEstablished: ((endpointId: String, name: String) -> Unit)? = null
    var onDisconnected: ((endpointId: String) -> Unit)? = null

    private var localName: String = "Unknown"
    private val endpointNames = mutableMapOf<String, String>()

    fun setLocalName(name: String) {
        localName = name
    }

    fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startAdvertising(
            localName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Advertising started")
            _connectionStatus.value = "Advertising"
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed", e)
            _connectionStatus.value = "Advertising failed"
        }
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Discovery started")
            _connectionStatus.value = "Discovering"
        }.addOnFailureListener { e ->
            Log.e(TAG, "Discovery failed", e)
            _connectionStatus.value = "Discovery failed"
        }
    }

    fun requestConnection(endpointId: String) {
        connectionsClient.requestConnection(
            localName,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            Log.d(TAG, "Connection requested to $endpointId")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Connection request failed", e)
        }
    }

    fun acceptConnection(endpointId: String) {
        connectionsClient.acceptConnection(endpointId, payloadCallback)
        _pendingRequests.value = _pendingRequests.value.filter { it.endpointId != endpointId }
    }

    fun rejectConnection(endpointId: String) {
        connectionsClient.rejectConnection(endpointId)
        _pendingRequests.value = _pendingRequests.value.filter { it.endpointId != endpointId }
    }

    fun sendRaw(endpointId: String, data: String) {
        try {
            val payload = Payload.fromBytes(data.toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send raw payload to $endpointId", e)
        }
    }

    fun sendMessage(endpointId: String, message: String) {
        sendRaw(endpointId, message)
    }

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _nearbyDevices.value = emptyList()
        _connectedEndpoints.value = emptySet()
        _connectionStatus.value = "Stopped"
        endpointNames.clear()
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Found: $endpointId - ${info.endpointName}")
            endpointNames[endpointId] = info.endpointName
            val device = NearbyDevice(endpointId, info.endpointName)
            val current = _nearbyDevices.value.toMutableList()
            if (current.none { it.endpointId == endpointId }) {
                current.add(device)
                _nearbyDevices.value = current
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost: $endpointId")
            _nearbyDevices.value = _nearbyDevices.value.filter { it.endpointId != endpointId }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated from: ${info.endpointName}")
            endpointNames[endpointId] = info.endpointName
            if (info.isIncomingConnection) {
                val request = ConnectionRequest(endpointId, info.endpointName)
                val current = _pendingRequests.value.toMutableList()
                current.add(request)
                _pendingRequests.value = current
            } else {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d(TAG, "Connected to $endpointId")
                    _connectedEndpoints.value = _connectedEndpoints.value + endpointId
                    _nearbyDevices.value = _nearbyDevices.value.filter { it.endpointId != endpointId }
                    onConnectionEstablished?.invoke(endpointId, endpointNames[endpointId] ?: endpointId)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d(TAG, "Connection rejected by $endpointId")
                }
                else -> {
                    Log.d(TAG, "Connection failed to $endpointId")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            _connectedEndpoints.value = _connectedEndpoints.value - endpointId
            onDisconnected?.invoke(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val message = String(payload.asBytes()!!, Charsets.UTF_8)
                if (message.length > MeshProtocol.MAX_FRAME_BYTES) {
                    Log.w(TAG, "Dropping oversized payload from $endpointId")
                    return
                }
                Log.d(TAG, "Received ${message.length} bytes from $endpointId")
                onMessageReceived?.invoke(endpointId, message)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Transfer updates
        }
    }
}
