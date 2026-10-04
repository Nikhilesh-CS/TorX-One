package com.torxone.app.transport.tor

import com.torxone.app.data.dao.ConnectionDao
import com.torxone.app.data.dao.PeerTorEndpointDao
import com.torxone.app.data.entity.PeerTorEndpointEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PeerTorEndpointRepository(private val dao: PeerTorEndpointDao) {
    private val endpoints = ConcurrentHashMap<String, TorRoute>()
    private val writes = Mutex()
    @Volatile var onVerifiedEndpointChanged: (String) -> Unit = {}

    fun resolve(relationshipId: String): TorRoute? = endpoints[relationshipId]

    /** Call only after the signature and relationship binding have been verified. */
    suspend fun bindVerified(relationshipId: String, route: TorRoute) {
        writes.withLock {
            dao.upsert(entity(relationshipId, route))
            endpoints[relationshipId] = route
        }
        // An authenticated rescan is also an explicit recovery probe when the onion is unchanged.
        onVerifiedEndpointChanged(relationshipId)
    }

    /** A user reviewed this exact route transition. Reject an intervening change. */
    suspend fun bindVerifiedIfUnchanged(relationshipId: String, route: TorRoute, expected: TorRoute?): Boolean {
        var cacheChanged = false
        val accepted = writes.withLock {
            val row = dao.getByRelationshipId(relationshipId)
            val current = row?.let { TorRoute(it.onionAddress, it.port) }
            if (current != expected && current != route) {
                cacheChanged = if (current == null) endpoints.remove(relationshipId) != null
                    else endpoints.put(relationshipId, current) != current
                false
            } else {
                dao.upsert(entity(relationshipId, route))
                endpoints[relationshipId] = route
                true
            }
        }
        if (accepted || cacheChanged) onVerifiedEndpointChanged(relationshipId)
        return accepted
    }

    /**
     * Legacy bootstrap recovery may fill an absent authenticated endpoint, but a
     * replayable bootstrap has no revision and must never replace a current row.
     * Serialize the absent-row check with verified rescans and publish Room's winner.
     */
    suspend fun bindBootstrapIfMissing(relationshipId: String, route: TorRoute) {
        val changed = writes.withLock {
            val existing = dao.getByRelationshipId(relationshipId)
            val authoritative = if (existing == null) {
                dao.upsert(entity(relationshipId, route))
                route
            } else TorRoute(existing.onionAddress, existing.port)
            endpoints.put(relationshipId, authoritative) != authoritative
        }
        if (changed) onVerifiedEndpointChanged(relationshipId)
    }

    /** Publish a row written inside the pairing transaction only after that transaction commits. */
    suspend fun refresh(relationshipId: String) {
        val changed = writes.withLock {
            val previous = endpoints[relationshipId]
            val row = dao.getByRelationshipId(relationshipId)
            val route = row?.let { TorRoute(it.onionAddress, it.port) }
            if (route == null) endpoints.remove(relationshipId) else endpoints[relationshipId] = route
            previous != route
        }
        if (changed) onVerifiedEndpointChanged(relationshipId)
    }

    /** Cache miss recovery reads this relationship's authoritative Room row only. */
    suspend fun resolvePersisted(relationshipId: String): TorRoute? {
        resolve(relationshipId)?.let { return it }
        refresh(relationshipId)
        return resolve(relationshipId)
    }

    suspend fun restore(connections: ConnectionDao, legacy: TorRouteManager) = writes.withLock {
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
