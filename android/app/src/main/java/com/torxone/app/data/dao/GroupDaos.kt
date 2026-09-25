package com.torxone.app.data.dao

import androidx.room.*
import com.torxone.app.data.entity.GroupEntity
import com.torxone.app.data.entity.GroupMemberEntity
import com.torxone.app.data.entity.GroupMessageDeliveryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups WHERE group_id = :groupId")
    fun observeById(groupId: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE group_id = :groupId")
    suspend fun getById(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE conversation_id = :conversationId")
    suspend fun getByConversationId(conversationId: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: GroupEntity)

    @Query("UPDATE groups SET epoch = :epoch, updated_at = :updatedAt WHERE group_id = :groupId")
    suspend fun updateEpoch(groupId: String, epoch: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE groups SET title = :title, updated_at = :updatedAt WHERE group_id = :groupId")
    suspend fun updateTitle(groupId: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE groups SET avatar_hash = :avatarHash, updated_at = :updatedAt WHERE group_id = :groupId")
    suspend fun updateAvatar(groupId: String, avatarHash: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM groups WHERE group_id = :groupId")
    suspend fun deleteById(groupId: String)
}

@Dao
interface GroupMemberDao {
    @Query("SELECT * FROM group_members WHERE group_id = :groupId ORDER BY joined_at ASC")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE group_id = :groupId ORDER BY joined_at ASC")
    suspend fun getMembers(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE group_id = :groupId AND state = 'ACTIVE' ORDER BY joined_at ASC")
    suspend fun getActiveMembers(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE group_id = :groupId AND state = 'ACTIVE' ORDER BY joined_at ASC")
    fun observeActiveMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE group_id = :groupId AND member_identity_id = :memberIdentityId")
    suspend fun getMember(groupId: String, memberIdentityId: String): GroupMemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(member: GroupMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<GroupMemberEntity>)

    @Query("UPDATE group_members SET role = :role WHERE group_id = :groupId AND member_identity_id = :memberIdentityId")
    suspend fun updateRole(groupId: String, memberIdentityId: String, role: String)

    @Query("""
        UPDATE group_members 
        SET state = :state, 
            removed_epoch = :removedEpoch, 
            removed_at = :removedAt 
        WHERE group_id = :groupId AND member_identity_id = :memberIdentityId
    """)
    suspend fun updateState(
        groupId: String,
        memberIdentityId: String,
        state: String,
        removedEpoch: Long?,
        removedAt: Long?
    )

    @Query("DELETE FROM group_members WHERE group_id = :groupId AND member_identity_id = :memberIdentityId")
    suspend fun deleteMember(groupId: String, memberIdentityId: String)

    @Query("DELETE FROM group_members WHERE group_id = :groupId")
    suspend fun deleteMembersForGroup(groupId: String)
}

@Dao
interface GroupMessageDeliveryDao {
    @Query("SELECT * FROM group_message_deliveries WHERE logical_message_id = :logicalMessageId")
    fun observeDeliveriesForMessage(logicalMessageId: String): Flow<List<GroupMessageDeliveryEntity>>

    @Query("SELECT * FROM group_message_deliveries WHERE logical_message_id = :logicalMessageId")
    suspend fun getDeliveriesForMessage(logicalMessageId: String): List<GroupMessageDeliveryEntity>

    @Query("SELECT * FROM group_message_deliveries WHERE status IN ('PENDING', 'QUEUED') ORDER BY created_at ASC")
    suspend fun getPendingDeliveries(): List<GroupMessageDeliveryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(delivery: GroupMessageDeliveryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(deliveries: List<GroupMessageDeliveryEntity>)

    @Query("""
        UPDATE group_message_deliveries 
        SET status = :status, 
            updated_at = :updatedAt 
        WHERE delivery_id = :deliveryId
    """)
    suspend fun updateStatus(
        deliveryId: String,
        status: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE group_message_deliveries 
        SET status = 'DELIVERED', 
            delivered_at = :deliveredAt, 
            updated_at = :updatedAt 
        WHERE logical_message_id = :logicalMessageId AND recipient_identity_id = :recipientIdentityId
    """)
    suspend fun markDelivered(
        logicalMessageId: String,
        recipientIdentityId: String,
        deliveredAt: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE group_message_deliveries 
        SET status = 'READ', 
            read_at = :readAt, 
            updated_at = :updatedAt 
        WHERE logical_message_id = :logicalMessageId AND recipient_identity_id = :recipientIdentityId
    """)
    suspend fun markRead(
        logicalMessageId: String,
        recipientIdentityId: String,
        readAt: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM group_message_deliveries WHERE logical_message_id = :logicalMessageId")
    suspend fun deleteForMessage(logicalMessageId: String)
}
