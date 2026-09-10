package com.torxone.app.service

import android.util.Log
import com.torxone.app.agent.EnvelopeType
import com.torxone.app.agent.TorXAgent
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.crypto.Identity
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactEntity
import com.torxone.app.data.MessageEntity
import com.torxone.app.group.GroupCryptoManager
import com.torxone.app.group.GroupPermission
import com.torxone.app.network.MeshProtocol
import com.torxone.app.network.SendResult
import com.torxone.app.network.Transport
import com.torxone.app.security.session.SessionCryptoService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * TorX One 2.0 — Chat Message Service
 *
 * Dedicated service that encapsulates:
 * 1. Chat message creation, persistent DB insertion, and state transitions
 * 2. Cryptographic session encryption (Double Ratchet with legacy fallback)
 * 3. Group chat encryption and pairwise member queue distribution
 * 4. Reaction & poll vote operations
 *
 * In TorX One 2.0, ALL outgoing messages strictly queue through [TorXAgent],
 * eliminating duplicate delivery loops, socket bypasses, and transport contradictions.
 */
class ChatMessageService(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val torXAgent: TorXAgent,
    private val sessionCryptoService: SessionCryptoService?,
    private val identityProvider: () -> Identity?,
    private val onionAddressProvider: () -> String = { "" }
) {
    companion object {
        private const val TAG = "ChatMessageService"
    }

    data class ChatMessagePayload(
        val text: String,
        val sentAt: Long? = null,
        val replyToId: String? = null,
        val replyToText: String? = null,
        val replyToSender: String? = null,
        val replyToType: String? = null
    )

    suspend fun sendMessage(
        contactKey: String,
        text: String,
        replyToId: String? = null,
        replyToText: String? = null,
        replyToSender: String? = null,
        replyToType: String? = null
    ): SendResult = withContext(Dispatchers.IO) {
        val identity = identityProvider()
            ?: return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        val contact = db.contactDao().getContact(contactKey)
            ?: return@withContext SendResult(false, Transport.FAILED, "Contact not found")

        if (CryptoManager.fromHexOrNull(contact.encryptionPublicKey, 32) == null) {
            return@withContext SendResult(false, Transport.FAILED, "Missing encryption key")
        }

        val sentAt = System.currentTimeMillis()
        val wireText = encodeChatMessagePayload(
            text = text,
            sentAt = sentAt,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSender = replyToSender,
            replyToType = replyToType
        )

        val messageId = UUID.randomUUID().toString()
        db.messageDao().insertMessage(
            MessageEntity(
                messageId = messageId,
                contactKey = contactKey,
                text = text,
                timestamp = sentAt,
                direction = "sent",
                status = "sending",
                replyToId = replyToId,
                replyToText = replyToText,
                replyToSender = replyToSender,
                replyToType = replyToType
            )
        )
        Log.i(TAG, "[MSG] id=$messageId state=SENDING")

        val sessionPayload = try {
            sessionCryptoService?.encrypt(contact, wireText, MeshProtocol.TYPE_MSG, messageId)
        } catch (e: Exception) {
            Log.w(TAG, "[SEND] Session encryption error, falling back to legacy: ${e.message}")
            null
        }

        val legacyPayload = if (sessionPayload == null) {
            buildEncryptedPayload(identity, contact, wireText)
                ?: run {
                    db.messageDao().updateMessageStatus(messageId, "failed")
                    return@withContext SendResult(false, Transport.FAILED, "Encryption failed")
                }
        } else null

        val myOnion = onionAddressProvider()
        val wireJson = sessionPayload?.wireJsonString
            ?: MeshProtocol.encodeDirectMessage(legacyPayload!!, messageId, myOnion, MeshProtocol.TYPE_MSG)

        torXAgent.queueForDelivery(
            recipientKey = contactKey,
            messageId = messageId,
            messageType = EnvelopeType.MSG,
            encryptedPayload = wireJson
        )

        SendResult(true, Transport.PENDING)
    }

    suspend fun sendGroupMessage(groupId: String, text: String, replyToId: String? = null): SendResult = withContext(Dispatchers.IO) {
        val identity = identityProvider()
            ?: return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        val myKey = CryptoManager.toHex(identity.signingPublicKey)
        val group = db.groupDao().getGroup(groupId)
            ?: return@withContext SendResult(false, Transport.FAILED, "Group not found")
        val localMembership = db.groupDao().getGroupMember(groupId, myKey)
        if (!GroupPermission.canSendMessages(group, localMembership)) {
            return@withContext SendResult(false, Transport.FAILED, "You do not have permission to send in this group")
        }

        val messageId = UUID.randomUUID().toString()
        val entity = MessageEntity(
            messageId = messageId,
            contactKey = groupId,
            conversationType = "group",
            senderKey = myKey,
            direction = "sent",
            status = "pending",
            text = text.trim(),
            timestamp = System.currentTimeMillis(),
            replyToId = replyToId
        )
        db.messageDao().insertMessage(entity)

        var groupKey = db.groupKeyDao().getLatestKey(groupId)
        if (groupKey == null && (group.creatorKey == myKey || group.myRole == "owner")) {
            val newKey = com.torxone.app.data.GroupKeyEntity(
                groupId = groupId,
                keyVersion = maxOf(1, group.currentKeyVersion),
                aesKeyBase64 = GroupCryptoManager.newKeyBase64(),
                distributedAt = System.currentTimeMillis()
            )
            db.groupKeyDao().insertKey(newKey)
            groupKey = newKey
        }
        if (groupKey == null) {
            com.torxone.app.service.TorXOneService.getInstance()?.groupManager?.requestMissingKey(groupId, group.creatorKey, 1)
            return@withContext SendResult(false, Transport.PENDING, "Waiting for group key distribution")
        }

        val replyTarget = replyToId?.let { db.messageDao().getMessageById(it) }
        val innerPayload = JSONObject().apply {
            put("astraType", "chat_message")
            put("version", 1)
            put("type", "TEXT")
            put("messageId", messageId)
            put("senderKey", myKey)
            put("timestamp", System.currentTimeMillis())
            put("text", text.trim())
            replyTarget?.let { target ->
                put("reply", JSONObject().apply {
                    put("originalMessageId", target.messageId)
                    put("originalSender", target.senderKey)
                    put("originalType", target.messageType)
                    put("originalPreview", target.text.take(500))
                })
            }
            put("mentions", JSONArray().apply {
                Regex("@([A-Za-z0-9_]{1,64})").findAll(text).forEach { match ->
                    put(JSONObject().apply {
                        put("label", match.groupValues[1])
                        put("start", match.range.first)
                        put("length", match.value.length)
                    })
                }
            })
        }

        val encrypted = GroupCryptoManager.encrypt(groupKey, innerPayload.toString())
        val finalPayload = JSONObject().apply {
            put("type", MeshProtocol.TYPE_GROUP_MESSAGE)
            put("schemaVersion", 2)
            put("groupId", groupId)
            put("keyVersion", groupKey.keyVersion)
            put("ciphertext", encrypted.ciphertextBase64)
            put("iv", encrypted.ivBase64)
        }

        val members = db.groupDao().getGroupMembersSync(groupId)
        var queuedCount = 0
        members.filter { it.role != "invited" && it.memberKey != myKey }.forEach { member ->
            val wire = buildEncryptedWireFrame(member.memberKey, finalPayload.toString(), MeshProtocol.TYPE_GROUP_MESSAGE)
            if (wire != null) {
                torXAgent.queueForDelivery(
                    recipientKey = member.memberKey,
                    messageId = "${messageId}_${member.memberKey.take(8)}",
                    messageType = EnvelopeType.GROUP_MESSAGE,
                    encryptedPayload = wire
                )
                queuedCount++
            }
        }

        val finalStatus = if (queuedCount > 0 || members.count { it.role != "invited" } <= 1) "sent" else "pending"
        db.messageDao().updateMessageStatus(messageId, finalStatus)
        SendResult(true, Transport.PENDING)
    }

    suspend fun sendRawPayload(contactKey: String, rawText: String, messageType: String = MeshProtocol.TYPE_MSG): SendResult = withContext(Dispatchers.IO) {
        if (identityProvider() == null) return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        val contact = db.contactDao().getContact(contactKey)
            ?: return@withContext SendResult(false, Transport.FAILED, "Contact not found")
        if (CryptoManager.fromHexOrNull(contact.encryptionPublicKey, 32) == null) {
            return@withContext SendResult(false, Transport.FAILED, "Missing encryption key")
        }

        val messageId = UUID.randomUUID().toString()
        val wireFrame = buildEncryptedWireFrame(contactKey, rawText, messageType)
            ?: return@withContext SendResult(false, Transport.FAILED, "Encryption failed")

        val envelopeType = EnvelopeType.fromWireType(messageType)
        torXAgent.queueForDelivery(
            recipientKey = contactKey,
            messageId = messageId,
            messageType = envelopeType,
            encryptedPayload = wireFrame
        )

        SendResult(true, Transport.PENDING)
    }

    suspend fun buildEncryptedWireFrame(contactKey: String, rawText: String, messageType: String = MeshProtocol.TYPE_MSG): String? = withContext(Dispatchers.IO) {
        val identity = identityProvider() ?: return@withContext null
        val contact = db.contactDao().getContact(contactKey) ?: return@withContext null
        if (CryptoManager.fromHexOrNull(contact.encryptionPublicKey, 32) == null) return@withContext null

        val messageId = UUID.randomUUID().toString()

        // 1. Try Double Ratchet session encryption
        val sessionPayload = try {
            sessionCryptoService?.encrypt(contact, rawText, messageType, messageId)
        } catch (e: Exception) {
            null
        }

        if (sessionPayload != null) {
            return@withContext sessionPayload.wireJsonString
        }

        // 2. Fallback to legacy asymmetric curve25519 payload
        val payload = buildEncryptedPayload(identity, contact, rawText) ?: return@withContext null
        val myOnion = onionAddressProvider()
        MeshProtocol.encodeDirectMessage(payload, messageId, myOnion, messageType)
    }

    suspend fun toggleReaction(contactKey: String, targetMessageId: String, emoji: String): SendResult = withContext(Dispatchers.IO) {
        val identity = identityProvider() ?: return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        val actorKey = CryptoManager.toHex(identity.signingPublicKey)
        if (emoji.isBlank()) return@withContext SendResult(false, Transport.FAILED, "Reaction is blank")
        val target = db.messageDao().getMessageById(targetMessageId) ?: return@withContext SendResult(false, Transport.FAILED, "Message not found")

        val current = parseReactionMap(target.reactionsJson)
        val action = if (current[actorKey]?.contains(emoji) == true) "remove" else "set"
        applyReactionToMessage(targetMessageId, actorKey, emoji, action)

        val payload = JSONObject().apply {
            put("astraType", "reaction")
            put("version", 1)
            put("reactionId", UUID.randomUUID().toString())
            put("targetMessageId", targetMessageId)
            put("emoji", emoji)
            put("action", action)
            put("actorKey", actorKey)
            put("createdAt", System.currentTimeMillis())
        }.toString()

        sendRawPayload(contactKey, payload, MeshProtocol.TYPE_REACTION)
    }

    suspend fun sendPollVote(contactKey: String, targetMessageId: String, optionIndex: Int): SendResult = withContext(Dispatchers.IO) {
        val identity = identityProvider() ?: return@withContext SendResult(false, Transport.FAILED, "Not logged in")
        val actorKey = CryptoManager.toHex(identity.signingPublicKey)
        if (db.messageDao().getMessageById(targetMessageId) == null) return@withContext SendResult(false, Transport.FAILED, "Message not found")

        applyPollVote(targetMessageId, actorKey, optionIndex)
        val payload = JSONObject().apply {
            put("astraType", "poll_vote")
            put("targetMessageId", targetMessageId)
            put("optionIndex", optionIndex)
            put("voterKey", actorKey)
        }.toString()

        sendRawPayload(contactKey, payload, MeshProtocol.TYPE_POLL_VOTE)
    }

    fun applyReactionToMessage(messageId: String, actorKey: String, emoji: String, action: String) {
        if (messageId.isBlank() || actorKey.isBlank() || emoji.isBlank()) return
        val message = db.messageDao().getMessageById(messageId) ?: return
        val reactions = parseReactionMap(message.reactionsJson)
        val actorReactions = reactions[actorKey]?.toMutableSet() ?: linkedSetOf()
        when (action) {
            "remove" -> actorReactions.remove(emoji)
            "set" -> {
                actorReactions.clear()
                actorReactions.add(emoji)
            }
            else -> {
                actorReactions.clear()
                actorReactions.add(emoji)
            }
        }
        if (actorReactions.isEmpty()) reactions.remove(actorKey) else reactions[actorKey] = actorReactions.toList()
        db.messageDao().updateReactions(messageId, encodeReactionMap(reactions))
    }

    fun parseReactionMap(raw: String?): MutableMap<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        if (raw.isNullOrBlank()) return result
        return runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { actor ->
                val value = json.get(actor)
                result[actor] = when (value) {
                    is JSONArray -> List(value.length()) { index -> value.optString(index) }.filter { it.isNotBlank() }
                    is String -> listOf(value).filter { it.isNotBlank() }
                    else -> emptyList()
                }
            }
            result
        }.getOrElse { result }
    }

    fun encodeReactionMap(reactions: Map<String, List<String>>): String? {
        if (reactions.isEmpty()) return null
        val json = JSONObject()
        reactions.forEach { (actor, emojis) ->
            val array = JSONArray()
            emojis.distinct().filter { it.isNotBlank() }.forEach { array.put(it) }
            if (array.length() > 0) json.put(actor, array)
        }
        return if (json.length() == 0) null else json.toString()
    }

    fun applyPollVote(messageId: String, voterKey: String, optionIndex: Int) {
        val message = db.messageDao().getMessageById(messageId) ?: return
        val votes = parseReactionMap(message.reactionsJson)
        votes[voterKey] = listOf(optionIndex.toString())
        db.messageDao().updateReactions(messageId, encodeReactionMap(votes))
    }

    fun encodeChatMessagePayload(
        text: String,
        sentAt: Long,
        replyToId: String?,
        replyToText: String?,
        replyToSender: String?,
        replyToType: String?
    ): String {
        return JSONObject().apply {
            put("astraType", "chat_message")
            put("version", 2)
            put("sentAt", sentAt)
            put("text", text)
            replyToId?.takeIf { it.isNotBlank() }?.let {
                put("reply", JSONObject().apply {
                    put("originalMessageId", it)
                    put("originalSender", replyToSender ?: "")
                    put("originalType", replyToType ?: "TEXT")
                    put("originalPreview", replyToText ?: "")
                })
            }
        }.toString()
    }

    fun decodeChatMessagePayload(raw: String): ChatMessagePayload {
        return runCatching {
            val json = JSONObject(raw)
            if (json.optString("astraType") != "chat_message") return@runCatching ChatMessagePayload(raw)
            val reply = json.optJSONObject("reply")
            ChatMessagePayload(
                text = json.optString("text", ""),
                sentAt = json.optLong("sentAt", 0L).takeIf { it > 0L },
                replyToId = reply?.optString("originalMessageId")?.takeIf { it.isNotBlank() },
                replyToSender = reply?.optString("originalSender")?.takeIf { it.isNotBlank() },
                replyToType = reply?.optString("originalType")?.takeIf { it.isNotBlank() },
                replyToText = reply?.optString("originalPreview")?.takeIf { it.isNotBlank() }
            )
        }.getOrElse { ChatMessagePayload(raw) }
    }

    private fun buildEncryptedPayload(
        identity: Identity,
        contact: ContactEntity,
        text: String
    ): MeshProtocol.EncryptedPayload? {
        return try {
            val mySigningKeyHex = CryptoManager.toHex(identity.signingPublicKey)
            val encPub = CryptoManager.fromHexOrNull(contact.encryptionPublicKey, 32) ?: return null
            val (ciphertext, nonce) = CryptoManager.encryptMessage(text, encPub, identity.encryptionSecretKey)
            val signature = CryptoManager.sign(ciphertext + nonce, identity.signingSecretKey)
            MeshProtocol.EncryptedPayload(
                fromSigningKey = mySigningKeyHex,
                toSigningKey = contact.signingPublicKey,
                ciphertextHex = CryptoManager.toHex(ciphertext),
                nonceHex = CryptoManager.toHex(nonce),
                signatureHex = CryptoManager.toHex(signature)
            )
        } catch (e: Exception) {
            Log.e(TAG, "[CRYPTO] buildEncryptedPayload failed", e)
            null
        }
    }
}
