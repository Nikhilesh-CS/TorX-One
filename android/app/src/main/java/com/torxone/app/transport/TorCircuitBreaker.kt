package com.torxone.app.transport

/** Per relationship, not per conversation/queue. Process-local health is safe to reset on restart. */
class TorCircuitBreaker(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val failureThreshold: Int = 3,
    private val openMs: Long = 30_000,
    private val maxOpenMs: Long = 300_000,
    private val maxEntries: Int = 4096
) {
    class Permit internal constructor(val key: String, internal val generation: Long)
    private data class Health(var failures: Int = 0, var openUntil: Long = 0,
                              var probe: Boolean = false, val generation: Long)
    private val health = LinkedHashMap<String, Health>(16, 0.75f, true)
    private var generation = 0L

    init { require(failureThreshold > 0 && openMs > 0 && maxOpenMs >= openMs && maxEntries > 0) }

    @Synchronized fun acquire(key: String): Permit? {
        val state = health.getOrPut(key) { Health(generation = ++generation) }
        if (state.probe || state.openUntil > clock()) return null
        if (state.openUntil != 0L) {
            state.probe = true
            DeliveryDiagnostics.event("circuit_half_open", key, transport = TransportType.TOR)
        }
        prune()
        return Permit(key, state.generation)
    }

    /** Socket acceptance permits future probes but isn't receiver proof; retain failure count. */
    @Synchronized fun accepted(permit: Permit) {
        val state = health[permit.key]?.takeIf { it.generation == permit.generation } ?: return
        if (state.probe) {
            state.probe = false
            state.openUntil = clock() + openMs
        }
    }

    @Synchronized fun failed(permit: Permit) {
        val state = health[permit.key]?.takeIf { it.generation == permit.generation } ?: return
        recordFailure(permit.key, state)
    }

    @Synchronized fun missingAck(key: String) {
        val state = health.getOrPut(key) { Health(generation = ++generation) }
        recordFailure(key, state)
        prune()
    }

    private fun recordFailure(key: String, state: Health) {
        state.failures++
        state.probe = false
        if (state.failures >= failureThreshold) {
            val shift = (state.failures - failureThreshold).coerceIn(0, 4)
            state.openUntil = clock() + minOf(openMs * (1L shl shift), maxOpenMs)
            DeliveryDiagnostics.event("circuit_open", key, transport = TransportType.TOR, state = "UNREACHABLE")
        }
    }

    @Synchronized fun abandon(permit: Permit) {
        health[permit.key]?.takeIf { it.generation == permit.generation }?.probe = false
    }

    @Synchronized fun reset(key: String) {
        if (health.remove(key) != null) DeliveryDiagnostics.event("circuit_closed", key, transport = TransportType.TOR)
    }

    @Synchronized fun resetAll() { health.clear(); generation++ }

    private fun prune() {
        val entries = health.entries.iterator()
        while (health.size > maxEntries && entries.hasNext()) {
            if (!entries.next().value.probe) entries.remove()
        }
    }
}
