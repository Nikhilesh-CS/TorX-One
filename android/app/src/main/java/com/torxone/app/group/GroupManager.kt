package com.torxone.app.group

import android.content.Context
import android.util.Log
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.GroupEntity
import com.torxone.app.data.GroupMemberEntity
import com.torxone.app.data.GroupKeyEntity
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
            myRole = "admin"
        )
        db.groupDao().insertGroup(group)
        db.groupKeyDao().insertKey(GroupKeyEntity(groupId, 1, GroupCryptoManager.newKeyBase64(), now))

        // 2. Insert creator as member
        db.groupDao().insertGroupMember(
            GroupMemberEntity(
                groupId = groupId,
                memberKey = mySigningKey,
                role = "admin",
                joinedAt = now
            )
        )

        // 3. Insert other members as 'invited'
        memberKeys.forEach { key ->
            db.groupDao().insertGroupMember(
                GroupMemberEntity(
                    groupId = groupId,
                    memberKey = key,
                    role = "invited",
                    joinedAt = now
                )
            )
        }

        // 4. Send GROUP_INVITE to each member
        val payloadObj = JSONObject().apply {
            put("type", MeshProtocol.TYPE_GROUP_INVITE)
            put("groupId", groupId)
            put("groupName", name)
            put("creatorKey", mySigningKey)
            
            val membersArray = JSONArray()
            membersArray.put(mySigningKey)
            memberKeys.forEach { membersArray.put(it) }
            put("members", membersArray)
        }
        val rawPayload = payloadObj.toString()

        memberKeys.forEach { key ->
            scope.launch {
                messageRouter.sendRawPayload(key, rawPayload, MeshProtocol.TYPE_GROUP_INVITE)
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
        val payloadObj = JSONObject().apply {
            put("type", MeshProtocol.TYPE_GROUP_JOIN)
            put("groupId", groupId)
            put("memberKey", mySigningKey)
            put("creatorKey", group.creatorKey)
        }
        val rawPayload = payloadObj.toString()

        scope.launch {
            messageRouter.sendRawPayload(group.creatorKey, rawPayload, MeshProtocol.TYPE_GROUP_JOIN)
        }
        
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

    private suspend fun handleIncomingInvite(json: JSONObject, senderKey: String) {
        val identity = identityManager.loadIdentity() ?: return
        val mySigningKey = com.torxone.app.crypto.CryptoManager.toHex(identity.signingPublicKey)

        val groupId = json.optString("groupId")
        val groupName = json.optString("groupName")
        val creatorKey = json.optString("creatorKey")
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
            avatarUri = null,
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
                    role = if (key == creatorKey) "admin" else "invited",
                    joinedAt = now
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

        // Update their status
        db.groupDao().insertGroupMember(member.copy(role = "member"))
        distributeLatestKey(groupId, memberKey)
        Log.d(TAG, "[GROUP_JOIN] Member $memberKey joined group $groupId")
    }

    /** Creator-only removal; membership update precedes key rotation for remote rejection. */
    suspend fun removeMember(groupId: String, memberKey: String): Boolean = withContext(Dispatchers.IO) {
        val group = db.groupDao().getGroup(groupId) ?: return@withContext false
        val myKey = identityManager.loadIdentity()?.let { CryptoManager.toHex(it.signingPublicKey) } ?: return@withContext false
        if (group.creatorKey != myKey || memberKey == myKey) return@withContext false
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
        val payload = JSONObject().put("groupId", groupId).put("memberKey", myKey).toString()
        messageRouter.sendRawPayload(group.creatorKey, payload, MeshProtocol.TYPE_GROUP_LEAVE).success
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
        db.groupDao().getGroupMembersSync(groupId)
            .filter { it.role != "invited" }
            .forEach { member -> if (member.memberKey != identitySigningKey()) distributeKey(next, member.memberKey) }
    }

    private suspend fun distributeRemoval(groupId: String, removedMemberKey: String) {
        val payload = JSONObject().put("action", "remove_member")
            .put("groupId", groupId).put("memberKey", removedMemberKey).toString()
        db.groupDao().getGroupMembersSync(groupId)
            .filter { it.role != "invited" && it.memberKey != identitySigningKey() }
            .forEach { messageRouter.sendRawPayload(it.memberKey, payload, MeshProtocol.TYPE_GROUP_UPDATE) }
    }

    private suspend fun handleIncomingMembershipUpdate(json: JSONObject, senderKey: String) {
        val groupId = json.optString("groupId")
        val removedMemberKey = json.optString("memberKey")
        val group = db.groupDao().getGroup(groupId) ?: return
        if (senderKey != group.creatorKey || json.optString("action") != "remove_member" || removedMemberKey.isBlank()) {
            Log.w(TAG, "[GROUP_UPDATE] Rejected unauthorized membership update")
            return
        }
        db.groupDao().deleteGroupMember(groupId, removedMemberKey)
    }

    private suspend fun distributeLatestKey(groupId: String, recipientKey: String) {
        db.groupKeyDao().getLatestKey(groupId)?.let { distributeKey(it, recipientKey) }
    }

    private suspend fun distributeKey(key: GroupKeyEntity, recipientKey: String) {
        val payload = JSONObject().apply {
            put("groupId", key.groupId)
            put("keyVersion", key.keyVersion)
            put("aesKeyBase64", key.aesKeyBase64)
        }.toString()
        messageRouter.sendRawPayload(recipientKey, payload, MeshProtocol.TYPE_GROUP_KEY)
    }

    private suspend fun handleIncomingKey(json: JSONObject, senderKey: String) {
        val groupId = json.optString("groupId")
        val version = json.optInt("keyVersion", 0)
        val encodedKey = json.optString("aesKeyBase64")
        val group = db.groupDao().getGroup(groupId) ?: return
        if (senderKey != group.creatorKey || version <= 0 || !isValidAes256Key(encodedKey)) {
            Log.w(TAG, "[GROUP_KEY] Rejected unauthorized or malformed key distribution")
            return
        }
        db.groupKeyDao().insertKey(GroupKeyEntity(groupId, version, encodedKey, System.currentTimeMillis()))
    }

    private fun identitySigningKey(): String = identityManager.loadIdentity()?.let { CryptoManager.toHex(it.signingPublicKey) }.orEmpty()
    private fun isValidAes256Key(value: String) = runCatching { java.util.Base64.getDecoder().decode(value).size == 32 }.getOrDefault(false)
}
