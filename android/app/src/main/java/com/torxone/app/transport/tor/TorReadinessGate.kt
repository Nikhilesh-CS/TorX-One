package com.torxone.app.transport.tor

/** Lifecycle ownership for samples. Loss of lifecycle forbids both old and newly started probes. */
internal class TorReadinessGate {
    private val guard = Any()
    private var generation = 0L
    private var allowed = false

    fun reset(allowProbes: Boolean, clearState: () -> Unit = {}) = synchronized(guard) {
        generation++
        allowed = allowProbes
        clearState()
    }

    fun ticket(): Long? = synchronized(guard) { generation.takeIf { allowed } }
    fun isCurrent(ticket: Long): Boolean = synchronized(guard) { allowed && generation == ticket }

    /** Only local state publication belongs here; never perform network I/O in publish. */
    fun publish(ticket: Long, action: () -> Unit): Boolean = synchronized(guard) {
        if (!allowed || generation != ticket) false
        else { action(); true }
    }
}
