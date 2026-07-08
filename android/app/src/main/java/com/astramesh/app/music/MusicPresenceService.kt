package com.astramesh.app.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MusicPresenceService(
    private val scope: CoroutineScope,
    private val mediaSessionListener: MediaSessionListener,
    private val onTrackChanged: suspend (DetectedMusicTrack) -> Unit
) {
    private var monitorJob: Job? = null
    private var lastTrackId: String = ""

    fun startMonitoring(intervalMs: Long = 30_000L) {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val track = mediaSessionListener.detectCurrentTrack()
                if (track != null && track.trackId != lastTrackId) {
                    lastTrackId = track.trackId
                    onTrackChanged(track)
                }
                delay(intervalMs)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }
}
