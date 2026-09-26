package com.torxone.app.connection

import com.torxone.app.data.dao.ConnectionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active pairwise connections.
 * Maps relationshipId -> Connection and queueId -> Connection.
 */
class ConnectionManager(
    var connectionDao: ConnectionDao? = null,
    private val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
) {

    private val connectionsByRelationship = ConcurrentHashMap<String, Connection>()
    private val connectionsByRecvQueue = ConcurrentHashMap<String, Connection>()
    private val connectionsBySendQueue = ConcurrentHashMap<String, Connection>()

    private val _activeConnectionsFlow = MutableStateFlow<Map<String, Connection>>(emptyMap())
    val activeConnectionsFlow: Flow<Map<String, Connection>> = _activeConnectionsFlow.asStateFlow()

    fun registerConnection(connection: Connection) {
        connectionsByRelationship[connection.relationshipId] = connection
        connectionsByRecvQueue[connection.recvQueueId] = connection
        connectionsBySendQueue[connection.sendQueueId] = connection
        _activeConnectionsFlow.value = HashMap(connectionsByRelationship)
    }

    /**
     * Restore persisted active connections from Room database on startup (Section 6).
     */
    suspend fun restoreFromDatabase(dao: ConnectionDao) {
        this.connectionDao = dao
        val activeEntities = dao.getAllActive()
        for (entity in activeEntities) {
            registerConnection(
                Connection(
                    connectionId = entity.connectionId,
                    relationshipId = entity.relationshipId,
                    generation = entity.generation,
                    sendQueueId = entity.sendQueueId,
                    recvQueueId = entity.recvQueueId,
                    sendAuth = entity.sendAuth,
                    recvAuth = entity.recvAuth,
                    sendSequence = entity.sendSequence,
                    recvSequence = entity.recvSequence
                )
            )
        }
    }

    /**
     * Atomically increment the send sequence counter for a relationship.
     * Uses ConcurrentHashMap.compute to prevent lost updates when send and
     * receive sequences are modified concurrently from different threads.
     */
    fun incrementSendSequence(relationshipId: String): Long {
        var nextSeq = 0L
        connectionsByRelationship.compute(relationshipId) { _, existing ->
            if (existing == null) return@compute null
            nextSeq = existing.sendSequence + 1
            val updated = existing.copy(sendSequence = nextSeq)
            connectionsByRecvQueue[updated.recvQueueId] = updated
            connectionsBySendQueue[updated.sendQueueId] = updated
            updated
        } ?: return 0L
        _activeConnectionsFlow.value = HashMap(connectionsByRelationship)
        scope.launch {
            connectionDao?.updateSendSequence(relationshipId, nextSeq)
        }
        return nextSeq
    }

    /**
     * Atomically advance the receive sequence counter if the incoming sequence
     * is strictly greater than the current value. Returns false if not accepted
     * (non-monotonic / replay).
     */
    fun tryAdvanceRecvSequence(relationshipId: String, sequence: Long): Boolean {
        var accepted = false
        connectionsByRelationship.compute(relationshipId) { _, existing ->
            if (existing == null) return@compute null
            if (sequence <= existing.recvSequence) {
                accepted = false
                return@compute existing  // unchanged
            }
            accepted = true
            val updated = existing.copy(recvSequence = sequence)
            connectionsByRecvQueue[updated.recvQueueId] = updated
            connectionsBySendQueue[updated.sendQueueId] = updated
            updated
        } ?: return false
        if (accepted) {
            _activeConnectionsFlow.value = HashMap(connectionsByRelationship)
            scope.launch {
                connectionDao?.updateRecvSequence(relationshipId, sequence)
            }
        }
        return accepted
    }

    fun updateRecvSequence(relationshipId: String, sequence: Long) {
        var changed = false
        connectionsByRelationship.compute(relationshipId) { _, existing ->
            if (existing == null) return@compute null
            if (sequence <= existing.recvSequence) return@compute existing
            changed = true
            val updated = existing.copy(recvSequence = sequence)
            connectionsByRecvQueue[updated.recvQueueId] = updated
            connectionsBySendQueue[updated.sendQueueId] = updated
            updated
        }
        if (changed) {
            _activeConnectionsFlow.value = HashMap(connectionsByRelationship)
            scope.launch {
                connectionDao?.updateRecvSequence(relationshipId, sequence)
            }
        }
    }

    fun getConnectionByRelationship(relationshipId: String): Connection? {
        return connectionsByRelationship[relationshipId]
    }

    fun getConnectionByRecvQueue(recvQueueId: String): Connection? {
        return connectionsByRecvQueue[recvQueueId]
    }

    fun getConnectionBySendQueue(sendQueueId: String): Connection? {
        return connectionsBySendQueue[sendQueueId]
    }

    fun getAllActiveConnections(): List<Connection> {
        return connectionsByRelationship.values.toList()
    }

    fun closeConnection(relationshipId: String) {
        val conn = connectionsByRelationship.remove(relationshipId) ?: return
        connectionsByRecvQueue.remove(conn.recvQueueId)
        connectionsBySendQueue.remove(conn.sendQueueId)
        _activeConnectionsFlow.value = HashMap(connectionsByRelationship)
    }
}
