package com.astramesh.app.music

import com.astramesh.app.crypto.CryptoManager
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.data.MusicNoteEntity
import com.astramesh.app.identity.IdentityManager
import com.astramesh.app.network.MeshProtocol
import com.astramesh.app.network.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class MusicNoteManager(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val identityManager: IdentityManager,
    private val repository: MusicNoteRepository,
    private val messageRouter: MessageRouter
) {
    val activeNotes: Flow<List<MusicNoteEntity>> = repository.observeActiveNotes()

    fun publishCurrentNote(
        track: DetectedMusicTrack,
        text: String,
        visibility: MusicNoteVisibility,
        durationHours: Int
    ) {
        scope.launch(Dispatchers.IO) {
            val identity = identityManager.loadIdentity() ?: return@launch
            val authorKey = CryptoManager.toHex(identity.signingPublicKey)
            val now = System.currentTimeMillis()
            val note = MusicNoteEntity(
                noteId = UUID.randomUUID().toString(),
                authorId = authorKey,
                authorName = identity.name,
                authorPublicKey = authorKey,
                signature = "",
                text = text.take(60),
                trackId = track.trackId,
                trackName = track.trackName,
                artist = track.artist,
                album = track.album,
                albumArtUri = track.albumArtUri,
                provider = track.provider,
                playbackPositionMs = track.playbackPositionMs,
                createdAt = now,
                expiresAt = now + durationHours.coerceIn(6, 48) * 60L * 60L * 1_000L,
                visibility = visibility.wireValue
            ).let { unsigned ->
                unsigned.copy(signature = signNote(unsigned))
            }
            repository.saveNote(note)
            if (visibility != MusicNoteVisibility.ONLY_ME) {
                broadcastNote(note)
            }
        }
    }

    fun handleMusicNotePacket(raw: String, senderKey: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(raw)
                if (json.optString("astraType") != "music_note") return@runCatching
                val note = MusicNoteEntity(
                    noteId = json.getString("noteId"),
                    authorId = senderKey,
                    authorName = json.optString("authorName", "Astra contact"),
                    authorPublicKey = senderKey,
                    signature = json.optString("signature", ""),
                    text = json.optString("text", "").take(60),
                    trackId = json.optString("trackId", ""),
                    trackName = json.optString("trackName", "Unknown track"),
                    artist = json.optString("artist", "Unknown artist"),
                    album = json.optString("album", ""),
                    albumArtUri = json.optString("albumArtUri").takeIf { it.isNotBlank() },
                    provider = json.optString("provider", ""),
                    playbackPositionMs = json.optLong("playbackPositionMs", 0L),
                    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                    expiresAt = json.optLong("expiresAt", System.currentTimeMillis()),
                    visibility = json.optString("visibility", MusicNoteVisibility.CONTACTS.wireValue),
                    updatedAt = System.currentTimeMillis()
                )
                if (note.expiresAt > System.currentTimeMillis() && note.trackName.isNotBlank()) {
                    repository.saveNote(note)
                }
            }
        }
    }

    private suspend fun broadcastNote(note: MusicNoteEntity) = withContext(Dispatchers.IO) {
        val contacts = db.contactDao().getAllContactsSync()
        val payload = encodeNote(note)
        contacts.forEach { contact ->
            messageRouter.sendRawPayload(contact.signingPublicKey, payload, MeshProtocol.TYPE_MUSIC_NOTE)
        }
    }

    private fun encodeNote(note: MusicNoteEntity): String {
        return JSONObject()
            .put("astraType", "music_note")
            .put("version", 1)
            .put("noteId", note.noteId)
            .put("authorName", note.authorName)
            .put("signature", note.signature)
            .put("text", note.text)
            .put("trackId", note.trackId)
            .put("trackName", note.trackName)
            .put("artist", note.artist)
            .put("album", note.album)
            .put("albumArtUri", note.albumArtUri ?: "")
            .put("provider", note.provider)
            .put("playbackPositionMs", note.playbackPositionMs)
            .put("createdAt", note.createdAt)
            .put("expiresAt", note.expiresAt)
            .put("visibility", note.visibility)
            .toString()
    }

    private fun signNote(note: MusicNoteEntity): String {
        val identity = identityManager.loadIdentity() ?: return hashNote(note)
        val payload = hashNote(note).toByteArray()
        return runCatching {
            CryptoManager.toHex(CryptoManager.sign(payload, identity.signingSecretKey))
        }.getOrElse { hashNote(note) }
    }

    private fun hashNote(note: MusicNoteEntity): String {
        val body = "${note.noteId}|${note.authorPublicKey}|${note.trackId}|${note.trackName}|${note.artist}|${note.createdAt}|${note.expiresAt}"
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
