package com.torxone.app.transport.tor

import java.net.Socket

/** Atomically transfers accepted sockets into one listener generation's ownership. */
internal class TorListenerAdmission(
    private val capacity: Int = 32,
    private val unauthenticatedCapacity: Int = minOf(8, capacity),
    private val perRelationshipCapacity: Int = minOf(2, capacity)
) {
    private val guard = Any()
    private val clients = LinkedHashMap<Socket, String?>()
    private val relationships = HashMap<String, Int>()
    private var unauthenticated = 0
    private var generation = 0L
    private var open = false

    init {
        require(capacity > 0 && unauthenticatedCapacity in 1..capacity && perRelationshipCapacity in 1..capacity)
    }

    fun start(): Long = synchronized(guard) {
        check(clients.isEmpty()) { "Previous Tor listener still owns clients" }
        generation++
        open = true
        generation
    }

    fun register(ticket: Long, socket: Socket): Boolean = synchronized(guard) {
        if (!open || ticket != generation || clients.size >= capacity || unauthenticated >= unauthenticatedCapacity || clients.containsKey(socket)) false
        else { clients[socket] = null; unauthenticated++; true }
    }

    /** Only a verified envelope/AEAD callback may promote a socket; headers never call this. */
    fun authenticate(ticket: Long, socket: Socket, relationship: String): Boolean = synchronized(guard) {
        if (!open || ticket != generation || !clients.containsKey(socket) || relationship.isBlank() || relationship.length > 256) return@synchronized false
        val current = clients[socket]
        if (current != null) return@synchronized current == relationship
        val count = relationships[relationship] ?: 0
        if (count >= perRelationshipCapacity) return@synchronized false
        clients[socket] = relationship
        unauthenticated--
        relationships[relationship] = count + 1
        true
    }

    fun release(socket: Socket) = synchronized(guard) {
        if (clients.containsKey(socket)) {
            val relationship = clients.remove(socket)
            if (relationship == null) unauthenticated--
            else {
                val remaining = relationships.getValue(relationship) - 1
                if (remaining == 0) relationships.remove(relationship) else relationships[relationship] = remaining
            }
        }
    }
    fun snapshot(): List<Socket> = synchronized(guard) { clients.keys.toList() }
    fun isCurrent(ticket: Long): Boolean = synchronized(guard) { open && generation == ticket }
    fun isOpen(): Boolean = synchronized(guard) { open }

    /** Admission closes before any blocking socket close. Racing registrations must be rejected. */
    fun stop(): List<Socket> = synchronized(guard) {
        open = false
        generation++
        clients.keys.toList().also { clients.clear(); relationships.clear(); unauthenticated = 0 }
    }
}
