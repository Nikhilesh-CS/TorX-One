package com.torxone.app.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.MusicNoteEntity
import com.torxone.app.identity.IdentityManager
import com.torxone.app.network.MeshProtocol
import com.torxone.app.network.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class MusicNoteManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val identityManager: IdentityManager,
    private val repository: MusicNoteRepository,
    private val messageRouter: MessageRouter
) {
    private val maxAlbumArtBytes = 192 * 1024

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
            val albumArtUri = persistLocalAlbumArt(track.albumArtUri, track.trackId) ?: track.albumArtUri
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
                albumArtUri = albumArtUri,
                provider = track.provider,
                playbackPositionMs = track.playbackPositionMs,
                createdAt = now,
                expiresAt = now + durationHours.coerceIn(6, 48) * 60L * 60L * 1_000L,
                visibility = visibility.wireValue
            ).let { unsigned ->
                unsigned.copy(signature = signNote(unsigned))
            }
            repository.removeNotesByAuthor(authorKey)
            repository.saveNote(note)
            if (visibility != MusicNoteVisibility.ONLY_ME) {
                broadcastNote(note)
            }
        }
    }

    fun deleteMyNote() {
        scope.launch(Dispatchers.IO) {
            val identity = identityManager.loadIdentity() ?: return@launch
            val authorKey = CryptoManager.toHex(identity.signingPublicKey)
            repository.removeNotesByAuthor(authorKey)
            broadcastDelete(authorKey)
        }
    }

    fun handleMusicNotePacket(raw: String, senderKey: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(raw)
                if (json.optString("astraType") != "music_note") return@runCatching
                val action = json.optString("action", "publish")
                if (action == "delete") {
                    repository.removeNotesByAuthor(senderKey)
                    return@runCatching
                }
                val albumArtData = json.optString("albumArtData", "")
                val albumArtUri = if (albumArtData.isNotBlank()) {
                    saveRemoteAlbumArt(senderKey, json.optString("trackId", ""), albumArtData)
                } else {
                    json.optString("albumArtUri").takeIf { it.isNotBlank() }
                }
                val note = MusicNoteEntity(
                    noteId = json.getString("noteId"),
                    authorId = senderKey,
                    authorName = json.optString("authorName", "TorX One contact"),
                    authorPublicKey = senderKey,
                    signature = json.optString("signature", ""),
                    text = json.optString("text", "").take(60),
                    trackId = json.optString("trackId", ""),
                    trackName = json.optString("trackName", "Unknown track"),
                    artist = json.optString("artist", "Unknown artist"),
                    album = json.optString("album", ""),
                    albumArtUri = albumArtUri,
                    provider = json.optString("provider", ""),
                    playbackPositionMs = json.optLong("playbackPositionMs", 0L),
                    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                    expiresAt = json.optLong("expiresAt", System.currentTimeMillis()),
                    visibility = json.optString("visibility", MusicNoteVisibility.CONTACTS.wireValue),
                    updatedAt = System.currentTimeMillis()
                )
                if (note.expiresAt > System.currentTimeMillis() && note.trackName.isNotBlank()) {
                    repository.removeNotesByAuthor(senderKey)
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

    private suspend fun broadcastDelete(authorKey: String) = withContext(Dispatchers.IO) {
        val contacts = db.contactDao().getAllContactsSync()
        val payload = JSONObject()
            .put("astraType", "music_note")
            .put("version", 1)
            .put("action", "delete")
            .put("authorPublicKey", authorKey)
            .put("deletedAt", System.currentTimeMillis())
            .toString()
        contacts.forEach { contact ->
            messageRouter.sendRawPayload(contact.signingPublicKey, payload, MeshProtocol.TYPE_MUSIC_NOTE)
        }
    }

    private fun encodeNote(note: MusicNoteEntity): String {
        return JSONObject()
            .put("astraType", "music_note")
            .put("version", 1)
            .put("action", "publish")
            .put("noteId", note.noteId)
            .put("authorName", note.authorName)
            .put("signature", note.signature)
            .put("text", note.text)
            .put("trackId", note.trackId)
            .put("trackName", note.trackName)
            .put("artist", note.artist)
            .put("album", note.album)
            .put("albumArtUri", note.albumArtUri ?: "")
            .put("albumArtData", encodeAlbumArt(note.albumArtUri))
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

    private fun persistLocalAlbumArt(albumArtUri: String?, trackId: String): String? {
        val bytes = readOptimizedAlbumArt(albumArtUri) ?: return null
        val file = albumArtFile("local", trackId)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    private fun encodeAlbumArt(albumArtUri: String?): String {
        val file = albumArtUri?.let { File(it) }
        val bytes = if (file != null && file.exists() && file.length() <= maxAlbumArtBytes) {
            file.readBytes()
        } else {
            readOptimizedAlbumArt(albumArtUri)
        } ?: return ""
        if (bytes.size > maxAlbumArtBytes) return ""
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun saveRemoteAlbumArt(senderKey: String, trackId: String, dataB64: String): String? {
        return runCatching {
            val bytes = java.util.Base64.getDecoder().decode(dataB64)
            if (bytes.isEmpty() || bytes.size > maxAlbumArtBytes) return@runCatching null
            val file = albumArtFile(senderKey, trackId)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    private fun readOptimizedAlbumArt(albumArtUri: String?): ByteArray? {
        if (albumArtUri.isNullOrBlank()) return null
        return runCatching {
            val bitmap = openAlbumArtStream(albumArtUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@runCatching null
            val scaled = scaleToMax(bitmap, 512)
            val output = ByteArrayOutputStream()
            val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            scaled.compress(format, 88, output)
            output.toByteArray().takeIf { it.size <= maxAlbumArtBytes }
        }.getOrNull()
    }

    private fun openAlbumArtStream(albumArtUri: String): java.io.InputStream? {
        val file = File(albumArtUri)
        if (file.exists()) return file.inputStream()
        val uri = Uri.parse(albumArtUri)
        return context.contentResolver.openInputStream(uri)
    }

    private fun scaleToMax(bitmap: Bitmap, maxSide: Int): Bitmap {
        val largestSide = maxOf(bitmap.width, bitmap.height)
        if (largestSide <= maxSide) return bitmap
        val scale = maxSide.toFloat() / largestSide.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun albumArtFile(ownerKey: String, trackId: String): File {
        val source = "$ownerKey:$trackId:${System.currentTimeMillis() / 86_400_000L}"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "music_art").apply { mkdirs() }, "$hash.webp")
    }
}
