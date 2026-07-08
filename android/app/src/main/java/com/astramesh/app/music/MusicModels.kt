package com.astramesh.app.music

data class DetectedMusicTrack(
    val trackId: String,
    val trackName: String,
    val artist: String,
    val album: String,
    val albumArtUri: String?,
    val provider: String,
    val playbackPositionMs: Long
)

enum class MusicNoteVisibility(val wireValue: String) {
    ONLY_ME("only_me"),
    FAVORITES("favorites"),
    CONTACTS("contacts"),
    EVERYONE("everyone")
}

enum class ListenTogetherEventType {
    PLAY,
    PAUSE,
    SEEK,
    POSITION_SYNC,
    END_SESSION
}

data class ListenTogetherEvent(
    val sessionId: String,
    val noteId: String,
    val peerKey: String,
    val eventType: ListenTogetherEventType,
    val positionMs: Long,
    val sentAt: Long = System.currentTimeMillis()
)
