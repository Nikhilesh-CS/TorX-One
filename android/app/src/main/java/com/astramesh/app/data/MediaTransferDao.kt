package com.astramesh.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class TransferStatus {
    PREPARING,
    ENCRYPTING,
    SENDING,
    RECEIVING,
    PAUSED,
    RETRYING,
    COMPLETED,
    FAILED,
    VERIFIED
}

@Entity(tableName = "media_transfers")
data class MediaTransferEntity(
    @PrimaryKey val messageId: String,
    val contactKey: String,
    val direction: String, // "sent" or "received"
    val totalChunks: Int,
    val completedChunks: Int,
    val status: String, // String representation of TransferStatus
    val lastUpdatedAt: Long,
    val transport: String? = null // Transport used (e.g., TOR, BLUETOOTH, WIFI_DIRECT)
)

@Dao
interface MediaTransferDao {
    @Query("SELECT * FROM media_transfers WHERE messageId = :messageId LIMIT 1")
    fun getTransfer(messageId: String): Flow<MediaTransferEntity?>

    @Query("SELECT * FROM media_transfers WHERE messageId = :messageId LIMIT 1")
    fun getTransferSync(messageId: String): MediaTransferEntity?

    @Query("SELECT * FROM media_transfers WHERE status IN ('PREPARING', 'ENCRYPTING', 'SENDING', 'RECEIVING', 'RETRYING')")
    fun getActiveTransfers(): List<MediaTransferEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTransfer(transfer: MediaTransferEntity)

    @Query("UPDATE media_transfers SET completedChunks = :completedChunks, status = :status, lastUpdatedAt = :timestamp WHERE messageId = :messageId")
    fun updateProgress(messageId: String, completedChunks: Int, status: String, timestamp: Long)

    @Query("UPDATE media_transfers SET status = :status, lastUpdatedAt = :timestamp WHERE messageId = :messageId")
    fun updateStatus(messageId: String, status: String, timestamp: Long)

    @Query("DELETE FROM media_transfers WHERE messageId = :messageId")
    fun deleteTransfer(messageId: String)
}
