package com.torxone.app.group

import com.torxone.app.data.GroupEntity
import com.torxone.app.data.GroupMemberEntity

/** Central policy shared by UI-facing operations and protocol validation. */
object GroupPermission {
    private fun active(member: GroupMemberEntity?) = member != null &&
        member.membershipState == "MEMBER" && member.role != "invited"

    fun canSendMessages(group: GroupEntity, member: GroupMemberEntity?) =
        active(member) && (group.whoCanSend == "MEMBERS" || member?.role == "admin" || member?.role == "owner")

    fun canAddMembers(group: GroupEntity, member: GroupMemberEntity?) =
        active(member) && (group.whoCanAddMembers == "MEMBERS" || member?.role == "admin" || member?.role == "owner")

    fun canEditInfo(group: GroupEntity, member: GroupMemberEntity?) =
        active(member) && (group.whoCanEditInfo == "MEMBERS" || member?.role == "admin" || member?.role == "owner")

    fun canRemoveMembers(member: GroupMemberEntity?) =
        active(member) && (member?.role == "admin" || member?.role == "owner")

    fun canManageAdmins(member: GroupMemberEntity?) =
        active(member) && member?.role == "owner"
}
