package com.torxone.app.groups

import com.torxone.app.data.entity.GroupMemberRole
import com.torxone.app.data.entity.GroupMemberState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Deterministic canonical binary codec for group control protocol payloads.
 * Avoids any platform JSON stubbing issues and guarantees fast, compact serialization.
 */
object GroupProtocolCodec {

    private const val INVITE_MAGIC = 0x54584749      // "TXGI"
    private const val JOINED_MAGIC = 0x5458474A      // "TXGJ"
    private const val REMOVE_MAGIC = 0x54584752      // "TXGR"
    private const val LEAVE_MAGIC = 0x5458474C       // "TXGL"
    private const val ROLE_CHANGE_MAGIC = 0x54584743 // "TXGC"
    private const val NAME_CHANGE_MAGIC = 0x5458474E // "TXGN"
    private const val AVATAR_CHANGE_MAGIC = 0x54584741 // "TXGA"

    fun encodeInvite(payload: GroupInvitePayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(INVITE_MAGIC)
        dos.writeUTF(payload.groupId)
        dos.writeUTF(payload.title)
        dos.writeUTF(payload.avatarHash ?: "")
        dos.writeUTF(payload.creatorIdentity)
        dos.writeLong(payload.epoch)
        dos.writeUTF(payload.inviterIdentity)
        dos.writeInt(payload.members.size)
        payload.members.forEach { m ->
            dos.writeUTF(m.identityId)
            dos.writeUTF(m.contactId)
            dos.writeUTF(m.role.name)
            dos.writeUTF(m.state.name)
        }
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeInvite(bytes: ByteArray): GroupInvitePayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == INVITE_MAGIC) { "Invalid GroupInvite magic header" }

        val groupId = dis.readUTF()
        val title = dis.readUTF()
        val avatarRaw = dis.readUTF()
        val avatarHash = if (avatarRaw.isEmpty()) null else avatarRaw
        val creatorIdentity = dis.readUTF()
        val epoch = dis.readLong()
        val inviterIdentity = dis.readUTF()
        val memberCount = dis.readInt()
        val membersList = mutableListOf<GroupMemberSnapshot>()
        for (i in 0 until memberCount) {
            val identityId = dis.readUTF()
            val contactId = dis.readUTF()
            val role = GroupMemberRole.fromString(dis.readUTF())
            val state = GroupMemberState.fromString(dis.readUTF())
            membersList.add(GroupMemberSnapshot(identityId, contactId, role, state))
        }
        return GroupInvitePayload(
            groupId = groupId,
            title = title,
            avatarHash = avatarHash,
            creatorIdentity = creatorIdentity,
            epoch = epoch,
            inviterIdentity = inviterIdentity,
            members = membersList
        )
    }

    fun encodeJoined(payload: GroupMemberJoinedPayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(JOINED_MAGIC)
        dos.writeUTF(payload.groupId)
        dos.writeUTF(payload.memberIdentity)
        dos.writeUTF(payload.role.name)
        dos.writeLong(payload.epoch)
        dos.writeUTF(payload.actorIdentity)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeJoined(bytes: ByteArray): GroupMemberJoinedPayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == JOINED_MAGIC) { "Invalid GroupMemberJoined magic header" }
        return GroupMemberJoinedPayload(
            groupId = dis.readUTF(),
            memberIdentity = dis.readUTF(),
            role = GroupMemberRole.fromString(dis.readUTF()),
            epoch = dis.readLong(),
            actorIdentity = dis.readUTF()
        )
    }

    fun encodeRemove(payload: GroupMemberRemovePayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(REMOVE_MAGIC)
        dos.writeUTF(payload.groupId)
        dos.writeUTF(payload.targetIdentity)
        dos.writeUTF(payload.actorIdentity)
        dos.writeLong(payload.previousEpoch)
        dos.writeLong(payload.newEpoch)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeRemove(bytes: ByteArray): GroupMemberRemovePayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == REMOVE_MAGIC) { "Invalid GroupMemberRemove magic header" }
        return GroupMemberRemovePayload(
            groupId = dis.readUTF(),
            targetIdentity = dis.readUTF(),
            actorIdentity = dis.readUTF(),
            previousEpoch = dis.readLong(),
            newEpoch = dis.readLong()
        )
    }

    fun encodeLeave(payload: GroupMemberLeavePayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(LEAVE_MAGIC)
        dos.writeUTF(payload.groupId)
        dos.writeUTF(payload.memberIdentity)
        dos.writeLong(payload.previousEpoch)
        dos.writeLong(payload.newEpoch)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeLeave(bytes: ByteArray): GroupMemberLeavePayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == LEAVE_MAGIC) { "Invalid GroupMemberLeave magic header" }
        return GroupMemberLeavePayload(
            groupId = dis.readUTF(),
            memberIdentity = dis.readUTF(),
            previousEpoch = dis.readLong(),
            newEpoch = dis.readLong()
        )
    }

    fun encodeRoleChange(payload: GroupRoleChangePayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(ROLE_CHANGE_MAGIC)
        dos.writeUTF(payload.groupId)
        dos.writeUTF(payload.targetIdentity)
        dos.writeUTF(payload.newRole.name)
        dos.writeUTF(payload.actorIdentity)
        dos.writeLong(payload.previousEpoch)
        dos.writeLong(payload.newEpoch)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeRoleChange(bytes: ByteArray): GroupRoleChangePayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == ROLE_CHANGE_MAGIC) { "Invalid GroupRoleChange magic header" }
        return GroupRoleChangePayload(
            groupId = dis.readUTF(),
            targetIdentity = dis.readUTF(),
            newRole = GroupMemberRole.fromString(dis.readUTF()),
            actorIdentity = dis.readUTF(),
            previousEpoch = dis.readLong(),
            newEpoch = dis.readLong()
        )
    }

    fun encodeNameChange(payload: GroupNameChangePayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(NAME_CHANGE_MAGIC)
        dos.writeUTF(payload.groupId)
        dos.writeUTF(payload.newTitle)
        dos.writeUTF(payload.actorIdentity)
        dos.writeLong(payload.previousEpoch)
        dos.writeLong(payload.newEpoch)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeNameChange(bytes: ByteArray): GroupNameChangePayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == NAME_CHANGE_MAGIC) { "Invalid GroupNameChange magic header" }
        return GroupNameChangePayload(
            groupId = dis.readUTF(),
            newTitle = dis.readUTF(),
            actorIdentity = dis.readUTF(),
            previousEpoch = dis.readLong(),
            newEpoch = dis.readLong()
        )
    }

    fun encodeAvatarChange(payload: GroupAvatarChangePayload): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(AVATAR_CHANGE_MAGIC)
        dos.writeUTF(payload.groupId)
        dos.writeUTF(payload.newAvatarHash ?: "")
        dos.writeUTF(payload.actorIdentity)
        dos.writeLong(payload.previousEpoch)
        dos.writeLong(payload.newEpoch)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeAvatarChange(bytes: ByteArray): GroupAvatarChangePayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == AVATAR_CHANGE_MAGIC) { "Invalid GroupAvatarChange magic header" }
        val groupId = dis.readUTF()
        val avatarRaw = dis.readUTF()
        val avatarHash = if (avatarRaw.isEmpty()) null else avatarRaw
        val actorIdentity = dis.readUTF()
        val previousEpoch = dis.readLong()
        val newEpoch = dis.readLong()
        return GroupAvatarChangePayload(
            groupId = groupId,
            newAvatarHash = avatarHash,
            actorIdentity = actorIdentity,
            previousEpoch = previousEpoch,
            newEpoch = newEpoch
        )
    }
}
