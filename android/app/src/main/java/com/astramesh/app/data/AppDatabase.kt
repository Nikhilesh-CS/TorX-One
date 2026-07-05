package com.astramesh.app.data

import kotlinx.coroutines.flow.Flow

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val signingPublicKey: String,
    val encryptionPublicKey: String,
    val name: String,
    val endpointId: String = "",
    val onionAddress: String = "",
    val isConnected: Boolean = false,
    val muteUntil: Long = 0L
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val messageId: String,
    val contactKey: String,
    val text: String,
    val timestamp: Long,
    val direction: String,
    val status: String = "pending", // pending, sent, delivered, failed
    val replyToId: String? = null,
    val replyToText: String? = null,
    val retryCount: Int = 0,
    val transport: String? = null,
    
    // Media & File Sharing Fields
    val messageType: String = "TEXT", // TEXT, IMAGE, VIDEO, AUDIO, VOICE, DOCUMENT, APK
    val fileName: String? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null,
    val localUri: String? = null,
    val thumbnailUri: String? = null,
    val transferProgress: Int? = null,
    val checksum: String? = null,
    val transferStatus: String? = null
)

@Entity(tableName = "connection_requests")
data class ConnectionRequestEntity(
    @PrimaryKey val endpointId: String,
    val name: String,
    val timestamp: Long,
    val status: String = "pending" // pending, accepted, rejected
)

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE signingPublicKey = :signingPublicKey LIMIT 1")
    fun getContact(signingPublicKey: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE endpointId = :endpointId LIMIT 1")
    fun getContactByEndpoint(endpointId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertContact(contact: ContactEntity)

    @Query("UPDATE contacts SET isConnected = :connected WHERE endpointId = :endpointId")
    fun updateConnectionStatus(endpointId: String, connected: Boolean)

    @Query("UPDATE contacts SET isConnected = :connected WHERE signingPublicKey = :key")
    fun updateConnectionStatusByKey(key: String, connected: Boolean)

    @Query("UPDATE contacts SET endpointId = :endpointId WHERE signingPublicKey = :key")
    fun updateEndpointId(key: String, endpointId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE contactKey = :contactKey ORDER BY timestamp ASC")
    fun getMessagesForContact(contactKey: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE contactKey = :contactKey ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessageForContact(contactKey: String): Flow<MessageEntity?>

    @Query("SELECT COUNT(*) FROM messages WHERE contactKey = :contactKey AND direction = 'received' AND status != 'read'")
    fun getUnreadCountForContact(contactKey: String): Flow<Int>

    @Query("SELECT * FROM messages WHERE contactKey = :contactKey AND direction = 'received' AND status != 'read' ORDER BY timestamp ASC")
    fun getUnreadMessagesSync(contactKey: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE contactKey = :contactKey AND text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMessages(contactKey: String, query: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    fun getMessageById(messageId: String): MessageEntity?

    @Query("UPDATE messages SET status = :status, transport = :transport WHERE messageId = :messageId")
    fun updateMessageStatus(messageId: String, status: String, transport: String? = null)

    @Query("UPDATE messages SET transferProgress = :progress WHERE messageId = :messageId")
    fun updateTransferProgress(messageId: String, progress: Int)

    @Query("UPDATE messages SET status = :status, localUri = :localUri, transferProgress = 100 WHERE messageId = :messageId")
    fun markMediaDelivered(messageId: String, status: String, localUri: String)

    @Query("UPDATE messages SET status = :status, transport = :transport WHERE messageId = :messageId AND contactKey = :contactKey AND direction = 'sent'")
    fun updateSentMessageStatus(messageId: String, contactKey: String, status: String, transport: String? = null): Int

    @Query("UPDATE messages SET status = 'read' WHERE contactKey = :contactKey AND direction = 'received' AND status != 'read'")
    fun markMessagesAsRead(contactKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE direction = 'sent' AND status = 'pending' AND retryCount < 5")
    fun getPendingMessages(): List<MessageEntity>

    @Query("UPDATE messages SET retryCount = retryCount + 1 WHERE messageId = :messageId")
    fun incrementRetryCount(messageId: String)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE contactKey = :contactKey")
    fun clearChat(contactKey: String)

    @Query("DELETE FROM messages")
    fun deleteAllMessages()

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessagesSync(): List<MessageEntity>
}

@Dao
interface ConnectionRequestDao {
    @Query("SELECT * FROM connection_requests WHERE status = 'pending' ORDER BY timestamp DESC")
    fun getPendingRequests(): Flow<List<ConnectionRequestEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRequest(request: ConnectionRequestEntity)

    @Query("UPDATE connection_requests SET status = :status WHERE endpointId = :endpointId")
    fun updateStatus(endpointId: String, status: String)

    @Query("DELETE FROM connection_requests WHERE endpointId = :endpointId")
    fun deleteRequest(endpointId: String)
}

@Database(
    entities = [ContactEntity::class, MessageEntity::class, ConnectionRequestEntity::class, MediaTransferEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun connectionRequestDao(): ConnectionRequestDao
    abstract fun mediaTransferDao(): MediaTransferDao

    companion object {
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN muteUntil INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add new columns to messages table
                db.execSQL("ALTER TABLE messages ADD COLUMN messageType TEXT NOT NULL DEFAULT 'TEXT'")
                db.execSQL("ALTER TABLE messages ADD COLUMN fileName TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN fileSize INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN mimeType TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN localUri TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN thumbnailUri TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN transferProgress INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN checksum TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN transferStatus TEXT")

                // Create media_transfers table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `media_transfers` (
                        `messageId` TEXT NOT NULL,
                        `contactKey` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `totalChunks` INTEGER NOT NULL,
                        `completedChunks` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `lastUpdatedAt` INTEGER NOT NULL,
                        `transport` TEXT,
                        PRIMARY KEY(`messageId`)
                    )
                """.trimIndent())
            }
        }
    }
}
