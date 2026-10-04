package com.torxone.app.media

import kotlinx.coroutines.sync.Mutex

/** RTC is optional: a busy shared lane must never queue another peer's Tor fallback. */
internal class FileRtcSendGate {
    private val mutex = Mutex()

    suspend fun sendWhenIdle(send: suspend () -> Boolean): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            send()
        } finally {
            mutex.unlock()
        }
    }
}
