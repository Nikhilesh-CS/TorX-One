package com.torxone.app.network

import org.json.JSONObject

/** Wire format for Nearby mesh and Tor socket transport. */
object MeshProtocol {
    const val TYPE_HELLO = "hello"
    const val TYPE_MSG = "msg"
    const val TYPE_RELAY = "relay"
    const val TYPE_ACK = "ack"
    const val TYPE_READ = "read"
    const val TYPE_REACTION = "reaction"
    const val TYPE_PRESENCE = "presence"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    
    // Profile Sync Protocol Types
    const val TYPE_PROFILE_UPDATE = "profile_update"
    const val TYPE_REQUEST_PROFILE_PHOTO = "req_profile_photo"
    const val TYPE_PROFILE_PHOTO_CHUNK = "profile_photo_chunk"

    // TorX One Music metadata and listen-together sync. No audio bytes are transferred.
    const val TYPE_MUSIC_NOTE = "music_note"
    const val TYPE_MUSIC_SYNC = "music_sync"
    
    // Media Transfer Protocol Types
    const val TYPE_MEDIA_OFFER = "media_offer"
    const val TYPE_MEDIA_CHUNK = "media_chunk"
    const val TYPE_MEDIA_ACK = "media_ack"
    const val TYPE_MEDIA_CANCEL = "media_cancel"
    const val TYPE_MEDIA_RESUME = "media_resume"
    const val TYPE_MEDIA_COMPLETE = "media_complete"
    const val TYPE_MEDIA_ERROR = "media_error"

    // Call signaling. Media itself uses WebRTC; these messages are encrypted signaling only.
    const val TYPE_CALL_OFFER = "call_offer"
    const val TYPE_CALL_ANSWER = "call_answer"
    const val TYPE_ICE_CANDIDATE = "ice_candidate"
    
    const val DEFAULT_TTL = 5
    // Single encrypted frames are for chat/control metadata, not large media.
    // Large files still use the chunked media-transfer pipeline.
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024

    private val hexRegex = Regex("^[0-9a-f]+$")

    data class EncryptedPayload(
        val fromSigningKey: String,
        val toSigningKey: String,
        val ciphertextHex: String,
        val nonceHex: String,
        val signatureHex: String
    )

    data class HelloPayload(
        val contactString: String
    )

    fun encodeHello(contactString: String): String {
        return JSONObject()
            .put("type", TYPE_HELLO)
            .put("contact", contactString)
            .toString()
    }

    fun encodeDirectMessage(payload: EncryptedPayload, messageId: String? = null, senderOnion: String? = null, type: String = TYPE_MSG): String {
        val json = JSONObject()
            .put("type", type)
            .put("from", payload.fromSigningKey)
            .put("to", payload.toSigningKey)
            .put("ciphertext", payload.ciphertextHex)
            .put("nonce", payload.nonceHex)
            .put("signature", payload.signatureHex)
        if (messageId != null) json.put("msgId", messageId)
        if (!senderOnion.isNullOrBlank()) json.put("senderOnion", senderOnion)
        return json.toString()
    }

    fun encodeAck(messageId: String, fromKey: String, toKey: String? = null, senderOnion: String? = null, ttl: Int = DEFAULT_TTL): String {
        val json = JSONObject()
            .put("type", TYPE_ACK)
            .put("msgId", messageId)
            .put("from", fromKey)
            .put("ttl", ttl)
        if (!toKey.isNullOrBlank()) json.put("to", toKey)
        if (!senderOnion.isNullOrBlank()) json.put("senderOnion", senderOnion)
        return json.toString()
    }

    fun encodeRead(messageId: String, fromKey: String, toKey: String? = null, senderOnion: String? = null, ttl: Int = DEFAULT_TTL): String {
        val json = JSONObject()
            .put("type", TYPE_READ)
            .put("msgId", messageId)
            .put("from", fromKey)
            .put("ttl", ttl)
        if (!toKey.isNullOrBlank()) json.put("to", toKey)
        if (!senderOnion.isNullOrBlank()) json.put("senderOnion", senderOnion)
        return json.toString()
    }

    fun encodePing(timestamp: Long, fromOnion: String): String {
        return JSONObject()
            .put("type", TYPE_PING)
            .put("timestamp", timestamp)
            .put("from", fromOnion)
            .toString()
    }

    fun encodePong(timestamp: Long): String {
        return JSONObject()
            .put("type", TYPE_PONG)
            .put("timestamp", timestamp)
            .toString()
    }

    fun encodeRelayMessage(
        payload: EncryptedPayload,
        ttl: Int = DEFAULT_TTL,
        messageId: String? = null,
        senderOnion: String? = null,
        type: String = TYPE_RELAY,
        innerType: String? = null
    ): String {
        val json = JSONObject()
            .put("type", type)
            .put("dest", payload.toSigningKey)
            .put("from", payload.fromSigningKey)
            .put("ttl", ttl)
            .put("ciphertext", payload.ciphertextHex)
            .put("nonce", payload.nonceHex)
            .put("signature", payload.signatureHex)
        if (!messageId.isNullOrBlank()) json.put("msgId", messageId)
        if (!senderOnion.isNullOrBlank()) json.put("senderOnion", senderOnion)
        if (!innerType.isNullOrBlank()) json.put("innerType", innerType)
        return json.toString()
    }

    fun parse(raw: String): JSONObject? {
        if (raw.length > MAX_FRAME_BYTES) return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun parseEncrypted(json: JSONObject): EncryptedPayload? {
        return try {
            val from = json.getString("from").trim().lowercase()
            val to = json.optString("to", json.optString("dest", "")).trim().lowercase()
            val ciphertext = json.getString("ciphertext").trim().lowercase()
            val nonce = json.getString("nonce").trim().lowercase()
            val signature = json.getString("signature").trim().lowercase()

            if (!isHex(from, 64)) return null
            if (!isHex(to, 64)) return null
            if (!isHex(nonce, 48)) return null
            if (!isHex(signature, 128)) return null
            if (!isHex(ciphertext) || ciphertext.length < 32) return null

            EncryptedPayload(
                fromSigningKey = from,
                toSigningKey = to,
                ciphertextHex = ciphertext,
                nonceHex = nonce,
                signatureHex = signature
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isHex(value: String, exactChars: Int? = null): Boolean {
        if (value.isEmpty() || value.length % 2 != 0) return false
        if (exactChars != null && value.length != exactChars) return false
        return hexRegex.matches(value)
    }
}
