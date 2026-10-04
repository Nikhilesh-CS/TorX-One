package com.torxone.app.transport.tor

/** One lifecycle owns bounded repairs. A brief successful restart does not replenish a crash loop. */
internal class TorRecoveryBudget(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1_000,
    private val healthyResetMs: Long = 60_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val guard = Any()
    private var generation = 0L
    private var running = false
    private var retries = 0
    private var healthySince: Long? = null

    init { require(maxRetries in 1..10 && baseDelayMs > 0 && healthyResetMs > 0) }

    fun start(): Long = synchronized(guard) {
        generation++; running = true; retries = 0; healthySince = null; generation
    }
    fun stop() = synchronized(guard) { generation++; running = false; healthySince = null }
    fun isCurrent(ticket: Long): Boolean = synchronized(guard) { running && generation == ticket }

    fun healthy(ticket: Long) = synchronized(guard) {
        if (running && generation == ticket) {
            val now = clock()
            val since = healthySince
            if (since == null) healthySince = now
            else if (now - since >= healthyResetMs) retries = 0
        }
    }

    fun unhealthy(ticket: Long) = synchronized(guard) {
        if (running && generation == ticket) healthySince = null
    }

    fun nextDelay(ticket: Long): Long? = synchronized(guard) {
        if (!running || generation != ticket || retries >= maxRetries) null
        else (baseDelayMs * (1L shl retries++)).coerceAtMost(30_000)
    }
}
