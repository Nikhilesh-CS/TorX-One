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
    val transferStatus: String? = null,
    val reactionsJson: String? = null
)

@Entity(tableName = "connection_requests")
data class ConnectionRequestEntity(
    @PrimaryKey val endpointId: String,
    val name: String,
    val timestamp: Long,
    val status: String = "pending" // pending, accepted, rejected
)

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val ownerKey: String, // signingPublicKey of contact, or "LOCAL_USER"
    val name: String,
    val bio: String = "",
    val statusMessage: String = "",
    val avatarHash: String? = null,
    val profileHash: String = "",
    val profileVersion: Int = 1,
    val lastUpdatedAt: Long = 0L,
    val avatarLocalPath: String? = null,
    
    // Future-proofing fields
    val nickname: String? = null,
    val pronouns: String? = null,
    val verifiedBadge: Boolean = false,
    val avatarTheme: String? = null,
    val customStatusEmoji: String? = null,
    val presenceCapabilities: String? = null
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
    @Query("SELECT * FROM (SELECT * FROM messages WHERE contactKey = :contactKey ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun getMessagesForContact(contactKey: String, limit: Int = 100): Flow<List<MessageEntity>>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(messages: List<MessageEntity>)

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

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE ownerKey = :ownerKey LIMIT 1")
    fun getProfile(ownerKey: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE ownerKey = :ownerKey LIMIT 1")
    fun getProfileSync(ownerKey: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertProfile(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE ownerKey = :ownerKey")
    fun deleteProfile(ownerKey: String)
}

@Database(
    entities = [ContactEntity::class, MessageEntity::class, ConnectionRequestEntity::class, MediaTransferEntity::class, ProfileEntity::class],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun connectionRequestDao(): ConnectionRequestDao
    abstract fun mediaTransferDao(): MediaTransferDao
    abstract fun profileDao(): ProfileDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) { override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {} }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) { override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {} }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) { override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {} }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) { override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {} }

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

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `profiles` (
                        `ownerKey` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `bio` TEXT NOT NULL,
                        `statusMessage` TEXT NOT NULL,
                        `avatarHash` TEXT,
                        `profileHash` TEXT NOT NULL,
                        `profileVersion` INTEGER NOT NULL,
                        `lastUpdatedAt` INTEGER NOT NULL,
                        `avatarLocalPath` TEXT,
                        `nickname` TEXT,
                        `pronouns` TEXT,
                        `verifiedBadge` INTEGER NOT NULL,
                        `avatarTheme` TEXT,
                        `customStatusEmoji` TEXT,
                        `presenceCapabilities` TEXT,
                        PRIMARY KEY(`ownerKey`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reactionsJson TEXT")
            }
        }
    }
}
