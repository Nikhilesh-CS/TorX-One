package com.torxone.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import com.torxone.app.calls.CallHistoryEntity
import com.torxone.app.calls.CallHistoryDao

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ContactEntity::class,
        OutboxEntity::class,
        ProcessedEnvelopeEntity::class,
        PairRelationshipEntity::class,
        ConnectionDbEntity::class,
        SessionDbEntity::class,
        SkippedKeyEntity::class,
        PendingInviteEntity::class,
        ReactionEntity::class,
        LocalMessageStateEntity::class,
        MediaEntity::class,
        MediaTransferEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        GroupMessageDeliveryEntity::class,
        CallHistoryEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class TorXDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun reactionDao(): ReactionDao
    abstract fun localMessageStateDao(): LocalMessageStateDao
    abstract fun mediaDao(): MediaDao
    abstract fun mediaTransferDao(): MediaTransferDao
    abstract fun contactDao(): ContactDao
    abstract fun outboxDao(): OutboxDao
    abstract fun processedEnvelopeDao(): ProcessedEnvelopeDao
    abstract fun pairRelationshipDao(): PairRelationshipDao
    abstract fun connectionDao(): ConnectionDao
    abstract fun sessionDao(): SessionDao
    abstract fun skippedKeyDao(): SkippedKeyDao
    abstract fun pendingInviteDao(): PendingInviteDao
    abstract fun groupDao(): GroupDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun groupMessageDeliveryDao(): GroupMessageDeliveryDao
    abstract fun callHistoryDao(): CallHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: TorXDatabase? = null

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `call_history` (
                        `callId` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `peerIdentityId` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `connectedAt` INTEGER,
                        `endedAt` INTEGER,
                        `durationMs` INTEGER,
                        PRIMARY KEY(`callId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_history_conversationId` ON `call_history` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_history_peerIdentityId` ON `call_history` (`peerIdentityId`)")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `remote_identity_id` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): TorXDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): TorXDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                TorXDatabase::class.java,
                "torxone.db"
            )
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                .build()
        }
    }
}
