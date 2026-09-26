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
        CallHistoryEntity::class,
        ConsumedInviteEntity::class,
        BootstrapStateEntity::class
    ],
    version = 9,
    exportSchema = true
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
    abstract fun consumedInviteDao(): ConsumedInviteDao
    abstract fun bootstrapStateDao(): BootstrapStateDao

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

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `consumed_invites` (
                        `invite_id` TEXT NOT NULL,
                        `consumed_at` INTEGER NOT NULL,
                        PRIMARY KEY(`invite_id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `bootstrap_states` (
                        `relationship_id` TEXT NOT NULL,
                        `invite_id` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `is_initiator` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `error_message` TEXT,
                        PRIMARY KEY(`relationship_id`)
                    )
                """.trimIndent())
            }
        }

        fun getInstance(
            context: Context,
            passphraseProvider: DatabasePassphraseProvider? = null
        ): TorXDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, passphraseProvider).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(
            context: Context,
            passphraseProvider: DatabasePassphraseProvider? = null
        ): TorXDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                TorXDatabase::class.java,
                "torxone.db"
            ).addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)

            val provider = passphraseProvider ?: DatabasePassphraseProvider(context.applicationContext)
            try {
                val passphrase = provider.getOrCreatePassphrase()
                migratePlaintextIfNeeded(context.applicationContext, passphrase)
                val factory = net.sqlcipher.database.SupportFactory(passphrase)
                builder.openHelperFactory(factory)
            } catch (e: Exception) {
                if (provider.allowInsecureFallback) {
                    android.util.Log.w("TorXDatabase", "Warning: Running database with insecure fallback openHelperFactory")
                } else {
                    throw SecurityException("Failed to configure encrypted database SQLite factory", e)
                }
            }

            return builder.build()
        }

        private fun migratePlaintextIfNeeded(context: Context, passphrase: ByteArray) {
            val dbFile = context.getDatabasePath("torxone.db")
            if (!dbFile.exists() || dbFile.length() < 16) return

            val header = ByteArray(16)
            try {
                java.io.FileInputStream(dbFile).use { fis ->
                    val read = fis.read(header)
                    if (read < 16) return
                }
            } catch (_: Exception) {
                return
            }

            val expectedPlainHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            if (header.contentEquals(expectedPlainHeader)) {
                android.util.Log.i("TorXDatabase", "Detected legacy plaintext SQLite database. Migrating to SQLCipher...")
                try {
                    net.sqlcipher.database.SQLiteDatabase.loadLibs(context)
                    val tempEncryptedFile = java.io.File(dbFile.parentFile, "torxone_encrypted.db")
                    if (tempEncryptedFile.exists()) tempEncryptedFile.delete()

                    val plaintextDb = net.sqlcipher.database.SQLiteDatabase.openOrCreateDatabase(dbFile, "", null)
                    val hexKey = passphrase.joinToString("") { "%02x".format(it) }
                    plaintextDb.rawExecSQL("ATTACH DATABASE '${tempEncryptedFile.absolutePath}' AS encrypted KEY \"x'$hexKey'\";")
                    plaintextDb.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                    plaintextDb.rawExecSQL("DETACH DATABASE encrypted;")
                    plaintextDb.close()

                    val backupFile = java.io.File(dbFile.parentFile, "torxone.db.plain.bak")
                    if (backupFile.exists()) backupFile.delete()
                    if (dbFile.renameTo(backupFile)) {
                        if (!tempEncryptedFile.renameTo(dbFile)) {
                            backupFile.renameTo(dbFile)
                            throw java.io.IOException("Failed to rename encrypted database to target")
                        }
                    }
                    android.util.Log.i("TorXDatabase", "Plaintext SQLite database successfully migrated to encrypted SQLCipher.")
                } catch (e: Exception) {
                    android.util.Log.e("TorXDatabase", "Failed to migrate plaintext database to SQLCipher: ${e.message}", e)
                }
            }
        }
    }
}

/**
 * Provides the hardware Keystore-backed database encryption passphrase.
 *
 * Uses EncryptedSharedPreferences backed by Android Keystore MasterKey (AES-256-GCM)
 * to persist a 256-bit cryptographic passphrase for SQLCipher.
 */
class DatabasePassphraseProvider(
    private val context: Context,
    val allowInsecureFallback: Boolean = false
) {
    fun getOrCreatePassphrase(): ByteArray {
        val prefs = try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()

            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "torx_db_secure_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            if (allowInsecureFallback) {
                context.getSharedPreferences("torx_db_fallback_prefs", Context.MODE_PRIVATE)
            } else {
                throw SecurityException("Database hardware Keystore initialization failed. Plaintext fallback rejected.", e)
            }
        }

        val existingBase64 = prefs.getString("db_passphrase", null)
        if (existingBase64 != null) {
            return java.util.Base64.getDecoder().decode(existingBase64)
        }

        val newPassphrase = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("db_passphrase", java.util.Base64.getEncoder().encodeToString(newPassphrase))
            .apply()
        return newPassphrase
    }
}
