package com.torxone.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "pending_encrypted_payloads")
data class PendingEncryptedPayload(
    @PrimaryKey val messageId: String,
    val fromSigningKey: String,
    val rawJson: String,
    val receivedAt: Long
)

@Dao
interface PendingEncryptedPayloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(payload: PendingEncryptedPayload)

    @Query("SELECT * FROM pending_encrypted_payloads ORDER BY receivedAt ASC")
    suspend fun getAll(): List<PendingEncryptedPayload>

    @Query("DELETE FROM pending_encrypted_payloads WHERE messageId = :messageId")
    suspend fun delete(messageId: String)
}
