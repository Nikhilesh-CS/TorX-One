package com.torxone.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Ciphertext retained until its authenticated group-key version arrives. */
@Entity(
    tableName = "pending_group_ciphertexts",
    primaryKeys = ["groupId", "senderKey", "outerMessageId"]
)
data class PendingGroupCiphertextEntity(
    val groupId: String,
    val senderKey: String,
    val outerMessageId: String,
    val keyVersion: Int,
    val rawJson: String,
    val senderOnion: String?,
    val receivedAt: Long,
    val expiresAt: Long
)

@Dao
interface PendingGroupCiphertextDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entity: PendingGroupCiphertextEntity): Long

    @Query("SELECT * FROM pending_group_ciphertexts WHERE groupId = :groupId AND keyVersion = :keyVersion AND expiresAt > :now ORDER BY receivedAt")
    fun getForKey(groupId: String, keyVersion: Int, now: Long = System.currentTimeMillis()): List<PendingGroupCiphertextEntity>

    @Query("DELETE FROM pending_group_ciphertexts WHERE groupId = :groupId AND senderKey = :senderKey AND outerMessageId = :outerMessageId")
    fun delete(groupId: String, senderKey: String, outerMessageId: String)

    @Query("DELETE FROM pending_group_ciphertexts WHERE expiresAt <= :now")
    fun deleteExpired(now: Long = System.currentTimeMillis())
}
