package com.torxone.app.group

import android.content.Context
import android.util.Log
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.GroupEntity
import com.torxone.app.data.GroupMemberEntity
import com.torxone.app.data.GroupKeyEntity
import com.torxone.app.data.GroupInviteEntity
import com.torxone.app.identity.IdentityManager
import com.torxone.app.network.MessageRouter
import com.torxone.app.network.MeshProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.group.GroupCryptoManager

class GroupManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val identityManager: IdentityManager,
    private val messageRouter: MessageRouter
) {
    private val eventManager = GroupEventManager(db, identityManager)

    suspend fun cleanupExpiredMessages() = withContext(Dispatchers.IO) {
        val groups = db.groupDao().getAllGroupsSync()
        val now = System.currentTimeMillis()
        groups.filter { it.disappearingDuration > 0 }.forEach { db.messageDao().deleteExpiredGroupMessages(now, it.disappearingDuration) }
    }

    suspend fun createInviteToken(groupId: String, expiresAt: Long, maxUses: Int = 1): String? = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext null
        val actor = identityManager.loadIdentity() ?: return@withContext null
        val actorKey = CryptoManager.toHex(actor.signingPublicKey)
        if (group.creatorKey != actorKey) return@withContext null
        val inviteId = UUID.randomUUID().toString()
        val body = "$inviteId|$groupId|$actorKey|$expiresAt|$maxUses"
        val signature = CryptoManager.toHex(CryptoManager.sign(body.toByteArray(), actor.signingSecretKey))
        db.groupInviteDao().insertInvite(GroupInviteEntity(inviteId, groupId, actorKey, expiresAt, maxUses.coerceAtLeast(1), signature = signature))
        JSONObject().put("inviteId", inviteId).put("groupId", groupId).put("inviterKey", actorKey).put("expiresAt", expiresAt).put("maxUses", maxUses).put("signature", signature).toString()
    }

    suspend fun revokeInvite(inviteId: String) = withContext(Dispatchers.IO) { db.groupInviteDao().revoke(inviteId) }

    suspend fun validateInviteToken(token: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(token)
            val invite = db.groupInviteDao().getInvite(json.getString("inviteId"))
            val groupId = json.getString("groupId")
            val inviter = json.getString("inviterKey")
            val expires = json.getLong("expiresAt")
            val maxUses = json.getInt("maxUses")
            if (expires <= System.currentTimeMillis() || invite?.revoked == true || (invite != null && invite.uses >= invite.maxUses)) return@withContext false
            if (invite != null && (invite.groupId != groupId || invite.inviterKey != inviter)) return@withContext false
            val body = "${json.getString("inviteId")}|$groupId|$inviter|$expires|$maxUses"
            val pub = CryptoManager.fromHexOrNull(inviter, 32) ?: return@withContext false
            val sig = CryptoManager.fromHexOrNull(json.getString("signature"), 64) ?: return@withContext false
            CryptoManager.verify(body.toByteArray(), sig, pub)
        }.getOrDefault(false)
    }

    suspend fun requestJoinWithInvite(token: String): Boolean = withContext(Dispatchers.IO) {
        if (!validateInviteToken(token)) return@withContext false
        val identity = identityManager.loadIdentity() ?: return@withContext false
        val json = JSONObject(token)
        val payload = JSONObject().put("token", token).put("groupId", json.getString("groupId"))
            .put("memberKey", CryptoManager.toHex(identity.signingPublicKey)).toString()
        messageRouter.sendRawPayload(json.getString("inviterKey"), payload, MeshProtocol.TYPE_GROUP_JOIN_REQUEST).success
    }

    suspend fun approveJoin(groupId: String, memberKey: String): Boolean = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        val actor = db.groupDao().getGroupMember(groupId, identitySigningKey())
        val target = db.groupDao().getGroupMember(groupId, memberKey)
        if (!GroupPermission.canManageAdmins(actor) || target == null || target.membershipState != "PENDING_APPROVAL") return@withContext false
        db.groupDao().insertGroupMember(target.copy(role = "member", membershipState = "MEMBER"))
        val event = eventManager.createLocalEvent(groupId, "MEMBER_APPROVED", targetKey = memberKey)
        val wire = JSONObject().put("action", "join_approved").put("groupId", groupId).put("memberKey", memberKey).apply { mergeEvent(event) }.toString()
        sendControl(groupId, memberKey, wire, MeshProtocol.TYPE_GROUP_UPDATE)
        rotateAndDistribute(groupId)
        true
    }

    suspend fun rejectJoin(groupId: String, memberKey: String): Boolean = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        val actor = db.groupDao().getGroupMember(groupId, identitySigningKey())
        val target = db.groupDao().getGroupMember(groupId, memberKey)
        if (!GroupPermission.canManageAdmins(actor) || target == null || target.membershipState != "PENDING_APPROVAL") return@withContext false
        db.groupDao().insertGroupMember(target.copy(role = "removed", membershipState = "REMOVED"))
        true
    }
    companion object {
        private const val TAG = "GroupManager"
    }

    /**
     * Creates a new group locally, inserts the creator and invited members into the DB,
     * and sends a GROUP_INVITE to all members via MessageRouter.
     */
    suspend fun createGroupAndInvite(name: String, avatarUri: String?, memberKeys: List<String>): String = withContext(Dispatchers.IO) {
        val identity = identityManager.loadIdentity() ?: throw IllegalStateException("Not logged in")
        val mySigningKey = com.torxone.app.crypto.CryptoManager.toHex(identity.signingPublicKey)
        val groupId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 1. Insert GroupEntity with creator as admin
        val group = GroupEntity(
            groupId = groupId,
            name = name,
            avatarUri = avatarUri,
            creatorKey = mySigningKey,
            createdAt = now,
            myRole = "owner"
        )
        db.groupDao().insertGroup(group)
        db.groupKeyDao().insertKey(GroupKeyEntity(groupId, 1, GroupCryptoManager.newKeyBase64(), now))

        // 2. Insert creator as member
        db.groupDao().insertGroupMember(
            GroupMemberEntity(
                groupId = groupId,
                memberKey = mySigningKey,
                role = "owner",
                joinedAt = now,
                membershipState = "MEMBER"
            )
        )

        // 3. Insert other members as 'invited'
        memberKeys.forEach { key ->
            db.groupDao().insertGroupMember(
                GroupMemberEntity(
                    groupId = groupId,
                    memberKey = key,
                    role = "invited",
                    joinedAt = now,
                    membershipState = "INVITED"
                )
            )
        }
        val createEvent = eventManager.createLocalEvent(groupId, "GROUP_CREATED", keyVersion = 1, payload = JSONObject().put("name", name))

        // 4. Send GROUP_INVITE to each member
        val payloadObj = JSONObject().apply {
            put("type", MeshProtocol.TYPE_GROUP_INVITE)
            put("groupId", groupId)
            put("groupName", name)
            put("creatorKey", mySigningKey)
            mergeEvent(createEvent)
            
            val membersArray = JSONArray()
            membersArray.put(mySigningKey)
            memberKeys.forEach { membersArray.put(it) }
            put("members", membersArray)
        }
        val rawPayload = payloadObj.toString()

        memberKeys.forEach { key ->
            scope.launch {
                sendControl(groupId, key, rawPayload, MeshProtocol.TYPE_GROUP_INVITE)
            }
        }

        return@withContext groupId
    }

    /**
     * Accepts a group invite. Changes myRole to "member" and sends GROUP_JOIN back to creator.
     */
    suspend fun acceptInvite(groupId: String): Boolean = withContext(Dispatchers.IO) {
        val identity = identityManager.loadIdentity() ?: return@withContext false
        val mySigningKey = com.torxone.app.crypto.CryptoManager.toHex(identity.signingPublicKey)
        
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        if (group.myRole != "invited") return@withContext false

        // Update local role
        db.groupDao().insertGroup(group.copy(myRole = "member"))

        // Send GROUP_JOIN to creator
        val joinEvent = eventManager.createLocalEvent(groupId, "MEMBER_JOINED", targetKey = mySigningKey, payload = JSONObject())
        val payloadObj = JSONObject().apply {
            put("type", MeshProtocol.TYPE_GROUP_JOIN)
            put("groupId", groupId)
            put("memberKey", mySigningKey)
            put("creatorKey", group.creatorKey)
            mergeEvent(joinEvent)
        }
        val rawPayload = payloadObj.toString()

        scope.launch {
            sendControl(groupId, group.creatorKey, rawPayload, MeshProtocol.TYPE_GROUP_JOIN)
        }
        
        true
    }

    /** Creator-only invitation path for members added after group creation. */
    suspend fun addMembers(groupId: String, memberKeys: List<String>): Boolean = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        val myKey = identitySigningKey()
        val actor = db.groupDao().getGroupMember(groupId, myKey)
        if (myKey.isBlank() || !GroupPermission.canAddMembers(group, actor)) return@withContext false
        val newKeys = memberKeys.distinct().filter { it != myKey && db.groupDao().getGroupMember(groupId, it) == null }
        if (newKeys.isEmpty()) return@withContext false
        val now = System.currentTimeMillis()
        newKeys.forEach { db.groupDao().insertGroupMember(GroupMemberEntity(groupId, it, "invited", now, "INVITED")) }
        val members = db.groupDao().getGroupMembersSync(groupId)
        val inviteEvent = eventManager.createLocalEvent(groupId, "MEMBER_INVITED", payload = JSONObject().put("count", newKeys.size))
        val invite = JSONObject().apply {
            put("groupId", groupId)
            put("groupName", group.name)
            put("creatorKey", group.creatorKey)
            mergeEvent(inviteEvent)
            put("avatarUri", group.avatarUri)
            put("members", JSONArray().apply { members.forEach { put(it.memberKey) } })
        }.toString()
        newKeys.forEach { sendControl(groupId, it, invite, MeshProtocol.TYPE_GROUP_INVITE) }
        true
    }

    /** Creator-only metadata sync. URI publication is limited to app-accessible values. */
    suspend fun updateGroupMetadata(groupId: String, name: String, avatarUri: String?, description: String? = null, whoCanSend: String? = null, whoCanEditInfo: String? = null, whoCanAddMembers: String? = null): Boolean = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        val actor = db.groupDao().getGroupMember(groupId, identitySigningKey())
        if (!GroupPermission.canEditInfo(group, actor) || name.isBlank()) return@withContext false
        val updated = group.copy(name = name.trim(), avatarUri = avatarUri, description = description ?: group.description, whoCanSend = whoCanSend ?: group.whoCanSend, whoCanEditInfo = whoCanEditInfo ?: group.whoCanEditInfo, whoCanAddMembers = whoCanAddMembers ?: group.whoCanAddMembers, updatedAt = System.currentTimeMillis(), metadataVersion = group.metadataVersion + 1)
        db.groupDao().insertGroup(updated)
        val updateEvent = eventManager.createLocalEvent(groupId, "GROUP_INFO_UPDATE", payload = JSONObject().put("name", updated.name).put("avatarUri", avatarUri ?: "").put("description", updated.description ?: "").put("whoCanSend", updated.whoCanSend).put("whoCanEditInfo", updated.whoCanEditInfo).put("whoCanAddMembers", updated.whoCanAddMembers))
        val payload = JSONObject().put("action", "metadata").put("groupId", groupId)
            .put("name", updated.name).put("avatarUri", avatarUri).put("description", updated.description).put("whoCanSend", updated.whoCanSend).put("whoCanEditInfo", updated.whoCanEditInfo).put("whoCanAddMembers", updated.whoCanAddMembers).apply { mergeEvent(updateEvent) }.toString()
        db.groupDao().getGroupMembersSync(groupId).filter { it.role != "invited" && it.memberKey != identitySigningKey() }
            .forEach { sendControl(groupId, it.memberKey, payload, MeshProtocol.TYPE_GROUP_UPDATE) }
        true
    }

    suspend fun setDisappearingDuration(groupId: String, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        val actor = db.groupDao().getGroupMember(groupId, identitySigningKey())
        if (!GroupPermission.canEditInfo(group, actor) || durationMs < 0) return@withContext false
        db.groupDao().insertGroup(group.copy(disappearingDuration = durationMs, updatedAt = System.currentTimeMillis(), metadataVersion = group.metadataVersion + 1))
        val event = eventManager.createLocalEvent(groupId, "GROUP_INFO_UPDATE", payload = JSONObject().put("name", group.name).put("avatarUri", group.avatarUri ?: "").put("disappearingDuration", durationMs))
        val wire = JSONObject().put("action", "metadata").put("groupId", groupId).put("name", group.name).put("avatarUri", group.avatarUri).put("disappearingDuration", durationMs).apply { mergeEvent(event) }.toString()
        db.groupDao().getGroupMembersSync(groupId).filter { it.role != "invited" && it.memberKey != identitySigningKey() }.forEach { sendControl(groupId, it.memberKey, wire, MeshProtocol.TYPE_GROUP_UPDATE) }
        true
    }

    suspend fun setJoinApprovalRequired(groupId: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        if (!GroupPermission.canManageAdmins(db.groupDao().getGroupMember(groupId, identitySigningKey()))) return@withContext false
        db.groupDao().insertGroup(group.copy(approvalRequired = enabled, updatedAt = System.currentTimeMillis(), metadataVersion = group.metadataVersion + 1))
        true
    }

    suspend fun changeMemberRole(groupId: String, memberKey: String, newRole: String): Boolean = withContext(Dispatchers.IO) {
        if (newRole !in setOf("admin", "member")) return@withContext false
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        val actorKey = identitySigningKey()
        val actor = db.groupDao().getGroupMember(groupId, actorKey)
        val target = db.groupDao().getGroupMember(groupId, memberKey)
        if (!GroupPermission.canManageAdmins(actor) || target == null || target.membershipState != "MEMBER" || memberKey == group.creatorKey) return@withContext false
        db.groupDao().insertGroupMember(target.copy(role = newRole))
        val event = eventManager.createLocalEvent(groupId, "ROLE_CHANGE", targetKey = memberKey, payload = JSONObject().put("role", newRole))
        val wire = JSONObject().put("action", "role_change").put("groupId", groupId).put("memberKey", memberKey).put("role", newRole).apply { mergeEvent(event) }.toString()
        db.groupDao().getGroupMembersSync(groupId).filter { it.role != "invited" && it.memberKey != actorKey }.forEach { sendControl(groupId, it.memberKey, wire, MeshProtocol.TYPE_GROUP_UPDATE) }
        true
    }

    suspend fun transferOwnership(groupId: String, memberKey: String): Boolean = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        val actorKey = identitySigningKey()
        val actor = db.groupDao().getGroupMember(groupId, actorKey)
        val target = db.groupDao().getGroupMember(groupId, memberKey)
        if (!GroupPermission.canManageAdmins(actor) || target == null || target.membershipState != "MEMBER" || memberKey == actorKey) return@withContext false
        db.groupDao().insertGroupMember(actor!!.copy(role = "admin"))
        db.groupDao().insertGroupMember(target.copy(role = "owner"))
        db.groupDao().insertGroup(group.copy(creatorKey = memberKey, myRole = "admin", updatedAt = System.currentTimeMillis()))
        val event = eventManager.createLocalEvent(groupId, "OWNERSHIP_TRANSFER", targetKey = memberKey, payload = JSONObject())
        val wire = JSONObject().put("action", "ownership_transfer").put("groupId", groupId).put("memberKey", memberKey).apply { mergeEvent(event) }.toString()
        db.groupDao().getGroupMembersSync(groupId).filter { it.role != "invited" && it.memberKey != actorKey }.forEach { sendControl(groupId, it.memberKey, wire, MeshProtocol.TYPE_GROUP_UPDATE) }
        rotateAndDistribute(groupId)
        true
    }

    /**
     * Changes only this device's notification preference.  This deliberately is
     * not broadcast: a member muting a group must never change another
     * member's notification settings.
     */
    suspend fun setGroupMuted(groupId: String, muted: Boolean): Boolean = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        db.groupDao().insertGroup(group.copy(muteUntil = if (muted) -1L else 0L))
        true
    }

    /**
     * Declines a group invite. Deletes the group and all its members from local DB.
     */
    suspend fun declineInvite(groupId: String) = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext
        if (group.myRole == "invited") {
            // foreignKey cascade will delete members
            db.groupDao().deleteGroup(groupId)
        }
    }

    /**
     * Route incoming group-related payloads from MessageRouter.
     */
    fun handleGroupPacket(messageType: String, plaintext: String, senderKey: String) {
        scope.launch(Dispatchers.IO) {
            val json = try { JSONObject(plaintext) } catch (e: Exception) { return@launch }
            if (messageType == MeshProtocol.TYPE_GROUP_SYNC_REQUEST) {
                handleSyncRequest(json, senderKey)
                return@launch
            }
            if (messageType == MeshProtocol.TYPE_GROUP_SYNC_RESPONSE) {
                handleSyncResponse(json, senderKey)
                return@launch
            }
            if (messageType == MeshProtocol.TYPE_GROUP_KEY_REQUEST) {
                val groupId = json.optString("groupId")
                val group = db.groupDao().getGroup(groupId)
                if (group != null && group.creatorKey == identitySigningKey() && db.groupDao().getGroupMember(groupId, senderKey) != null) {
                    db.groupKeyDao().getLatestKey(groupId)?.let { key ->
                        val event = db.groupEventDao().getKeyRotationEvent(groupId, key.keyVersion)?.let { row ->
                            GroupEventManager.SignedEvent(row.eventId, row.groupVersion, JSONObject().apply { put("eventId", row.eventId); put("groupId", row.groupId); put("eventType", row.eventType); put("actorKey", row.actorKey); put("groupVersion", row.groupVersion); put("keyVersion", row.keyVersion); put("createdAt", row.createdAt); put("payload", JSONObject(row.payload)); put("signature", row.signature) })
                        }
                        distributeKey(key, senderKey, event)
                    }
                }
                return@launch
            }
            if (messageType == MeshProtocol.TYPE_GROUP_INVITE_LINK) return@launch
            if (messageType == MeshProtocol.TYPE_GROUP_JOIN_REQUEST) {
                handleJoinRequest(json, senderKey)
                return@launch
            }
            val eventId = json.optString("eventId")
            val groupId = json.optString("groupId")
            if (groupId.isBlank() || !eventManager.verifyIncoming(json, groupId, senderKey)) return@launch
            val eventVersion = json.optLong("groupVersion", 0L)
            val sync = db.groupSyncDao().getState(groupId)
            if (eventVersion > 0 && sync != null && eventVersion > sync.lastKnownGroupVersion + 1) {
                requestSync(groupId, senderKey, sync.lastKnownGroupVersion)
                return@launch
            }
            if (!eventManager.markIncoming(groupId, eventId, senderKey, messageType)) return@launch
            if (eventVersion > 0) db.groupSyncDao().upsertState(com.torxone.app.data.GroupSyncStateEntity(groupId, maxOf(eventVersion, sync?.lastKnownGroupVersion ?: 0L), eventId, System.currentTimeMillis()))
            
            if (messageType == MeshProtocol.TYPE_GROUP_INVITE) {
                handleIncomingInvite(json, senderKey)
            } else if (messageType == MeshProtocol.TYPE_GROUP_JOIN) {
                handleIncomingJoin(json, senderKey)
            } else if (messageType == MeshProtocol.TYPE_GROUP_UPDATE) {
                handleIncomingMembershipUpdate(json, senderKey)
            } else if (messageType == MeshProtocol.TYPE_GROUP_LEAVE) {
                handleIncomingLeave(json, senderKey)
            } else if (messageType == MeshProtocol.TYPE_GROUP_KEY) {
                handleIncomingKey(json, senderKey)
            }
        }
    }

    private suspend fun handleJoinRequest(json: JSONObject, senderKey: String) {
        val groupId = json.optString("groupId")
        val group = db.groupDao().getGroup(groupId) ?: return
        if (group.creatorKey != identitySigningKey() || senderKey != json.optString("memberKey")) return
        val token = json.optString("token")
        if (!validateInviteToken(token)) return
        val existing = db.groupDao().getGroupMember(groupId, senderKey)
        if (existing?.membershipState == "REMOVED" || existing?.membershipState == "LEFT") return
        val now = System.currentTimeMillis()
        if (existing == null) db.groupDao().insertGroupMember(GroupMemberEntity(groupId, senderKey, "pending", now, if (group.approvalRequired) "PENDING_APPROVAL" else "MEMBER"))
        if (!group.approvalRequired) {
            db.groupDao().insertGroupMember(db.groupDao().getGroupMember(groupId, senderKey)!!.copy(role = "member", membershipState = "MEMBER"))
            rotateAndDistribute(groupId)
        }
        db.groupInviteDao().incrementUses(JSONObject(token).getString("inviteId"))
        com.torxone.app.service.NotificationHelper.showGroupJoinRequest(context, groupId, group.name, senderKey)
    }

    private suspend fun requestSync(groupId: String, recipientKey: String, fromVersion: Long) {
        val payload = JSONObject().put("groupId", groupId).put("fromVersion", fromVersion).toString()
        messageRouter.sendRawPayload(recipientKey, payload, MeshProtocol.TYPE_GROUP_SYNC_REQUEST)
    }

    suspend fun requestMissingKey(groupId: String, recipientKey: String, keyVersion: Int) {
        val payload = JSONObject().put("groupId", groupId).put("keyVersion", keyVersion).toString()
        messageRouter.sendRawPayload(recipientKey, payload, MeshProtocol.TYPE_GROUP_KEY_REQUEST)
    }

    private suspend fun handleSyncRequest(json: JSONObject, senderKey: String) {
        val groupId = json.optString("groupId")
        val group = db.groupDao().getGroup(groupId) ?: return
        if (db.groupDao().getGroupMember(groupId, senderKey) == null) return
        val from = json.optLong("fromVersion", 0L)
        val events = JSONArray()
        db.groupEventDao().getEvents(groupId).filter { it.groupVersion > from }.forEach { event ->
            events.put(JSONObject().apply {
                put("eventId", event.eventId); put("groupId", event.groupId); put("eventType", event.eventType); put("actorKey", event.actorKey)
                event.targetKey?.let { put("targetKey", it) }; put("groupVersion", event.groupVersion); put("keyVersion", event.keyVersion); put("createdAt", event.createdAt); put("payload", JSONObject(event.payload)); put("signature", event.signature)
            })
        }
        messageRouter.sendRawPayload(senderKey, JSONObject().put("groupId", groupId).put("events", events).toString(), MeshProtocol.TYPE_GROUP_SYNC_RESPONSE)
    }

    private suspend fun handleSyncResponse(json: JSONObject, senderKey: String) {
        val groupId = json.optString("groupId")
        val group = db.groupDao().getGroup(groupId) ?: return
        if (db.groupDao().getGroupMember(groupId, senderKey) == null) return
        val events = json.optJSONArray("events") ?: return
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (!eventManager.verifyIncoming(event, groupId, event.optString("actorKey"))) continue
            val id = event.optString("eventId")
            if (!eventManager.markIncoming(groupId, id, event.optString("actorKey"), event.optString("eventType"))) continue
            db.groupSyncDao().upsertState(com.torxone.app.data.GroupSyncStateEntity(groupId, event.optLong("groupVersion"), id, System.currentTimeMillis()))
            applySyncedEvent(group, event)
        }
    }

    private fun applySyncedEvent(group: GroupEntity, event: JSONObject) {
        val type = event.optString("eventType")
        val target = event.optString("targetKey")
        when (type) {
            "GROUP_INFO_UPDATE" -> event.optJSONObject("payload")?.let { p ->
                db.groupDao().insertGroup(group.copy(name = p.optString("name", group.name), description = p.optString("description").takeIf { it.isNotBlank() }, avatarUri = p.optString("avatarUri").takeIf { it.isNotBlank() }, disappearingDuration = p.optLong("disappearingDuration", group.disappearingDuration), whoCanSend = p.optString("whoCanSend", group.whoCanSend), whoCanEditInfo = p.optString("whoCanEditInfo", group.whoCanEditInfo), whoCanAddMembers = p.optString("whoCanAddMembers", group.whoCanAddMembers), metadataVersion = event.optLong("groupVersion", group.metadataVersion)))
            }
            "MEMBER_REMOVED", "MEMBER_LEFT" -> if (target.isNotBlank()) db.groupDao().deleteGroupMember(group.groupId, target)
            "ROLE_CHANGE" -> if (target.isNotBlank()) db.groupDao().getGroupMember(group.groupId, target)?.let { db.groupDao().insertGroupMember(it.copy(role = event.optJSONObject("payload")?.optString("role", it.role) ?: it.role)) }
            "OWNERSHIP_TRANSFER" -> if (target.isNotBlank()) db.groupDao().getGroupMember(group.groupId, target)?.let { db.groupDao().insertGroupMember(it.copy(role = "owner")); db.groupDao().insertGroup(group.copy(creatorKey = target, myRole = if (identitySigningKey() == target) "owner" else "admin")) }
            "KEY_ROTATED" -> event.optJSONObject("payload")?.optString("aesKeyBase64")?.takeIf { it.isNotBlank() }?.let { db.groupKeyDao().insertKey(com.torxone.app.data.GroupKeyEntity(group.groupId, event.optInt("keyVersion"), it, System.currentTimeMillis())) }
        }
    }

    private suspend fun handleIncomingInvite(json: JSONObject, senderKey: String) {
        val identity = identityManager.loadIdentity() ?: return
        val mySigningKey = com.torxone.app.crypto.CryptoManager.toHex(identity.signingPublicKey)

        val groupId = json.optString("groupId")
        val groupName = json.optString("groupName")
        val creatorKey = json.optString("creatorKey")
        val avatarUri = json.optString("avatarUri").takeIf { it.isNotBlank() }
        val membersArray = json.optJSONArray("members")
        
        if (groupId.isBlank() || groupName.isBlank() || creatorKey.isBlank() || membersArray == null) {
            Log.w(TAG, "[GROUP_INVITE] Malformed payload, missing fields")
            return
        }

        if (senderKey != creatorKey) {
            Log.w(TAG, "[GROUP_INVITE] Sender ($senderKey) does not match claimed creator ($creatorKey)")
            return
        }

        var amIIncluded = false
        val parsedMembers = mutableListOf<String>()
        for (i in 0 until membersArray.length()) {
            val memKey = membersArray.optString(i)
            if (memKey.isNotBlank()) {
                parsedMembers.add(memKey)
                if (memKey == mySigningKey) amIIncluded = true
            }
        }

        if (!amIIncluded) {
            Log.w(TAG, "[GROUP_INVITE] I am not in the member list of this invite")
            return
        }

        val existingGroup = db.groupDao().getGroup(groupId)
        if (existingGroup != null) {
            Log.w(TAG, "[GROUP_INVITE] Group $groupId already exists locally")
            return
        }

        // Insert as 'invited'
        val now = System.currentTimeMillis()
        val group = GroupEntity(
            groupId = groupId,
            name = groupName,
            avatarUri = avatarUri,
            creatorKey = creatorKey,
            createdAt = now,
            myRole = "invited"
        )
        db.groupDao().insertGroup(group)

        parsedMembers.forEach { key ->
            db.groupDao().insertGroupMember(
                GroupMemberEntity(
                    groupId = groupId,
                    memberKey = key,
                    role = if (key == creatorKey) "owner" else "invited",
                    joinedAt = now,
                    membershipState = if (key == creatorKey) "MEMBER" else "INVITED"
                )
            )
        }

        Log.d(TAG, "[GROUP_INVITE] Group $groupId created successfully with role=invited")
    }

    private suspend fun handleIncomingJoin(json: JSONObject, senderKey: String) {
        val groupId = json.optString("groupId")
        val memberKey = json.optString("memberKey")
        val creatorKey = json.optString("creatorKey")
        
        if (groupId.isBlank() || memberKey.isBlank() || creatorKey.isBlank()) {
            Log.w(TAG, "[GROUP_JOIN] Malformed payload")
            return
        }
        
        if (senderKey != memberKey) {
            Log.w(TAG, "[GROUP_JOIN] Sender ($senderKey) does not match claimed joining member ($memberKey)")
            return
        }

        val group = db.groupDao().getGroup(groupId)
        if (group == null) {
            Log.w(TAG, "[GROUP_JOIN] Unknown group $groupId")
            return
        }

        if (group.creatorKey != creatorKey) {
            Log.w(TAG, "[GROUP_JOIN] Claimed creator ($creatorKey) does not match actual creator (${group.creatorKey})")
            return
        }
        
        val identity = identityManager.loadIdentity() ?: return
        val mySigningKey = com.torxone.app.crypto.CryptoManager.toHex(identity.signingPublicKey)

        if (group.creatorKey != mySigningKey) {
            Log.w(TAG, "[GROUP_JOIN] I am not the creator, but received GROUP_JOIN for $groupId")
            return
        }

        val members = db.groupDao().getGroupMembersSync(groupId)
        val member = members.find { it.memberKey == memberKey }
        if (member == null) {
            Log.w(TAG, "[GROUP_JOIN] Member $memberKey was not invited to group $groupId")
            return
        }

        if (group.approvalRequired) {
            db.groupDao().insertGroupMember(member.copy(role = "pending", membershipState = "PENDING_APPROVAL"))
            return
        }
        db.groupDao().insertGroupMember(member.copy(role = "member", membershipState = "MEMBER"))
        rotateAndDistribute(groupId)
        Log.d(TAG, "[GROUP_JOIN] Member $memberKey joined group $groupId")
    }

    /** Creator-only removal; membership update precedes key rotation for remote rejection. */
    suspend fun removeMember(groupId: String, memberKey: String): Boolean = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        val myKey = identityManager.loadIdentity()?.let { CryptoManager.toHex(it.signingPublicKey) } ?: return@withContext false
        val actor = db.groupDao().getGroupMember(groupId, myKey)
        if (!GroupPermission.canRemoveMembers(actor) || memberKey == myKey) return@withContext false
        if (db.groupDao().getGroupMember(groupId, memberKey) == null) return@withContext false
        db.groupDao().deleteGroupMember(groupId, memberKey)
        distributeRemoval(groupId, memberKey)
        rotateAndDistribute(groupId)
        true
    }

    suspend fun leaveGroup(groupId: String): Boolean = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        val myKey = identityManager.loadIdentity()?.let { CryptoManager.toHex(it.signingPublicKey) } ?: return@withContext false
        if (myKey == group.creatorKey) return@withContext false // creator must explicitly remove/close the group in V1
        val leaveEvent = eventManager.createLocalEvent(groupId, "MEMBER_LEFT", targetKey = myKey)
        val payload = JSONObject().put("groupId", groupId).put("memberKey", myKey).apply { mergeEvent(leaveEvent) }.toString()
        sendControl(groupId, group.creatorKey, payload, MeshProtocol.TYPE_GROUP_LEAVE).success
    }

    private suspend fun handleIncomingLeave(json: JSONObject, senderKey: String) {
        val groupId = json.optString("groupId")
        val memberKey = json.optString("memberKey")
        val group = db.groupDao().getGroup(groupId) ?: return
        val myKey = identityManager.loadIdentity()?.let { CryptoManager.toHex(it.signingPublicKey) } ?: return
        if (senderKey != memberKey || group.creatorKey != myKey || memberKey == myKey) return
        if (db.groupDao().getGroupMember(groupId, memberKey) == null) return
        db.groupDao().deleteGroupMember(groupId, memberKey)
        distributeRemoval(groupId, memberKey)
        rotateAndDistribute(groupId)
    }

    private suspend fun rotateAndDistribute(groupId: String) {
        val latest = db.groupKeyDao().getLatestKey(groupId) ?: return
        val next = GroupKeyEntity(groupId, latest.keyVersion + 1, GroupCryptoManager.newKeyBase64(), System.currentTimeMillis())
        db.groupKeyDao().insertKey(next)
        db.groupDao().getGroup(groupId)?.let {
            db.groupDao().insertGroup(it.copy(currentKeyVersion = next.keyVersion, updatedAt = System.currentTimeMillis()))
        }
        val rotateEvent = eventManager.createLocalEvent(groupId, "KEY_ROTATED", keyVersion = next.keyVersion, payload = JSONObject().put("aesKeyBase64", next.aesKeyBase64))
        db.groupDao().getGroupMembersSync(groupId)
            .filter { it.role != "invited" }
            .forEach { member -> if (member.memberKey != identitySigningKey()) distributeKey(next, member.memberKey, rotateEvent) }
    }

    private suspend fun distributeRemoval(groupId: String, removedMemberKey: String) {
        val removeEvent = eventManager.createLocalEvent(groupId, "MEMBER_REMOVED", targetKey = removedMemberKey)
        val payload = JSONObject().put("action", "remove_member")
            .put("groupId", groupId).put("memberKey", removedMemberKey).apply { mergeEvent(removeEvent) }.toString()
        db.groupDao().getGroupMembersSync(groupId)
            .filter { it.role != "invited" && it.memberKey != identitySigningKey() }
            .forEach { sendControl(groupId, it.memberKey, payload, MeshProtocol.TYPE_GROUP_UPDATE) }
    }

    private suspend fun handleIncomingMembershipUpdate(json: JSONObject, senderKey: String) {
        val groupId = json.optString("groupId")
        val removedMemberKey = json.optString("memberKey")
        val group = db.groupDao().getGroup(groupId) ?: return
        val actor = db.groupDao().getGroupMember(groupId, senderKey)
        if (json.optString("action") == "join_approved") {
            val targetKey = json.optString("memberKey")
            if (senderKey != group.creatorKey || targetKey != identitySigningKey()) return
            db.groupDao().getGroupMember(groupId, targetKey)?.let { db.groupDao().insertGroupMember(it.copy(role = "member", membershipState = "MEMBER")) }
            db.groupDao().insertGroup(group.copy(myRole = "member"))
            return
        }
        if (json.optString("action") == "role_change" || json.optString("action") == "ownership_transfer") {
            if (!GroupPermission.canManageAdmins(actor)) return
            if (json.optString("eventType") != if (json.optString("action") == "role_change") "ROLE_CHANGE" else "OWNERSHIP_TRANSFER") return
            val targetKey = json.optString("memberKey")
            val target = db.groupDao().getGroupMember(groupId, targetKey) ?: return
            if (json.optString("targetKey") != targetKey) return
            if (json.optString("action") == "role_change") {
                val role = json.optString("role")
                if (role !in setOf("admin", "member") || targetKey == group.creatorKey) return
                db.groupDao().insertGroupMember(target.copy(role = role))
            } else {
                db.groupDao().insertGroupMember(target.copy(role = "owner"))
                val currentKey = identitySigningKey()
                db.groupDao().getGroupMember(groupId, currentKey)?.let { db.groupDao().insertGroupMember(it.copy(role = "admin")) }
                db.groupDao().insertGroup(group.copy(creatorKey = targetKey, myRole = if (currentKey == targetKey) "owner" else "admin"))
            }
            return
        }
        if (!GroupPermission.canRemoveMembers(actor) || json.optString("action") != "remove_member" || removedMemberKey.isBlank()) {
            if (!GroupPermission.canEditInfo(group, actor) || json.optString("action") != "metadata" || json.optString("eventType") != "GROUP_INFO_UPDATE") {
                Log.w(TAG, "[GROUP_UPDATE] Rejected unauthorized group update")
                return
            }
            val name = json.optString("name").trim()
            if (name.isBlank()) return
            val eventPayload = json.optJSONObject("payload")
            if (eventPayload == null || eventPayload.optString("name") != name || eventPayload.optString("avatarUri") != json.optString("avatarUri")) return
                db.groupDao().insertGroup(group.copy(name = name, avatarUri = json.optString("avatarUri").takeIf { it.isNotBlank() }, description = json.optString("description").takeIf { it.isNotBlank() }, disappearingDuration = json.optLong("disappearingDuration", group.disappearingDuration), whoCanSend = json.optString("whoCanSend", group.whoCanSend), whoCanEditInfo = json.optString("whoCanEditInfo", group.whoCanEditInfo), whoCanAddMembers = json.optString("whoCanAddMembers", group.whoCanAddMembers), metadataVersion = maxOf(group.metadataVersion, json.optLong("groupVersion", group.metadataVersion)), updatedAt = System.currentTimeMillis()))
            return
        }
        if (json.optString("eventType") != "MEMBER_REMOVED" || json.optString("targetKey") != removedMemberKey) return
        db.groupDao().deleteGroupMember(groupId, removedMemberKey)
    }

    private suspend fun distributeLatestKey(groupId: String, recipientKey: String) {
        db.groupKeyDao().getLatestKey(groupId)?.let { distributeKey(it, recipientKey, null) }
    }

    private suspend fun distributeKey(key: GroupKeyEntity, recipientKey: String, event: GroupEventManager.SignedEvent?) {
        val payload = JSONObject().apply {
            put("groupId", key.groupId)
            put("keyVersion", key.keyVersion)
            put("aesKeyBase64", key.aesKeyBase64)
            mergeEvent(event)
        }.toString()
        sendControl(key.groupId, recipientKey, payload, MeshProtocol.TYPE_GROUP_KEY)
    }

    private suspend fun sendControl(groupId: String, recipientKey: String, payload: String, type: String): com.torxone.app.network.SendResult {
        val result = messageRouter.sendRawPayload(recipientKey, payload, type)
        if (!result.success) {
            val json = runCatching { JSONObject(payload) }.getOrNull()
            val eventId = json?.optString("eventId").orEmpty()
            if (eventId.isNotBlank()) db.groupSyncDao().upsertPending(com.torxone.app.data.PendingGroupEventEntity(eventId, groupId, recipientKey, payload, type, createdAt = System.currentTimeMillis(), nextRetryAt = System.currentTimeMillis() + 30_000L, expiresAt = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L))
        }
        return result
    }

    suspend fun retryPendingEvents() {
        val now = System.currentTimeMillis()
        db.groupSyncDao().duePending(now).forEach { pending ->
            val result = messageRouter.sendRawPayload(pending.recipientKey, pending.payload, pending.eventType)
            if (result.success) db.groupSyncDao().deletePendingForRecipient(pending.eventId, pending.recipientKey)
            else if (pending.retryCount >= 12 || pending.expiresAt <= now) db.groupSyncDao().deletePending(pending.eventId)
            else db.groupSyncDao().reschedule(pending.eventId, pending.recipientKey, pending.retryCount + 1, now + (30_000L shl pending.retryCount.coerceAtMost(5)))
        }
    }

    private fun JSONObject.mergeEvent(event: GroupEventManager.SignedEvent?) {
        event?.json?.keys()?.forEachRemaining { key -> put(key, event.json.get(key)) }
    }

    private suspend fun handleIncomingKey(json: JSONObject, senderKey: String) {
        val groupId = json.optString("groupId")
        val version = json.optInt("keyVersion", 0)
        val encodedKey = json.optString("aesKeyBase64")
        val group = db.groupDao().getGroup(groupId) ?: return
        val senderMember = db.groupDao().getGroupMember(groupId, senderKey)
        if (!GroupPermission.canRemoveMembers(senderMember) || json.optString("eventType") != "KEY_ROTATED" || version <= 0 || !isValidAes256Key(encodedKey)) {
            Log.w(TAG, "[GROUP_KEY] Rejected unauthorized or malformed key distribution")
            return
        }
        db.groupKeyDao().insertKey(GroupKeyEntity(groupId, version, encodedKey, System.currentTimeMillis()))
        db.groupDao().insertGroup(group.copy(currentKeyVersion = maxOf(group.currentKeyVersion, version), updatedAt = System.currentTimeMillis()))
    }

    private fun identitySigningKey(): String = identityManager.loadIdentity()?.let { CryptoManager.toHex(it.signingPublicKey) }.orEmpty()
    private fun isValidAes256Key(value: String) = runCatching { java.util.Base64.getDecoder().decode(value).size == 32 }.getOrDefault(false)
}
