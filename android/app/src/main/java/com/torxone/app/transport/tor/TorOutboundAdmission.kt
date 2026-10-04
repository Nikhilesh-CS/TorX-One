package com.torxone.app.transport.tor

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.sync.Semaphore

/** Bounded callers and FIFO suspended admission; no network operation owns the bookkeeping guard. */
internal class TorOutboundAdmission(activeLimit: Int = 8, private val callerLimit: Int = 128,
                                    private val perKeyCallerLimit: Int = minOf(32, callerLimit)) {
    private val permits = Semaphore(activeLimit)
    private val guard = Any()
    private var callers = 0
    private val callersByKey = HashMap<String, Int>()

    init { require(activeLimit > 0 && callerLimit >= activeLimit && perKeyCallerLimit in 1..callerLimit) }

    fun reserve(key: String = ""): Reservation = synchronized(guard) {
        val peerCallers = callersByKey[key] ?: 0
        if (callers >= callerLimit || peerCallers >= perKeyCallerLimit) throw IOException("Tor outbound admission full")
        callers++
        callersByKey[key] = peerCallers + 1
        Reservation(key)
    }

    inner class Reservation internal constructor(private val key: String) : Closeable {
        private var active = false
        private var closed = false

        suspend fun activate() {
            check(!active && !closed)
            permits.acquire() // kotlinx.coroutines Semaphore guarantees FIFO admission of suspended callers.
            active = true
        }

        override fun close() {
            if (closed) return
            closed = true
            if (active) permits.release()
            synchronized(guard) {
                callers--
                val remaining = callersByKey.getValue(key) - 1
                if (remaining == 0) callersByKey.remove(key) else callersByKey[key] = remaining
            }
        }
    }

    internal fun reservedCount(): Int = synchronized(guard) { callers }
}
