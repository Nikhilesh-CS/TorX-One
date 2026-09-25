package com.torxone.app.transport.nearby

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

enum class RouteState {
    CONNECTING,
    AUTHENTICATING,
    READY,
    STALE,
    DISCONNECTED
}

/**
 * Direct route tracking for Nearby peer sessions.
 */
data class NearbyRoute(
    val relationshipId: String,
    val endpointId: String,
    val state: RouteState,
    val protocolVersion: Int = NEARBY_PROTOCOL_VERSION,
    val maxFrameSize: Int = MAX_DIRECT_FRAME_SIZE,
    val lastSeen: Long = System.currentTimeMillis(),
    val sendQueueId: String? = null,
    val recvQueueId: String? = null
)

/**
 * DirectRouteTable — maintains exact endpoint-to-relationship and queue-to-endpoint bindings.
 *
 * Ensures:
 * - Exactly authenticated routes carry normal message traffic
 * - Zero packet misdirection in multi-peer topologies
 * - Fast queue address resolution
 * - Thread-safe updates across connection lifecycles
 */
class DirectRouteTable {
    companion object {
        private const val TAG = "DirectRouteTable"
    }

    // relationshipId -> NearbyRoute
    private val routesByRelationship = ConcurrentHashMap<String, NearbyRoute>()
    // endpointId -> relationshipId
    private val endpointToRelationship = ConcurrentHashMap<String, String>()
    // queueAddress -> endpointId
    private val queueToEndpoint = ConcurrentHashMap<String, String>()
    // endpointId -> set of queueAddresses
    private val endpointToQueues = ConcurrentHashMap<String, MutableSet<String>>()
    // endpointId -> lastSeen
    private val endpointLastSeen = ConcurrentHashMap<String, Long>()

    fun registerEndpoint(endpointId: String) {
        endpointLastSeen[endpointId] = System.currentTimeMillis()
    }

    fun bindRoute(
        relationshipId: String,
        endpointId: String,
        state: RouteState,
        protocolVersion: Int = NEARBY_PROTOCOL_VERSION,
        maxFrameSize: Int = MAX_DIRECT_FRAME_SIZE,
        sendQueueId: String? = null,
        recvQueueId: String? = null
    ) {
        val now = System.currentTimeMillis()
        val route = NearbyRoute(
            relationshipId = relationshipId,
            endpointId = endpointId,
            state = state,
            protocolVersion = protocolVersion,
            maxFrameSize = maxFrameSize,
            lastSeen = now,
            sendQueueId = sendQueueId,
            recvQueueId = recvQueueId
        )
        routesByRelationship[relationshipId] = route
        endpointToRelationship[endpointId] = relationshipId
        endpointLastSeen[endpointId] = now

        if (sendQueueId != null) {
            bindQueue(sendQueueId, endpointId)
        }
        if (recvQueueId != null) {
            bindQueue(recvQueueId, endpointId)
        }
        notifyRouteChanged(relationshipId, state, now)
    }

    private val routeListeners = java.util.concurrent.CopyOnWriteArrayList<(relationshipId: String, state: RouteState, lastSeen: Long) -> Unit>()

    fun addRouteListener(listener: (relationshipId: String, state: RouteState, lastSeen: Long) -> Unit) {
        routeListeners.add(listener)
    }

    fun removeRouteListener(listener: (relationshipId: String, state: RouteState, lastSeen: Long) -> Unit) {
        routeListeners.remove(listener)
    }

    private fun notifyRouteChanged(relationshipId: String, state: RouteState, lastSeen: Long) {
        for (listener in routeListeners) {
            try {
                listener(relationshipId, state, lastSeen)
            } catch (e: Exception) {
                Log.e(TAG, "Error in route listener for relationship $relationshipId: ${e.message}", e)
            }
        }
    }

    fun bindQueue(queueAddress: String, endpointId: String) {
        queueToEndpoint[queueAddress] = endpointId
        endpointToQueues.computeIfAbsent(endpointId) { ConcurrentHashMap.newKeySet() }.add(queueAddress)
    }

    fun getRouteByRelationship(relationshipId: String): NearbyRoute? = routesByRelationship[relationshipId]

    fun getEndpointForQueue(queueAddress: String): String? = queueToEndpoint[queueAddress]

    fun getRelationshipForEndpoint(endpointId: String): String? = endpointToRelationship[endpointId]

    fun updateLastSeen(endpointId: String, time: Long = System.currentTimeMillis()) {
        endpointLastSeen[endpointId] = time
        val relId = endpointToRelationship[endpointId]
        if (relId != null) {
            routesByRelationship[relId]?.let {
                routesByRelationship[relId] = it.copy(lastSeen = time)
            }
        }
    }

    fun getLastSeen(endpointId: String): Long = endpointLastSeen[endpointId] ?: 0L

    fun getAllEndpoints(): Set<String> = endpointLastSeen.keys.toSet()

    fun isReady(relationshipId: String): Boolean =
        routesByRelationship[relationshipId]?.state == RouteState.READY

    fun isEndpointReady(endpointId: String): Boolean {
        val relId = endpointToRelationship[endpointId] ?: return false
        return routesByRelationship[relId]?.state == RouteState.READY
    }

    fun markStale(endpointId: String) {
        val relId = endpointToRelationship[endpointId] ?: return
        val now = System.currentTimeMillis()
        routesByRelationship[relId]?.let {
            routesByRelationship[relId] = it.copy(state = RouteState.STALE)
        }
        notifyRouteChanged(relId, RouteState.STALE, now)
    }

    fun removeEndpoint(endpointId: String): Set<String> {
        val now = System.currentTimeMillis()
        endpointLastSeen.remove(endpointId)
        val relId = endpointToRelationship.remove(endpointId)
        val affected = mutableSetOf<String>()
        if (relId != null) {
            routesByRelationship.remove(relId)
            affected.add(relId)
            notifyRouteChanged(relId, RouteState.DISCONNECTED, now)
        }
        val queues = endpointToQueues.remove(endpointId)
        queues?.forEach { queueToEndpoint.remove(it) }
        return affected
    }

    fun clear() {
        val now = System.currentTimeMillis()
        val relationships = routesByRelationship.keys.toList()
        routesByRelationship.clear()
        endpointToRelationship.clear()
        queueToEndpoint.clear()
        endpointToQueues.clear()
        endpointLastSeen.clear()
        for (rel in relationships) {
            notifyRouteChanged(rel, RouteState.DISCONNECTED, now)
        }
    }

    fun getAllReadyRoutes(): List<NearbyRoute> =
        routesByRelationship.values.filter { it.state == RouteState.READY }
}
