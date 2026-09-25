package com.torxone.app.connection

import com.torxone.app.data.dao.ConnectionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active pairwise connections.
 * Maps relationshipId -> Connection and queueId -> Connection.
 */
class ConnectionManager {

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
    suspend fun restoreFromDatabase(connectionDao: ConnectionDao) {
        val activeEntities = connectionDao.getAllActive()
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

    fun incrementSendSequence(relationshipId: String): Long {
        val conn = connectionsByRelationship[relationshipId] ?: return 0L
        val nextSeq = conn.sendSequence + 1
        val updated = conn.copy(sendSequence = nextSeq)
        registerConnection(updated)
        return nextSeq
    }

    fun updateRecvSequence(relationshipId: String, sequence: Long) {
        val conn = connectionsByRelationship[relationshipId] ?: return
        if (sequence > conn.recvSequence) {
            val updated = conn.copy(recvSequence = sequence)
            registerConnection(updated)
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

    fun closeConnection(relationshipId: String) {
        val conn = connectionsByRelationship.remove(relationshipId) ?: return
        connectionsByRecvQueue.remove(conn.recvQueueId)
        connectionsBySendQueue.remove(conn.sendQueueId)
        _activeConnectionsFlow.value = HashMap(connectionsByRelationship)
    }
}
