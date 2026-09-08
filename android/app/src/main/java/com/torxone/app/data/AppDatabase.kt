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
    val conversationType: String = "direct", // "direct" or "group"
    val senderKey: String? = null,
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

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val avatarUri: String? = null,
    val creatorKey: String,
    val createdAt: Long,
    val myRole: String,
    val muteUntil: Long = 0L,
    val description: String? = null,
    val updatedAt: Long = 0L,
    val membershipState: String = "MEMBER",
    val currentKeyVersion: Int = 1,
    val whoCanSend: String = "MEMBERS",
    val whoCanEditInfo: String = "ADMINS",
    val whoCanAddMembers: String = "ADMINS",
    val approvalRequired: Boolean = false,
    val maxMembers: Int = 1000,
    val avatarVersion: Int = 0,
    val metadataVersion: Long = 0L,
    val disappearingDuration: Long = 0L
)

@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "memberKey"],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ]
)
data class GroupMemberEntity(
    val groupId: String,
    val memberKey: String,
    val role: String,
    val joinedAt: Long,
    val membershipState: String = "MEMBER",
    val memberTag: String? = null
)

@Entity(
    tableName = "group_keys",
    primaryKeys = ["groupId", "keyVersion"],
    foreignKeys = [androidx.room.ForeignKey(
        entity = GroupEntity::class,
        parentColumns = ["groupId"],
        childColumns = ["groupId"],
        onDelete = androidx.room.ForeignKey.CASCADE
    )]
)
data class GroupKeyEntity(
    val groupId: String,
    val keyVersion: Int,
    val aesKeyBase64: String,
    val distributedAt: Long
)

@Entity(
    tableName = "group_events",
    indices = [androidx.room.Index(value = ["groupId", "groupVersion"]), androidx.room.Index(value = ["groupId", "createdAt"])]
)
data class GroupEventEntity(
    @PrimaryKey val eventId: String,
    val groupId: String,
    val eventType: String,
    val actorKey: String,
    val targetKey: String? = null,
    val groupVersion: Long,
    val keyVersion: Int,
    val createdAt: Long,
    val payload: String,
    val signature: String
)

@Entity(
    tableName = "processed_group_events",
    primaryKeys = ["groupId", "eventId"],
    indices = [androidx.room.Index(value = ["receivedAt"])]
)
data class ProcessedGroupEventEntity(
    val groupId: String,
    val eventId: String,
    val senderKey: String,
    val eventType: String,
    val receivedAt: Long
)

@Entity(tableName = "pending_group_events", primaryKeys = ["eventId", "recipientKey"], indices = [androidx.room.Index(value = ["nextRetryAt"]), androidx.room.Index(value = ["groupId"])])
data class PendingGroupEventEntity(
    val eventId: String,
    val groupId: String,
    val recipientKey: String,
    val payload: String,
    val eventType: String,
    val retryCount: Int = 0,
    val createdAt: Long,
    val nextRetryAt: Long,
    val expiresAt: Long
)

@Entity(tableName = "group_sync_state")
data class GroupSyncStateEntity(
    @PrimaryKey val groupId: String,
    val lastKnownGroupVersion: Long = 0L,
    val lastKnownEventId: String? = null,
    val lastSyncAt: Long = 0L
)

@Entity(tableName = "group_invites")
data class GroupInviteEntity(
    @PrimaryKey val inviteId: String,
    val groupId: String,
    val inviterKey: String,
    val expiresAt: Long,
    val maxUses: Int = 1,
    val uses: Int = 0,
    val revoked: Boolean = false,
    val signature: String
)

@Dao
interface GroupEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertEvent(event: GroupEventEntity): Long

    @Query("SELECT * FROM group_events WHERE groupId = :groupId ORDER BY groupVersion ASC")
    fun getEvents(groupId: String): List<GroupEventEntity>

    @Query("SELECT COALESCE(MAX(groupVersion), 0) FROM group_events WHERE groupId = :groupId")
    fun getLatestVersion(groupId: String): Long
    @Query("SELECT * FROM group_events WHERE groupId = :groupId AND eventType = 'KEY_ROTATED' AND keyVersion = :keyVersion ORDER BY groupVersion DESC LIMIT 1")
    fun getKeyRotationEvent(groupId: String, keyVersion: Int): GroupEventEntity?
}

@Dao
interface ProcessedGroupEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun markProcessed(event: ProcessedGroupEventEntity): Long

    @Query("SELECT COUNT(*) FROM processed_group_events WHERE groupId = :groupId AND eventId = :eventId")
    fun wasProcessed(groupId: String, eventId: String): Int
}

@Dao
interface GroupSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertPending(event: PendingGroupEventEntity)
    @Query("SELECT * FROM pending_group_events WHERE nextRetryAt <= :now AND expiresAt > :now ORDER BY nextRetryAt LIMIT :limit")
    fun duePending(now: Long, limit: Int = 50): List<PendingGroupEventEntity>
    @Query("DELETE FROM pending_group_events WHERE eventId = :eventId")
    fun deletePending(eventId: String)
    @Query("DELETE FROM pending_group_events WHERE eventId = :eventId AND recipientKey = :recipientKey")
    fun deletePendingForRecipient(eventId: String, recipientKey: String)
    @Query("UPDATE pending_group_events SET retryCount = :retryCount, nextRetryAt = :nextRetryAt WHERE eventId = :eventId AND recipientKey = :recipientKey")
    fun reschedule(eventId: String, recipientKey: String, retryCount: Int, nextRetryAt: Long)
    @Query("SELECT * FROM group_sync_state WHERE groupId = :groupId LIMIT 1")
    fun getState(groupId: String): GroupSyncStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertState(state: GroupSyncStateEntity)
}

@Dao
interface GroupInviteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertInvite(invite: GroupInviteEntity)
    @Query("SELECT * FROM group_invites WHERE inviteId = :inviteId LIMIT 1") fun getInvite(inviteId: String): GroupInviteEntity?
    @Query("UPDATE group_invites SET revoked = 1 WHERE inviteId = :inviteId") fun revoke(inviteId: String)
    @Query("UPDATE group_invites SET uses = uses + 1 WHERE inviteId = :inviteId") fun incrementUses(inviteId: String)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun getAllGroups(): Flow<List<GroupEntity>>
    @Query("SELECT * FROM groups") fun getAllGroupsSync(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    fun getGroup(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    fun getGroupFlow(groupId: String): Flow<GroupEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertGroup(group: GroupEntity)

    @Query("DELETE FROM groups WHERE groupId = :groupId")
    fun deleteGroup(groupId: String)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun getGroupMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun getGroupMembersSync(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND memberKey = :memberKey LIMIT 1")
    fun getGroupMember(groupId: String, memberKey: String): GroupMemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertGroupMember(member: GroupMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertGroupMembers(members: List<GroupMemberEntity>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND memberKey = :memberKey")
    fun deleteGroupMember(groupId: String, memberKey: String)
}

@Dao
interface GroupKeyDao {
    @Query("SELECT * FROM group_keys WHERE groupId = :groupId ORDER BY keyVersion DESC LIMIT 1")
    fun getLatestKey(groupId: String): GroupKeyEntity?

    @Query("SELECT * FROM group_keys WHERE groupId = :groupId AND keyVersion = :keyVersion LIMIT 1")
    fun getKey(groupId: String, keyVersion: Int): GroupKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertKey(key: GroupKeyEntity)
}

@Entity(tableName = "connection_requests")
data class ConnectionRequestEntity(
    @PrimaryKey val endpointId: String,
    val name: String,
    val timestamp: Long,
    val status: String = "pending" // pending, accepted, rejected
)


@Dao
interface ContactDao {
    @Query("""
        SELECT c.* FROM contacts c
        LEFT JOIN (
            SELECT contactKey, MAX(timestamp) AS last_msg_time
            FROM messages
            GROUP BY contactKey
        ) m ON c.signingPublicKey = m.contactKey
        ORDER BY COALESCE(m.last_msg_time, 0) DESC, c.name ASC
    """)
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("""
        SELECT c.* FROM contacts c
        LEFT JOIN (
            SELECT contactKey, MAX(timestamp) AS last_msg_time
            FROM messages
            GROUP BY contactKey
        ) m ON c.signingPublicKey = m.contactKey
        ORDER BY COALESCE(m.last_msg_time, 0) DESC, c.name ASC
    """)
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
    @Query("SELECT * FROM messages WHERE id IN (SELECT id FROM messages WHERE contactKey = :contactKey AND conversationType = :conversationType ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun getMessagesForConversation(contactKey: String, conversationType: String = "direct", limit: Int = 100): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE contactKey = :contactKey AND conversationType = :conversationType ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessageForConversation(contactKey: String, conversationType: String = "direct"): Flow<MessageEntity?>

    @Query("SELECT COUNT(*) FROM messages WHERE contactKey = :contactKey AND conversationType = :conversationType AND direction = 'received' AND status != 'read'")
    fun getUnreadCountForConversation(contactKey: String, conversationType: String = "direct"): Flow<Int>

    @Transaction
    @Query("SELECT * FROM messages WHERE contactKey = :contactKey AND conversationType = :conversationType AND direction = 'received' AND status != 'read' ORDER BY timestamp ASC")
    fun getUnreadMessagesSync(contactKey: String, conversationType: String = "direct"): List<MessageEntity>

    @Transaction
    @Query("SELECT * FROM messages WHERE contactKey = :contactKey AND conversationType = :conversationType ORDER BY timestamp ASC")
    fun getMessagesForConversationSync(contactKey: String, conversationType: String = "direct"): List<MessageEntity>

    @Transaction
    @Query("SELECT * FROM messages WHERE contactKey = :contactKey AND conversationType = :conversationType AND text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMessages(contactKey: String, conversationType: String = "direct", query: String): Flow<List<MessageEntity>>

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

    @Query("UPDATE messages SET status = 'read' WHERE contactKey = :contactKey AND conversationType = :conversationType AND direction = 'received' AND status != 'read'")
    fun markMessagesAsRead(contactKey: String, conversationType: String = "direct")

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

    @Query("UPDATE messages SET text = :text, status = 'sent' WHERE messageId = :messageId")
    fun updateMessageText(messageId: String, text: String)

    @Query("UPDATE messages SET status = 'read' WHERE messageId = :messageId")
    fun markMessageRead(messageId: String)

    @Query("DELETE FROM messages WHERE conversationType = 'group' AND :now > timestamp + :duration")
    fun deleteExpiredGroupMessages(now: Long, duration: Long)

    @Query("DELETE FROM messages WHERE contactKey = :contactKey AND conversationType = :conversationType")
    fun clearChat(contactKey: String, conversationType: String = "direct")

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
    entities = [ContactEntity::class, MessageEntity::class, ConnectionRequestEntity::class, ReactionOutboxEntity::class, MediaTransferEntity::class, ProfileEntity::class, MusicNoteEntity::class, PendingEncryptedPayload::class, GroupEntity::class, GroupMemberEntity::class, GroupKeyEntity::class, GroupEventEntity::class, ProcessedGroupEventEntity::class, PendingGroupEventEntity::class, GroupSyncStateEntity::class, GroupInviteEntity::class],
    version = 20,
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
    abstract fun pendingEncryptedPayloadDao(): PendingEncryptedPayloadDao
    abstract fun groupDao(): GroupDao
    abstract fun groupKeyDao(): GroupKeyDao
    abstract fun groupEventDao(): GroupEventDao
    abstract fun processedGroupEventDao(): ProcessedGroupEventDao
    abstract fun groupSyncDao(): GroupSyncDao
    abstract fun groupInviteDao(): GroupInviteDao
    abstract fun conversationDao(): ConversationDao

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

        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pending_encrypted_payloads` (
                        `messageId` TEXT NOT NULL,
                        `fromSigningKey` TEXT NOT NULL,
                        `rawJson` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`messageId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN conversationType TEXT NOT NULL DEFAULT 'direct'")
                db.execSQL("ALTER TABLE messages ADD COLUMN senderKey TEXT")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `groups` (
                        `groupId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `avatarUri` TEXT,
                        `creatorKey` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `myRole` TEXT NOT NULL,
                        PRIMARY KEY(`groupId`)
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `group_members` (
                        `groupId` TEXT NOT NULL,
                        `memberKey` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `joinedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`, `memberKey`),
                        FOREIGN KEY(`groupId`) REFERENCES `groups`(`groupId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE groups ADD COLUMN muteUntil INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `group_keys` (
                        `groupId` TEXT NOT NULL,
                        `keyVersion` INTEGER NOT NULL,
                        `aesKeyBase64` TEXT NOT NULL,
                        `distributedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`, `keyVersion`),
                        FOREIGN KEY(`groupId`) REFERENCES `groups`(`groupId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE groups ADD COLUMN description TEXT")
                db.execSQL("ALTER TABLE groups ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE groups ADD COLUMN membershipState TEXT NOT NULL DEFAULT 'MEMBER'")
                db.execSQL("ALTER TABLE groups ADD COLUMN currentKeyVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE groups ADD COLUMN whoCanSend TEXT NOT NULL DEFAULT 'MEMBERS'")
                db.execSQL("ALTER TABLE groups ADD COLUMN whoCanEditInfo TEXT NOT NULL DEFAULT 'ADMINS'")
                db.execSQL("ALTER TABLE groups ADD COLUMN whoCanAddMembers TEXT NOT NULL DEFAULT 'ADMINS'")
                db.execSQL("ALTER TABLE groups ADD COLUMN approvalRequired INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE groups ADD COLUMN maxMembers INTEGER NOT NULL DEFAULT 1000")
                db.execSQL("ALTER TABLE groups ADD COLUMN avatarVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE groups ADD COLUMN metadataVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE groups ADD COLUMN disappearingDuration INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_members ADD COLUMN membershipState TEXT NOT NULL DEFAULT 'MEMBER'")
                db.execSQL("ALTER TABLE group_members ADD COLUMN memberTag TEXT")
                db.execSQL("UPDATE group_members SET membershipState = 'INVITED' WHERE role = 'invited'")
                db.execSQL("UPDATE group_members SET membershipState = 'MEMBER' WHERE role IN ('admin','member')")
            }
        }

        val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `group_events` (`eventId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `eventType` TEXT NOT NULL, `actorKey` TEXT NOT NULL, `targetKey` TEXT, `groupVersion` INTEGER NOT NULL, `keyVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `payload` TEXT NOT NULL, `signature` TEXT NOT NULL, PRIMARY KEY(`eventId`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_events_groupId_groupVersion` ON `group_events` (`groupId`, `groupVersion`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_events_groupId_createdAt` ON `group_events` (`groupId`, `createdAt`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `processed_group_events` (`groupId` TEXT NOT NULL, `eventId` TEXT NOT NULL, `senderKey` TEXT NOT NULL, `eventType` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `eventId`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_processed_group_events_receivedAt` ON `processed_group_events` (`receivedAt`)")
            }
        }

        val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `pending_group_events` (`eventId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `recipientKey` TEXT NOT NULL, `payload` TEXT NOT NULL, `eventType` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `nextRetryAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, PRIMARY KEY(`eventId`, `recipientKey`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_group_events_nextRetryAt` ON `pending_group_events` (`nextRetryAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_group_events_groupId` ON `pending_group_events` (`groupId`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `group_sync_state` (`groupId` TEXT NOT NULL, `lastKnownGroupVersion` INTEGER NOT NULL, `lastKnownEventId` TEXT, `lastSyncAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`))""")
            }
        }

        val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `group_invites` (`inviteId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `inviterKey` TEXT NOT NULL, `expiresAt` INTEGER NOT NULL, `maxUses` INTEGER NOT NULL, `uses` INTEGER NOT NULL, `revoked` INTEGER NOT NULL, `signature` TEXT NOT NULL, PRIMARY KEY(`inviteId`))""")
            }
        }
    }
}
