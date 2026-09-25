package com.torxone.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*

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
        MediaTransferEntity::class
    ],
    version = 5,
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

    companion object {
        @Volatile
        private var INSTANCE: TorXDatabase? = null

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
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
