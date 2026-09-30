package com.torxone.app.transport.tor

import com.torxone.app.data.dao.ConnectionDao
import com.torxone.app.data.dao.PeerTorEndpointDao
import com.torxone.app.data.entity.PeerTorEndpointEntity
import java.util.concurrent.ConcurrentHashMap

class PeerTorEndpointRepository(private val dao: PeerTorEndpointDao) {
    private val endpoints = ConcurrentHashMap<String, TorRoute>()

    fun resolve(relationshipId: String): TorRoute? = endpoints[relationshipId]

    /** Call only after the signature and relationship binding have been verified. */
    suspend fun bindVerified(relationshipId: String, route: TorRoute) {
        dao.upsert(entity(relationshipId, route))
        endpoints[relationshipId] = route
    }

    /** Publish a row written inside the pairing transaction only after that transaction commits. */
    suspend fun refresh(relationshipId: String) {
        val row = dao.getByRelationshipId(relationshipId)
        if (row == null) endpoints.remove(relationshipId)
        else endpoints[relationshipId] = TorRoute(row.onionAddress, row.port)
    }

    suspend fun restore(connections: ConnectionDao, legacy: TorRouteManager) {
        // Import existing installations once. A signed Room endpoint always wins over legacy prefs.
        for (connection in connections.getAllActive()) {
            if (dao.getByRelationshipId(connection.relationshipId) == null) {
                legacy.resolve(connection.sendQueueId)?.let { route ->
                    dao.upsert(entity(connection.relationshipId, route).copy(source = "LEGACY_ROUTE"))
                }
            }
            if (dao.getByRelationshipId(connection.relationshipId) != null) legacy.remove(connection.sendQueueId)
        }
        endpoints.clear()
        dao.getAll().forEach { endpoints[it.relationshipId] = TorRoute(it.onionAddress, it.port) }
    }

    companion object {
        fun entity(relationshipId: String, route: TorRoute) = PeerTorEndpointEntity(
            relationshipId = relationshipId, onionAddress = route.onionHost, port = route.port
        )
    }
}
