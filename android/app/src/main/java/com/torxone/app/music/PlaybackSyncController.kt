package com.torxone.app.music

class PlaybackSyncController {
    fun shouldCorrectDrift(localPositionMs: Long, remotePositionMs: Long, toleranceMs: Long = 750L): Boolean {
        return kotlin.math.abs(localPositionMs - remotePositionMs) > toleranceMs
    }

    fun correctedPosition(remotePositionMs: Long, eventSentAt: Long, now: Long = System.currentTimeMillis()): Long {
        return remotePositionMs + (now - eventSentAt).coerceAtLeast(0L)
    }
}
