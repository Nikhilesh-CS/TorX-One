/**
 * Core database layer for TorX One.
 * Handles storage of contacts, messages, profiles, and media metadata.
 */
package com.torxone.app.data

import kotlinx.coroutines.flow.Flow

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import java.util.UUID

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
    val replyToSender: String? = null,
    val replyToType: String? = null,
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


@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts")
    fun getAllContactsSync(): List<ContactEntity>

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

    @Query("UPDATE contacts SET endpointId = '', isConnected = 0 WHERE endpointId = :endpointId")
    fun clearEndpoint(endpointId: String)

    @Query("DELETE FROM contacts WHERE signingPublicKey = :key")
    fun deleteContact(key: String)
}

@Dao
interface MessageDao {
    @Transaction
    @Query("SELECT * FROM messages WHERE id IN (SELECT id FROM messages WHERE contactKey = :contactKey ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun getMessagesForContact(contactKey: String, limit: Int = 100): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE contactKey = :contactKey ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessageForContact(contactKey: String): Flow<MessageEntity?>

    @Query("SELECT COUNT(*) FROM messages WHERE contactKey = :contactKey AND direction = 'received' AND status != 'read'")
    fun getUnreadCountForContact(contactKey: String): Flow<Int>

    @Transaction
    @Query("SELECT * FROM messages WHERE contactKey = :contactKey AND direction = 'received' AND status != 'read' ORDER BY timestamp ASC")
    fun getUnreadMessagesSync(contactKey: String): List<MessageEntity>

    @Transaction
    @Query("SELECT * FROM messages WHERE contactKey = :contactKey ORDER BY timestamp ASC")
    fun getMessagesForContactSync(contactKey: String): List<MessageEntity>

    @Transaction
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

    @Query("""
        UPDATE messages
        SET status = CASE
            WHEN status = 'read' THEN status
            WHEN :status = 'read' THEN 'read'
            WHEN status = 'delivered' AND :status IN ('pending', 'sent') THEN status
            WHEN status = 'sent' AND :status = 'pending' THEN status
            ELSE :status
        END,
        transport = COALESCE(:transport, transport)
        WHERE messageId = :messageId AND contactKey = :contactKey AND direction = 'sent'
    """)
    fun updateSentMessageStatus(messageId: String, contactKey: String, status: String, transport: String? = null): Int

    @Query("UPDATE messages SET status = 'read' WHERE contactKey = :contactKey AND direction = 'received' AND status != 'read'")
    fun markMessagesAsRead(contactKey: String)

    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE messageId = :messageId")
    fun updateReactions(messageId: String, reactionsJson: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE direction = 'sent' AND status = 'pending' AND retryCount < 40")
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
interface ReactionOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertReaction(reaction: ReactionOutboxEntity)

    @Query("SELECT * FROM reaction_outbox WHERE retryCount < 20 ORDER BY createdAt ASC")
    fun getPendingReactions(): List<ReactionOutboxEntity>

    @Query("DELETE FROM reaction_outbox WHERE reactionId = :reactionId")
    fun deleteReaction(reactionId: String)

    @Query("UPDATE reaction_outbox SET retryCount = retryCount + 1 WHERE reactionId = :reactionId")
    fun incrementRetry(reactionId: String)
}

@Entity(tableName = "reaction_outbox")
data class ReactionOutboxEntity(
    @PrimaryKey val reactionId: String,
    val contactKey: String,
    val targetMessageId: String,
    val emoji: String,
    val action: String,
    val createdAt: Long,
    val retryCount: Int = 0
)

@Entity(tableName = "music_notes")
data class MusicNoteEntity(
    @PrimaryKey val noteId: String = UUID.randomUUID().toString(),
    val authorId: String,
    val authorName: String,
    val authorPublicKey: String,
    val signature: String,
    val text: String,
    val trackId: String,
    val trackName: String,
    val artist: String,
    val album: String,
    val albumArtUri: String?,
    val provider: String,
    val playbackPositionMs: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val visibility: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface MusicNoteDao {
    @Query("SELECT * FROM music_notes WHERE expiresAt > :now ORDER BY createdAt DESC")
    fun observeActiveNotes(now: Long = System.currentTimeMillis()): Flow<List<MusicNoteEntity>>

    @Query("SELECT * FROM music_notes WHERE noteId = :noteId LIMIT 1")
    fun getNote(noteId: String): MusicNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertNote(note: MusicNoteEntity)

    @Query("DELETE FROM music_notes WHERE noteId = :noteId")
    fun deleteNote(noteId: String)

    @Query("DELETE FROM music_notes WHERE authorPublicKey = :authorPublicKey")
    fun deleteNotesByAuthor(authorPublicKey: String)

    @Query("DELETE FROM music_notes WHERE expiresAt <= :now")
    fun deleteExpired(now: Long = System.currentTimeMillis())
}

@Database(
    entities = [ContactEntity::class, MessageEntity::class, ConnectionRequestEntity::class, ReactionOutboxEntity::class, MediaTransferEntity::class, ProfileEntity::class, MusicNoteEntity::class],
    version = 12,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun connectionRequestDao(): ConnectionRequestDao
    abstract fun reactionOutboxDao(): ReactionOutboxDao
    abstract fun mediaTransferDao(): MediaTransferDao
    abstract fun profileDao(): ProfileDao
    abstract fun musicNoteDao(): MusicNoteDao

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

                val profileColumns = mutableSetOf<String>()
                db.query("PRAGMA table_info(`profiles`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        profileColumns.add(cursor.getString(nameIndex))
                    }
                }

                if (!profileColumns.contains("ownerKey")) {
                    db.execSQL("ALTER TABLE profiles RENAME TO profiles_legacy")
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

                    db.execSQL("""
                        INSERT OR REPLACE INTO profiles (
                            ownerKey, name, bio, statusMessage, avatarHash, profileHash,
                            profileVersion, lastUpdatedAt, avatarLocalPath, nickname, pronouns,
                            verifiedBadge, avatarTheme, customStatusEmoji, presenceCapabilities
                        )
                        SELECT
                            COALESCE(contactKey, 'LOCAL_USER'),
                            name,
                            COALESCE(bio, ''),
                            '',
                            NULL,
                            '',
                            1,
                            COALESCE(timestamp, 0),
                            NULL,
                            NULL,
                            NULL,
                            0,
                            NULL,
                            NULL,
                            NULL
                        FROM profiles_legacy
                    """.trimIndent())
                    db.execSQL("DROP TABLE profiles_legacy")
                }
            }
        }

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToSender TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToType TEXT")
            }
        }

        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `reaction_outbox` (
                        `reactionId` TEXT NOT NULL,
                        `contactKey` TEXT NOT NULL,
                        `targetMessageId` TEXT NOT NULL,
                        `emoji` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`reactionId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `music_notes` (
                        `noteId` TEXT NOT NULL,
                        `authorId` TEXT NOT NULL,
                        `authorName` TEXT NOT NULL,
                        `authorPublicKey` TEXT NOT NULL,
                        `signature` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `trackName` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `albumArtUri` TEXT,
                        `provider` TEXT NOT NULL,
                        `playbackPositionMs` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `visibility` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`noteId`)
                    )
                """.trimIndent())
            }
        }
    }
}
