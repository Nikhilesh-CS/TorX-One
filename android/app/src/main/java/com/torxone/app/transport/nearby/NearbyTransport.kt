package com.torxone.app.transport.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.torxone.app.incoming.IncomingTransportHub
import com.torxone.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * NearbyTransport — Google Nearby Connections implementation.
 *
 * Enforces critical invariant:
 * connectionsClient.sendPayload() does NOT equal success.
 * Only PayloadTransferUpdate.Status.SUCCESS completes the send as TransportResult.Accepted!
 */
class NearbyTransport(
    private val context: Context,
    private val incomingTransportHub: IncomingTransportHub,
    private val localEndpointName: String = "TorXPeer"
) : Transport {

    companion object {
        private const val TAG = "NearbyTransport"
        private const val SERVICE_ID = "com.torxone.mesh"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    override val type: TransportType = TransportType.NEARBY

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingTransfers = ConcurrentHashMap<Long, CompletableDeferred<TransportResult>>()

    private val _availability = MutableStateFlow<TransportAvailability>(TransportAvailability.Unavailable("Not started"))
    override fun availability(): Flow<TransportAvailability> = _availability.asStateFlow()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                Log.d(TAG, "[RX] Received ${bytes.size} bytes from endpoint $endpointId")
                scope.launch {
                    incomingTransportHub.onRawFrameReceived(bytes, TransportType.NEARBY)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val deferred = pendingTransfers[update.payloadId] ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    Log.i(TAG, "[TX COMPLETE] Payload ${update.payloadId} transferred successfully to $endpointId")
                    deferred.complete(TransportResult.Accepted(TransportType.NEARBY))
                    pendingTransfers.remove(update.payloadId)
                }
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    Log.w(TAG, "[TX FAIL] Payload ${update.payloadId} failed: status=${update.status}")
                    deferred.complete(TransportResult.Failed(TransportType.NEARBY, "Transfer failed with status ${update.status}"))
                    pendingTransfers.remove(update.payloadId)
                }
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // Transfer in progress
                }
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d(TAG, "Connection initiated from $endpointId (${connectionInfo.endpointName}), accepting")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                Log.i(TAG, "Connected to endpoint: $endpointId")
                connectedEndpoints.add(endpointId)
                updateAvailability()
            } else {
                Log.w(TAG, "Connection failed to endpoint: $endpointId (${resolution.status.statusMessage})")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "Disconnected from endpoint: $endpointId")
            connectedEndpoints.remove(endpointId)
            updateAvailability()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Discovered endpoint $endpointId (${info.endpointName}), requesting connection")
            connectionsClient.requestConnection(
                localEndpointName,
                endpointId,
                connectionLifecycleCallback
            )
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost endpoint: $endpointId")
        }
    }

    fun start() {
        Log.i(TAG, "Starting Nearby advertising and discovery")
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            localEndpointName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnFailureListener { e ->
            Log.e(TAG, "Failed to start advertising: ${e.message}")
        }

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnFailureListener { e ->
            Log.e(TAG, "Failed to start discovery: ${e.message}")
        }

        updateAvailability()
    }

    fun stop() {
        Log.i(TAG, "Stopping Nearby transport")
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        updateAvailability()
    }

    override suspend fun send(
        destination: TransportDestination,
        payload: ByteArray
    ): TransportResult {
        val targetEndpoint = connectedEndpoints.firstOrNull()
            ?: return TransportResult.Failed(type, "No Nearby endpoints currently connected")

        val nearbyPayload = Payload.fromBytes(payload)
        val deferred = CompletableDeferred<TransportResult>()
        pendingTransfers[nearbyPayload.id] = deferred

        try {
            connectionsClient.sendPayload(targetEndpoint, nearbyPayload)
                .addOnFailureListener { e ->
                    Log.e(TAG, "sendPayload call failed: ${e.message}")
                    deferred.complete(TransportResult.Failed(type, e.message ?: "sendPayload failed"))
                    pendingTransfers.remove(nearbyPayload.id)
                }

            // Wait for PayloadTransferUpdate.Status.SUCCESS callback
            return withTimeout(30_000L) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingTransfers.remove(nearbyPayload.id)
            return TransportResult.Failed(type, "Transfer timed out awaiting SUCCESS callback")
        } catch (e: Exception) {
            pendingTransfers.remove(nearbyPayload.id)
            return TransportResult.Failed(type, e.message ?: "Send exception")
        }
    }

    private fun updateAvailability() {
        _availability.value = if (connectedEndpoints.isNotEmpty()) {
            TransportAvailability.Available
        } else {
            TransportAvailability.Unavailable("No connected Nearby peers")
        }
    }
}
