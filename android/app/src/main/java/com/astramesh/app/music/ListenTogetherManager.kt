package com.astramesh.app.music

import com.astramesh.app.network.MeshProtocol
import com.astramesh.app.network.MessageRouter
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
    val lastEvent: ListenTogetherEvent? = null
)

class ListenTogetherManager(
    private val scope: CoroutineScope,
    private val messageRouter: MessageRouter
) {
    private val _state = MutableStateFlow(ListenTogetherState())
    val state: StateFlow<ListenTogetherState> = _state.asStateFlow()
    val playbackSyncController = PlaybackSyncController()

    fun startSession(peerKey: String, noteId: String, positionMs: Long) {
        val sessionId = UUID.randomUUID().toString()
        _state.value = ListenTogetherState(sessionId, noteId, peerKey, active = true)
        sendEvent(peerKey, noteId, sessionId, ListenTogetherEventType.PLAY, positionMs)
    }

    fun sendEvent(
        peerKey: String,
        noteId: String,
        sessionId: String = _state.value.sessionId.ifBlank { UUID.randomUUID().toString() },
        type: ListenTogetherEventType,
        positionMs: Long
    ) {
        scope.launch(Dispatchers.IO) {
            val event = ListenTogetherEvent(sessionId, noteId, peerKey, type, positionMs)
            _state.value = ListenTogetherState(sessionId, noteId, peerKey, type != ListenTogetherEventType.END_SESSION, event)
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
                sentAt = json.optLong("sentAt", System.currentTimeMillis())
            )
            _state.value = ListenTogetherState(
                sessionId = event.sessionId,
                noteId = event.noteId,
                peerKey = senderKey,
                active = event.eventType != ListenTogetherEventType.END_SESSION,
                lastEvent = event
            )
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
            .put("sentAt", event.sentAt)
            .toString()
    }
}
