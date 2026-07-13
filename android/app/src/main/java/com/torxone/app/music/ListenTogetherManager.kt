package com.torxone.app.music

import com.torxone.app.network.MeshProtocol
import com.torxone.app.network.MessageRouter
import com.torxone.app.data.MusicNoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

data class ListenTogetherState(
    val sessionId: String = "",
    val noteId: String = "",
    val peerKey: String = "",
    val active: Boolean = false,
    val incomingInvite: Boolean = false,
    val awaitingResponse: Boolean = false,
    val lastEvent: ListenTogetherEvent? = null
)

class ListenTogetherManager(
    private val scope: CoroutineScope,
    private val messageRouter: MessageRouter
) {
    private val _state = MutableStateFlow(ListenTogetherState())
    val state: StateFlow<ListenTogetherState> = _state.asStateFlow()
    val playbackSyncController = PlaybackSyncController()

    fun inviteSession(peerKey: String, note: MusicNoteEntity) {
        val sessionId = UUID.randomUUID().toString()
        val track = note.toDetectedTrack()
        val event = ListenTogetherEvent(sessionId, note.noteId, peerKey, ListenTogetherEventType.INVITE, note.playbackPositionMs, track)
        _state.value = ListenTogetherState(
            sessionId = sessionId,
            noteId = note.noteId,
            peerKey = peerKey,
            active = false,
            incomingInvite = false,
            awaitingResponse = true,
            lastEvent = event
        )
        scope.launch(Dispatchers.IO) {
            messageRouter.sendRawPayload(peerKey, encodeEvent(event), MeshProtocol.TYPE_MUSIC_SYNC)
        }
    }

    fun inviteSession(peerKey: String, noteId: String, positionMs: Long) {
        val sessionId = UUID.randomUUID().toString()
        val event = ListenTogetherEvent(sessionId, noteId, peerKey, ListenTogetherEventType.INVITE, positionMs)
        _state.value = ListenTogetherState(
            sessionId = sessionId,
            noteId = noteId,
            peerKey = peerKey,
            active = false,
            incomingInvite = false,
            awaitingResponse = true,
            lastEvent = event
        )
        scope.launch(Dispatchers.IO) {
            messageRouter.sendRawPayload(peerKey, encodeEvent(event), MeshProtocol.TYPE_MUSIC_SYNC)
        }
    }

    fun startSession(peerKey: String, noteId: String, positionMs: Long) {
        val sessionId = UUID.randomUUID().toString()
        _state.value = ListenTogetherState(sessionId, noteId, peerKey, active = true)
        sendEvent(peerKey, noteId, sessionId, ListenTogetherEventType.PLAY, positionMs)
    }

    fun acceptIncomingInvite() {
        val current = _state.value
        if (!current.incomingInvite || current.peerKey.isBlank()) return
        sendEvent(current.peerKey, current.noteId, current.sessionId, ListenTogetherEventType.ACCEPT, current.lastEvent?.positionMs ?: 0L, current.lastEvent?.track)
    }

    fun rejectIncomingInvite() {
        val current = _state.value
        if (!current.incomingInvite || current.peerKey.isBlank()) return
        sendEvent(current.peerKey, current.noteId, current.sessionId, ListenTogetherEventType.REJECT, 0L)
        _state.value = ListenTogetherState()
    }

    fun endSession() {
        val current = _state.value
        if (current.peerKey.isBlank()) return
        sendEvent(current.peerKey, current.noteId, current.sessionId, ListenTogetherEventType.END_SESSION, current.lastEvent?.positionMs ?: 0L)
        _state.value = ListenTogetherState()
    }

    fun sendEvent(
        peerKey: String,
        noteId: String,
        sessionId: String = _state.value.sessionId.ifBlank { UUID.randomUUID().toString() },
        type: ListenTogetherEventType,
        positionMs: Long,
        track: DetectedMusicTrack? = _state.value.lastEvent?.track
    ) {
        scope.launch(Dispatchers.IO) {
            val event = ListenTogetherEvent(sessionId, noteId, peerKey, type, positionMs, track)
            _state.value = when (type) {
                ListenTogetherEventType.ACCEPT -> ListenTogetherState(sessionId, noteId, peerKey, active = true, lastEvent = event)
                ListenTogetherEventType.REJECT, ListenTogetherEventType.END_SESSION -> ListenTogetherState()
                else -> ListenTogetherState(sessionId, noteId, peerKey, type in setOf(ListenTogetherEventType.PLAY, ListenTogetherEventType.PAUSE, ListenTogetherEventType.SEEK, ListenTogetherEventType.POSITION_SYNC), lastEvent = event)
            }
            messageRouter.sendRawPayload(peerKey, encodeEvent(event), MeshProtocol.TYPE_MUSIC_SYNC)
        }
    }

    fun handleSyncPacket(raw: String, senderKey: String) {
        runCatching {
            val json = JSONObject(raw)
            if (json.optString("astraType") != "music_sync") return@runCatching
            val event = ListenTogetherEvent(
                sessionId = json.getString("sessionId"),
                noteId = json.getString("noteId"),
                peerKey = senderKey,
                eventType = ListenTogetherEventType.valueOf(json.getString("eventType")),
                positionMs = json.optLong("positionMs", 0L),
                track = json.optJSONObject("track")?.toDetectedTrack(),
                sentAt = json.optLong("sentAt", System.currentTimeMillis())
            )
            _state.value = when (event.eventType) {
                ListenTogetherEventType.INVITE -> ListenTogetherState(
                    sessionId = event.sessionId,
                    noteId = event.noteId,
                    peerKey = senderKey,
                    active = false,
                    incomingInvite = true,
                    awaitingResponse = false,
                    lastEvent = event
                )
                ListenTogetherEventType.ACCEPT -> ListenTogetherState(
                    sessionId = event.sessionId,
                    noteId = event.noteId,
                    peerKey = senderKey,
                    active = true,
                    incomingInvite = false,
                    awaitingResponse = false,
                    lastEvent = event
                )
                ListenTogetherEventType.REJECT,
                ListenTogetherEventType.END_SESSION -> ListenTogetherState()
                else -> ListenTogetherState(
                    sessionId = event.sessionId,
                    noteId = event.noteId,
                    peerKey = senderKey,
                    active = true,
                    incomingInvite = false,
                    awaitingResponse = false,
                    lastEvent = event
                )
            }
        }
    }

    private fun encodeEvent(event: ListenTogetherEvent): String {
        return JSONObject()
            .put("astraType", "music_sync")
            .put("version", 1)
            .put("sessionId", event.sessionId)
            .put("noteId", event.noteId)
            .put("eventType", event.eventType.name)
            .put("positionMs", event.positionMs)
            .put("track", event.track?.toJson())
            .put("sentAt", event.sentAt)
            .toString()
    }

    private fun MusicNoteEntity.toDetectedTrack(): DetectedMusicTrack {
        return DetectedMusicTrack(
            trackId = trackId,
            trackName = trackName,
            artist = artist,
            album = album,
            albumArtUri = albumArtUri,
            provider = provider,
            playbackPositionMs = playbackPositionMs
        )
    }

    private fun DetectedMusicTrack.toJson(): JSONObject {
        return JSONObject()
            .put("trackId", trackId)
            .put("trackName", trackName)
            .put("artist", artist)
            .put("album", album)
            .put("albumArtUri", albumArtUri ?: "")
            .put("provider", provider)
            .put("playbackPositionMs", playbackPositionMs)
    }

    private fun JSONObject.toDetectedTrack(): DetectedMusicTrack? {
        val name = optString("trackName", "")
        if (name.isBlank()) return null
        return DetectedMusicTrack(
            trackId = optString("trackId", ""),
            trackName = name,
            artist = optString("artist", "Unknown artist"),
            album = optString("album", ""),
            albumArtUri = optString("albumArtUri", "").takeIf { it.isNotBlank() },
            provider = optString("provider", ""),
            playbackPositionMs = optLong("playbackPositionMs", 0L)
        )
    }
}
