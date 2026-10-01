package com.torxone.app.privacy

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.torxone.app.data.*
import com.torxone.app.data.entity.*
import com.torxone.app.media.MediaStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DisappearingDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(name: String) = Room.databaseBuilder(context, TorXDatabase::class.java, name)
        .addMigrations(TorXDatabase.MIGRATION_19_20)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                FeatureSchema.installSearchTriggers(db); LinkSchema.installTriggers(db)
            }
        }).build()

    @Test fun fileCreationIntentReconcilesOrphanAfterDatabaseReopen() = runBlocking {
        val name = "privacy-file-intent-test.db"
        context.deleteDatabase(name)
        val directory = File(context.cacheDir, "privacy-test-${UUID.randomUUID()}")
        val storage = MediaStorage(customBaseDir = directory)
        val id = UUID.randomUUID().toString()
        val file = storage.incomingFile(id, "orphan.txt")
        var db = open(name)
        try {
            SecurityPolicyService(db, storage).trackExpectedFile(id, file)
            file.writeText("created before message association")
            db.close(); db = open(name)
            assertEquals(1, db.securityPolicyDao().pendingMediaFiles().size)
            SecurityPolicyService(db, storage).cleanupDue()
            assertFalse(file.exists())
            assertTrue(db.securityPolicyDao().pendingMediaFiles().isEmpty())
            assertTrue(db.securityPolicyDao().pendingFiles().isEmpty())
        } finally { db.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    }

    @Test fun cleanupCannotUnlinkActiveWriterAndRetriesAfterWriterStops() = runBlocking {
        val name = "privacy-active-writer-test.db"
        context.deleteDatabase(name)
        val directory = File(context.cacheDir, "privacy-test-${UUID.randomUUID()}")
        val storage = MediaStorage(customBaseDir = directory)
        val id = UUID.randomUUID().toString()
        val file = storage.incomingFile(id, "writing.txt")
        val db = open(name)
        try {
            val service = SecurityPolicyService(db, storage)
            service.withTrackedFiles(id, listOf(file)) {
                file.writeText("still writing")
                service.discardFile(file.path)
                service.cleanupDue()
                assertTrue(file.exists())
                assertEquals(1, service.dao.pendingMediaFiles().size)
            }
            service.cleanupDue()
            assertFalse(file.exists())
            assertTrue(service.dao.pendingMediaFiles().isEmpty())
        } finally { db.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    }

    @Test fun expiryCleansAlreadyDeletedAttachmentAndPreservesDeletionTimestamp() = runBlocking {
        val name = "privacy-deleted-attachment-test.db"
        context.deleteDatabase(name)
        val directory = File(context.cacheDir, "privacy-test-${UUID.randomUUID()}")
        val storage = MediaStorage(customBaseDir = directory)
        val id = UUID.randomUUID().toString()
        val file = storage.saveIncomingFile(id, "deleted.txt", byteArrayOf(1))
        val db = open(name)
        try {
            db.conversationDao().upsert(ConversationEntity("chat", title = "Fixture"))
            db.messageDao().insertIfAbsent(MessageEntity("deleted", "chat", "peer", "FILE", "private",
                MessageDirection.INCOMING, "DELIVERED", createdAt = 1000, expiresAt = 61_000))
            db.mediaDao().insert(MediaEntity(id, "deleted", "chat", "DOCUMENT", "text/plain", "deleted.txt", 1,
                localPath = file.path, encryptedSha256 = "hash", mediaKey = ByteArray(32), status = "COMPLETE"))
            db.messageDao().markDeleted("deleted", 2000)
            val service = SecurityPolicyService(db, storage, clock = { 61_000 })
            assertEquals(1, service.dao.due(61_000).size)
            service.cleanupDue()
            assertNull(db.mediaDao().getById(id))
            assertFalse(file.exists())
            assertEquals(2000L, db.messageDao().getById("deleted")!!.deletedAt)
            assertTrue(service.dao.due(61_000).isEmpty())
        } finally { db.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    }

    @Test fun descriptorOwnsUnassociatedPlaintextUntilExpiry() = runBlocking {
        val name = "privacy-descriptor-file-test.db"
        context.deleteDatabase(name)
        val directory = File(context.cacheDir, "privacy-test-${UUID.randomUUID()}")
        val storage = MediaStorage(customBaseDir = directory)
        val id = UUID.randomUUID().toString()
        val file = storage.incomingFile(id, "received.txt")
        var now = 1000L
        var db = open(name)
        try {
            db.conversationDao().upsert(ConversationEntity("chat", title = "Fixture"))
            db.messageDao().insertIfAbsent(MessageEntity("received", "chat", "peer", "FILE", "private",
                MessageDirection.INCOMING, "DELIVERED", createdAt = 1000, expiresAt = 61_000))
            db.mediaDao().insert(MediaEntity(id, "received", "chat", "DOCUMENT", "text/plain", "received.txt", 1,
                encryptedSha256 = "hash", mediaKey = ByteArray(32), status = "DOWNLOADING"))
            SecurityPolicyService(db, storage).trackExpectedFile(id, file)
            file.writeText("plaintext before local_path update")
            db.close(); db = open(name)
            val service = SecurityPolicyService(db, storage, clock = { now })
            service.cleanupDue()
            assertTrue(file.exists())
            assertEquals(1, service.dao.pendingMediaFiles().size)
            now = 61_000
            service.cleanupDue()
            assertFalse(file.exists())
            assertTrue(service.dao.pendingMediaFiles().isEmpty())
        } finally { db.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    }

    @Test fun schema19UpgradeAddsIntentTableAndPreservesExistingCleanup() = runBlocking {
        val name = "privacy-schema20-test.db"
        context.deleteDatabase(name)
        try {
            val asset = InstrumentationRegistry.getInstrumentation().context.assets
                .open("com.torxone.app.data.TorXDatabase/19.json").bufferedReader().use { it.readText() }
            val schema = org.json.JSONObject(asset).getJSONObject("database")
            val file = context.getDatabasePath(name); file.parentFile?.mkdirs()
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                old.execSQL("INSERT INTO privacy_file_cleanup VALUES('/private/retry',1)")
                old.version = 19
            }
            val db = open(name)
            try {
                assertEquals(1, db.securityPolicyDao().pendingFiles().size)
                db.securityPolicyDao().trackFile(PendingMediaFile("/private/new", "media", 2))
                assertEquals(1, db.securityPolicyDao().pendingMediaFiles().size)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun restartExpiryErasesPlaintextIndexesAttachmentsAndKeepsBoundTombstones() = runBlocking {
        val name = "privacy-expiry-test.db"
        context.deleteDatabase(name)
        val directory = File(context.cacheDir, "privacy-test-${UUID.randomUUID()}")
        val storage = MediaStorage(customBaseDir = directory)
        val mediaId = UUID.randomUUID().toString()
        val localFile = storage.saveIncomingFile(mediaId, "private.txt", "private bytes".toByteArray())
        val temp = storage.getTempEncryptedFile(mediaId).apply { writeBytes(byteArrayOf(1, 2)) }
        var db = open(name)
        try {
            db.conversationDao().upsert(ConversationEntity("chat", title = "Peer", lastMessageId = "message", lastMessagePreview = "private text"))
            db.messageDao().insertIfAbsent(MessageEntity("message", "chat", "peer", "FILE", "private text",
                MessageDirection.INCOMING, "DELIVERED", createdAt = 1000, expiresAt = 61_000))
            db.messageDao().insertIfAbsent(MessageEntity("reply", "chat", "peer", "TEXT", "later reply",
                MessageDirection.INCOMING, "DELIVERED", replyToMessageId = "message"))
            db.featureDao().star(StarredMessageEntity("message", "chat", 1000))
            db.mediaDao().insert(MediaEntity(mediaId, "message", "chat", "DOCUMENT", "text/plain", "private.txt", 13,
                localPath = localFile.absolutePath, encryptedSha256 = "hash", mediaKey = ByteArray(32), thumbnailData = byteArrayOf(1), status = "COMPLETE"))
            db.mediaTransferDao().upsert(MediaTransferEntity(mediaId, mediaId, "chat", "relationship", "DOWNLOAD", 1, 1024,
                tempEncryptedPath = temp.absolutePath, status = "COMPLETED", totalBytes = 2))
            db.close(); db = open(name)
            val service = SecurityPolicyService(db, storage, clock = { 61_000 })
            service.cleanupDue()
            val tombstone = db.messageDao().getById("message")!!
            assertNull(tombstone.body); assertEquals(61_000L, tombstone.deletedAt)
            assertNull(db.messageDao().getById("reply")!!.replyToMessageId)
            assertNull(db.conversationDao().getById("chat")!!.lastMessagePreview)
            assertNull(db.mediaDao().getById(mediaId))
            assertTrue(db.mediaTransferDao().getAllByMediaId(mediaId).isEmpty())
            assertTrue(db.featureDao().search("private*", "chat").first().isEmpty())
            assertTrue(service.expiredMedia(mediaId, "relationship", "chat"))
            assertFalse(service.expiredMedia(mediaId, "other-relationship", "chat"))
            assertFalse(localFile.exists()); assertFalse(temp.exists())
            assertTrue(service.dao.pendingFiles().isEmpty())
            service.cleanupDue()
        } finally { db.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    }

    @Test fun unsupportedRecipientBlocksExpiryAndFailedFileDeletionSurvivesRestart() = runBlocking {
        val name = "privacy-capability-test.db"
        context.deleteDatabase(name)
        val directory = File(context.cacheDir, "privacy-test-${UUID.randomUUID()}")
        var db = open(name)
        try {
            val storage = MediaStorage(customBaseDir = directory)
            db.conversationDao().upsert(ConversationEntity("chat", title = "Peer"))
            val service = SecurityPolicyService(db, storage, clock = { 1000 })
            service.setTimer("chat", 60_000)
            assertTrue(runCatching { service.expiryForSend("chat", listOf("legacy"), 1000) }.isFailure)
            db.featureDao().saveCapabilities(PeerCapabilitiesEntity("updated", "DISAPPEARING_V1", 1000))
            assertEquals(61_000L, service.expiryForSend("chat", listOf("updated"), 1000))
            assertTrue(runCatching { service.expiryForSend("chat", listOf("updated", "legacy"), 1000) }.isFailure)
            // Nonempty directories cannot be unlinked by the private-file remover.
            val blocked = File(storage.mediaBaseDir, "blocked").apply { mkdirs() }
            File(blocked, "child").writeText("keep")
            service.discardFile(blocked.absolutePath)
            service.retryFileCleanup()
            assertEquals(1, service.dao.pendingFiles().size)
            db.close(); db = open(name)
            assertEquals(1, db.securityPolicyDao().pendingFiles().size)
            assertEquals(60_000L, db.securityPolicyDao().policy("chat")!!.disappearAfterMs)
        } finally { db.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    }
}
