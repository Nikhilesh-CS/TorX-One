package com.astramesh.app.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astramesh.app.data.MusicNoteEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MusicNoteViewModel(
    private val repository: MusicNoteRepository,
    private val musicNoteManager: MusicNoteManager?
) : ViewModel() {
    val activeNotes: StateFlow<List<MusicNoteEntity>> = repository.observeActiveNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun publish(track: DetectedMusicTrack, text: String, visibility: MusicNoteVisibility, durationHours: Int) {
        musicNoteManager?.publishCurrentNote(track, text, visibility, durationHours)
    }

    fun pruneExpired() {
        viewModelScope.launch {
            repository.pruneExpired()
        }
    }
}
