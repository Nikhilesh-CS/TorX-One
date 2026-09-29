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
        BootstrapStateEntity::class,
        RelayPacketEntity::class,
        RelayReceiptEntity::class
    ],
    version = 12,
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
    abstract fun relayQueueDao(): RelayQueueDao

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

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `application_sequence` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `crypto_format_version` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `skipped_message_keys` ADD COLUMN `crypto_format_version` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pair_relationships` ADD COLUMN `crypto_format_version` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `connections` ADD COLUMN `crypto_format_version` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pending_invites` ADD COLUMN `crypto_format_version` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `relationship_id` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE `outbox` SET `relationship_id` = COALESCE((SELECT `relationship_id` FROM `connections` WHERE `connections`.`connection_id` = `outbox`.`connection_id`), '') WHERE `application_sequence` IS NOT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_relationship_id_application_sequence` ON `outbox` (`relationship_id`, `application_sequence`)")
            }
        }

        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `relay_packets` (
                        `packet_id` TEXT NOT NULL, `source_node_id` TEXT NOT NULL,
                        `destination_node_id` TEXT NOT NULL, `ingress_peer_node_id` TEXT NOT NULL,
                        `encoded_packet` BLOB NOT NULL, `packet_bytes` INTEGER NOT NULL,
                        `priority` INTEGER NOT NULL, `remaining_ttl` INTEGER NOT NULL,
                        `status` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL,
                        `next_attempt_at` INTEGER NOT NULL, `created_at` INTEGER NOT NULL,
                        `expires_at` INTEGER NOT NULL, `last_error` TEXT,
                        `last_next_hop_node_id` TEXT,
                        PRIMARY KEY(`packet_id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_relay_packets_destination_node_id` ON `relay_packets` (`destination_node_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_relay_packets_source_node_id` ON `relay_packets` (`source_node_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_relay_packets_status_next_attempt_at_priority` ON `relay_packets` (`status`, `next_attempt_at`, `priority`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `relay_receipts` (
                        `packet_id` TEXT NOT NULL, `delivered_at` INTEGER NOT NULL,
                        `expires_at` INTEGER NOT NULL, PRIMARY KEY(`packet_id`)
                    )
                """.trimIndent())
            }
        }

        fun getInstance(
            context: Context,
            passphraseProvider: DatabasePassphraseProvider
        ): TorXDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, passphraseProvider).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(
            context: Context,
            passphraseProvider: DatabasePassphraseProvider
        ): TorXDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                TorXDatabase::class.java,
                "torxone.db"
            ).addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)

            val provider = passphraseProvider
            try {
                val passphrase = provider.getOrCreatePassphrase()
                migratePlaintextIfNeeded(context.applicationContext, passphrase)
                System.loadLibrary("sqlcipher")
                val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase)
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
                    System.loadLibrary("sqlcipher")
                    val tempEncryptedFile = java.io.File(dbFile.parentFile, "torxone_encrypted.db")
                    if (tempEncryptedFile.exists()) tempEncryptedFile.delete()

                    val hexKey = passphrase.joinToString("") { "%02x".format(it) }
                    net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                        dbFile, ByteArray(0), null, null, null
                    ).use { plaintextDb ->
                        val checkpoint = plaintextDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE);", null)
                        try {
                            if (!checkpoint.moveToFirst() || checkpoint.getInt(0) != 0) {
                                throw java.io.IOException("Could not checkpoint legacy database WAL before encryption")
                            }
                        } finally {
                            checkpoint.close()
                        }
                        plaintextDb.execSQL("ATTACH DATABASE '${tempEncryptedFile.absolutePath}' AS encrypted KEY \"x'$hexKey'\";")
                        plaintextDb.rawQuery("SELECT sqlcipher_export('encrypted');", null).use { it.moveToFirst() }
                        plaintextDb.execSQL("DETACH DATABASE encrypted;")
                    }

                    // Reopen encrypted temp DB using the intended key and verify
                    val encryptedDb = net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                        tempEncryptedFile, passphrase, null, null, null
                    )
                    try {
                        val cursor = encryptedDb.rawQuery("PRAGMA integrity_check;", null)
                        var integrityOk = false
                        if (cursor.moveToFirst()) {
                            val res = cursor.getString(0)
                            integrityOk = res.equals("ok", ignoreCase = true)
                        }
                        cursor.close()
                        if (!integrityOk) {
                            throw SecurityException("SQLCipher exported database failed PRAGMA integrity_check")
                        }
                        val versionCursor = encryptedDb.rawQuery("PRAGMA user_version;", null)
                        var userVersion = -1
                        if (versionCursor.moveToFirst()) {
                            userVersion = versionCursor.getInt(0)
                        }
                        versionCursor.close()
                        if (userVersion <= 0) {
                            throw SecurityException("Invalid user_version in migrated database")
                        }
                    } finally {
                        encryptedDb.close()
                    }

                    // Sidecar handling
                    val walFile = java.io.File(dbFile.parentFile, "torxone.db-wal")
                    val shmFile = java.io.File(dbFile.parentFile, "torxone.db-shm")
                    val journalFile = java.io.File(dbFile.parentFile, "torxone.db-journal")

                    java.io.FileOutputStream(tempEncryptedFile, true).use { it.fd.sync() }
                    // Replace atomically after verification. Keeping a plaintext backup would
                    // leave a second, unencrypted copy of all account data on the device.
                    java.nio.file.Files.move(
                        tempEncryptedFile.toPath(),
                        dbFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    if (walFile.exists() && !walFile.delete()) throw java.io.IOException("Failed to remove legacy WAL sidecar")
                    if (shmFile.exists() && !shmFile.delete()) throw java.io.IOException("Failed to remove legacy SHM sidecar")
                    if (journalFile.exists() && !journalFile.delete()) throw java.io.IOException("Failed to remove legacy journal sidecar")
                    android.util.Log.i("TorXDatabase", "Plaintext SQLite database successfully migrated to encrypted SQLCipher.")
                } catch (e: Exception) {
                    android.util.Log.e("TorXDatabase", "Failed to migrate plaintext database to SQLCipher: ${e.message}", e)
                    throw SecurityException("Database encryption migration failed closed to prevent data compromise or corruption", e)
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
    private val securityRepository: com.torxone.app.security.SecurityRepository,
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
            val stored = java.util.Base64.getDecoder().decode(existingBase64)
            val format = prefs.getInt(
                "db_passphrase_crypto_format",
                com.torxone.app.security.VersionedSecurityRepository.FORMAT_V1_RAW
            )
            val passphrase = securityRepository.reveal(
                com.torxone.app.security.ProtectedSecret(format, stored),
                com.torxone.app.security.SecretPurpose.DATABASE_PASSPHRASE
            )
            if (format != securityRepository.currentCryptoFormatVersion) {
                persistPassphrase(prefs, passphrase)
            }
            return passphrase
        }

        val newPassphrase = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        persistPassphrase(prefs, newPassphrase)
        return newPassphrase
    }

    private fun persistPassphrase(prefs: android.content.SharedPreferences, passphrase: ByteArray) {
        val protected = securityRepository.protect(
            passphrase,
            com.torxone.app.security.SecretPurpose.DATABASE_PASSPHRASE
        )
        val committed = prefs.edit()
            .putString("db_passphrase", java.util.Base64.getEncoder().encodeToString(protected.bytes))
            .putInt("db_passphrase_crypto_format", protected.cryptoFormatVersion)
            .commit()
        if (!committed) {
            throw SecurityException("Failed to durably commit database encryption passphrase to Keystore-backed storage")
        }
    }
}
