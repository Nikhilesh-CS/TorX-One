package com.torxone.app.media

import com.torxone.app.calls.CallManager
import com.torxone.app.calls.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns the optional file lane's native setup/ICE wait, including missing SDP callbacks. */
internal class FileRtcConnectionDeadline(
    private val manager: CallManager,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 40_000L
) {
    private var job: Job? = null

    init { require(timeoutMs > 0) }

    fun start(callId: String) {
        cancel()
        job = scope.launch {
            delay(timeoutMs)
            val session = manager.activeCall.value
            if (session?.callId == callId && session.state in setOf(
                    CallState.OUTGOING_PREPARING, CallState.OUTGOING_SENDING,
                    CallState.OUTGOING_CALLING, CallState.OUTGOING_RINGING,
                    CallState.INCOMING_RINGING, CallState.CONNECTING)) {
                // Ending the call cancels its watchers; don't cancel our own teardown.
                job = null
                manager.onCallFailed(callId, "File connection is unavailable; using Tor")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
