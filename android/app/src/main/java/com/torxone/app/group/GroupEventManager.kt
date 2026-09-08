package com.torxone.app.group

import com.torxone.app.crypto.CryptoManager
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.GroupEventEntity
import com.torxone.app.data.ProcessedGroupEventEntity
import com.torxone.app.identity.IdentityManager
import org.json.JSONObject
import java.util.UUID

/** Immutable local audit log plus persistent control-event replay guard. */
class GroupEventManager(
    private val db: AppDatabase,
    private val identityManager: IdentityManager
) {
    data class SignedEvent(val eventId: String, val groupVersion: Long, val json: JSONObject)

    suspend fun createLocalEvent(groupId: String, type: String, targetKey: String? = null, keyVersion: Int = 0, payload: JSONObject = JSONObject()): SignedEvent? {
        val identity = identityManager.loadIdentity() ?: return null
        val actor = CryptoManager.toHex(identity.signingPublicKey)
        val id = UUID.randomUUID().toString()
        val version = db.groupEventDao().getLatestVersion(groupId) + 1
        val body = canonical(id, groupId, type, actor, targetKey, version, keyVersion, payload)
        val signature = CryptoManager.toHex(CryptoManager.sign(body.toByteArray(Charsets.UTF_8), identity.signingSecretKey))
        val event = JSONObject().apply {
            put("eventId", id); put("groupId", groupId); put("eventType", type)
            put("actorKey", actor); if (targetKey != null) put("targetKey", targetKey)
            put("groupVersion", version); put("keyVersion", keyVersion)
            put("createdAt", System.currentTimeMillis()); put("payload", payload); put("signature", signature)
        }
        db.groupEventDao().insertEvent(GroupEventEntity(id, groupId, type, actor, targetKey, version, keyVersion, System.currentTimeMillis(), payload.toString(), signature))
        return SignedEvent(id, version, event)
    }

    suspend fun recordLocal(groupId: String, type: String, targetKey: String? = null, keyVersion: Int = 0, payload: JSONObject = JSONObject()): String {
        return createLocalEvent(groupId, type, targetKey, keyVersion, payload)?.eventId.orEmpty()
    }

    fun verifyIncoming(event: JSONObject, expectedGroupId: String, senderKey: String): Boolean {
        return try {
            val groupId = event.getString("groupId")
            val actor = event.getString("actorKey")
            val type = event.getString("eventType")
            val id = event.getString("eventId")
            if (groupId != expectedGroupId || actor != senderKey || id.isBlank()) return false
            val body = canonical(id, groupId, type, actor, event.optString("targetKey").takeIf { it.isNotBlank() }, event.getLong("groupVersion"), event.optInt("keyVersion", 0), event.optJSONObject("payload") ?: JSONObject())
            val pub = CryptoManager.fromHexOrNull(actor, 32) ?: return false
            val sig = CryptoManager.fromHexOrNull(event.getString("signature"), 64) ?: return false
            CryptoManager.verify(body.toByteArray(Charsets.UTF_8), sig, pub)
        } catch (_: Exception) { false }
    }

    private fun canonical(id: String, groupId: String, type: String, actor: String, target: String?, version: Long, keyVersion: Int, payload: JSONObject): String =
        JSONObject().apply { put("eventId", id); put("groupId", groupId); put("eventType", type); put("actorKey", actor); if (target != null) put("targetKey", target); put("groupVersion", version); put("keyVersion", keyVersion); put("payload", payload) }.toString()

    fun markIncoming(groupId: String, eventId: String, senderKey: String, type: String): Boolean {
        if (eventId.isBlank() || db.processedGroupEventDao().wasProcessed(groupId, eventId) > 0) return false
        return db.processedGroupEventDao().markProcessed(ProcessedGroupEventEntity(groupId, eventId, senderKey, type, System.currentTimeMillis())) > 0
    }
}
