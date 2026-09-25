package com.torxone.app.connection

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
