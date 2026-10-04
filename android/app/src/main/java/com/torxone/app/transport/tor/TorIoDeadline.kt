package com.torxone.app.transport.tor

import java.io.Closeable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Absolute I/O deadlines: SO_TIMEOUT alone resets on every successful read. */
internal object TorIoDeadline {
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "Tor-input-deadlines").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun watch(connection: Closeable, timeoutMs: Long): Closeable {
        require(timeoutMs > 0)
        val deadline = timer.schedule({ runCatching { connection.close() } }, timeoutMs, TimeUnit.MILLISECONDS)
        return Closeable { deadline.cancel(false) }
    }
}
