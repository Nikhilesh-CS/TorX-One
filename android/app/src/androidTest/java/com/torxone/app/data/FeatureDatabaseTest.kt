package com.torxone.app.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.torxone.app.data.entity.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureDatabaseTest {
    @Test fun conversationMetadataUpdatesPreserveHistoryAndLocalStateAcrossReopen() = runBlocking {
        val name = "conversation-update-preservation-test.db"
        context.deleteDatabase(name)
        try {
            withDatabase(name) { db ->
                db.conversationDao().upsert(ConversationEntity("chat", title = "Original"))
                db.messageDao().insertIfAbsent(MessageEntity("incoming", "chat", "peer", "TEXT",
                    "received fixture", MessageDirection.INCOMING, "DELIVERED"))
                db.messageDao().insertIfAbsent(MessageEntity("outgoing", "chat", "local", "TEXT",
                    "pending fixture", MessageDirection.OUTGOING, "QUEUED"))
                db.outboxDao().insert(OutboxEntity("delivery", "outgoing", "chat", "connection", "queue",
                    byteArrayOf(1), ByteArray(32), "QUEUED"))
                db.featureDao().saveDraft(ConversationDraftEntity("chat", "draft fixture", "incoming"))
                db.featureDao().star(StarredMessageEntity("incoming", "chat", 1))
                db.featureDao().saveAppearance(ConversationAppearanceEntity("chat", "OCEAN"))
                db.securityPolicyDao().save(com.torxone.app.privacy.ConversationSecurityPolicy("chat", 60_000L, 1))
                repeat(5) { index ->
                    val conversation = requireNotNull(db.conversationDao().getById("chat"))
                    db.conversationDao().upsert(conversation.copy(title = "Updated-$index", avatarHash = "avatar-$index"))
                    assertEquals(2, db.messageDao().observeByConversation("chat").first().size)
                }
            }
            withDatabase(name) { db ->
                assertEquals("Updated-4", db.conversationDao().getById("chat")?.title)
                assertEquals("avatar-4", db.conversationDao().getById("chat")?.avatarHash)
                assertEquals("received fixture", db.messageDao().getById("incoming")?.body)
                assertEquals("pending fixture", db.messageDao().getById("outgoing")?.body)
                assertEquals(1, db.outboxDao().getPending().size)
                assertEquals("draft fixture", db.featureDao().draft("chat")?.text)
                assertEquals(listOf("incoming"), db.featureDao().observeStars("chat").first())
                assertEquals("OCEAN", db.featureDao().observeAppearance("chat").first()?.theme)
                assertEquals(60_000L, db.securityPolicyDao().policy("chat")?.disappearAfterMs)
                // An explicit user deletion still cascades as intended.
                db.conversationDao().deleteById("chat")
                assertNull(db.messageDao().getById("incoming"))
                assertNull(db.featureDao().draft("chat"))
                assertNull(db.securityPolicyDao().policy("chat"))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun pendingMessageCounterExcludesBackgroundControlsAndFanoutDuplicates() = runBlocking {
        val name = "pending-count-test.db"
        context.deleteDatabase(name)
        try {
            withDatabase(name) { db ->
                db.conversationDao().upsert(ConversationEntity("chat", title = "Fixture"))
                db.messageDao().insertIfAbsent(MessageEntity("text", "chat", "local", "TEXT", "synthetic",
                    MessageDirection.OUTGOING, "QUEUED"))
                for ((delivery, message) in listOf("a" to "text", "b" to "text", "c" to "receipt")) {
                    db.outboxDao().insert(OutboxEntity(delivery, message, "chat", "connection", "queue",
                        byteArrayOf(1), ByteArray(32), "QUEUED"))
                }
                assertEquals(1, db.featureDao().pendingMessageCount("chat"))
                assertEquals(3, db.featureDao().pendingDeliveryCount("chat"))
                assertEquals(1, db.featureDao().pendingControlCount("chat"))
            }
        } finally { context.deleteDatabase(name) }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(name: String) = Room.databaseBuilder(context, TorXDatabase::class.java, name)
        .addMigrations(TorXDatabase.MIGRATION_15_16, TorXDatabase.MIGRATION_16_17,
            TorXDatabase.MIGRATION_17_18, TorXDatabase.MIGRATION_18_19, TorXDatabase.MIGRATION_19_20).addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                FeatureSchema.installSearchTriggers(db)
                LinkSchema.installTriggers(db)
            }
        }).build()
    private suspend fun withDatabase(name: String, block: suspend (TorXDatabase) -> Unit) {
        val db = open(name)
        try { block(db) } finally { db.close() }
    }

    @Test fun linkQueueSurvivesRestartAndMetadataTracksEditsAndDeletion() = runBlocking {
        val name = "feature-links-test.db"
        context.deleteDatabase(name)
        try {
            withDatabase(name) { db ->
                db.conversationDao().upsert(ConversationEntity("chat", title = "Alice"))
                db.messageDao().insertIfAbsent(MessageEntity("message", "chat", "peer", "TEXT",
                    "See https://example.com/docs", MessageDirection.INCOMING, "DELIVERED"))
                assertEquals(1, db.featureDao().pendingLinkMessages().size)
            }
            withDatabase(name) { db ->
                val index = com.torxone.app.chat.MessageLinkIndex(db)
                assertEquals(1, index.drainBatch())
                assertEquals(0, index.drainBatch())
                assertEquals("example.com", db.featureDao().observeLinks("chat").first().single().host)
                db.messageDao().updateBodyAndEdit("message", "See https://openai.com/docs", 1, 2)
                assertTrue(db.featureDao().observeLinks("chat").first().isEmpty())
                assertEquals(1, index.drainBatch())
                assertEquals("openai.com", db.featureDao().observeLinks("chat").first().single().host)
                db.localMessageStateDao().upsert(LocalMessageStateEntity("message", "chat"))
                assertTrue(db.featureDao().observeLinks("chat").first().isEmpty())
                db.localMessageStateDao().delete("message")
                db.messageDao().markDeleted("message", 3)
                assertTrue(db.featureDao().observeLinks("chat").first().isEmpty())
                assertEquals(0, index.drainBatch())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun draftsStarsAndCapabilitiesSurviveReopen() = runBlocking {
        val name = "feature-restart-test.db"
        context.deleteDatabase(name)
        try {
            withDatabase(name) { db ->
                db.conversationDao().upsert(ConversationEntity("chat", title = "Alice"))
                db.messageDao().insertIfAbsent(MessageEntity("message", "chat", "peer", "TEXT", "hello", MessageDirection.INCOMING, "DELIVERED"))
                db.featureDao().saveDraft(ConversationDraftEntity("chat", "reply later", "message"))
                db.featureDao().star(StarredMessageEntity("message", "chat", 1))
                db.featureDao().saveCapabilities(PeerCapabilitiesEntity("relationship", "UNKNOWN_MESSAGE_V1", 1))
                db.featureDao().saveAlias(ContactAliasEntity("peer", "My friend"))
                db.featureDao().saveAppearance(ConversationAppearanceEntity("chat", "OCEAN", "COOL", "SQUARE"))
            }
            withDatabase(name) { db ->
                assertEquals("reply later", db.featureDao().draft("chat")?.text)
                assertEquals("message", db.featureDao().draft("chat")?.replyToMessageId)
                assertEquals(listOf("message"), db.featureDao().observeStars("chat").first())
                assertEquals("UNKNOWN_MESSAGE_V1", db.featureDao().capabilities("relationship")?.features)
                assertEquals("My friend", db.featureDao().alias("peer")?.alias)
                assertEquals("OCEAN", db.featureDao().observeAppearance("chat").first()?.theme)
                db.conversationDao().deleteById("chat")
                assertNull(db.featureDao().draft("chat"))
                assertNull(db.featureDao().observeAppearance("chat").first())
                assertEquals("My friend", db.featureDao().alias("peer")?.alias)
                assertTrue(db.featureDao().observeStars("chat").first().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationPreservesMessagesAndSearchTracksEditsHidesAndDeletes() = runBlocking {
        val name = "feature-migration-test.db"
        context.deleteDatabase(name)
        try {
            val asset = InstrumentationRegistry.getInstrumentation().context.assets
                .open("com.torxone.app.data.TorXDatabase/15.json").bufferedReader().use { it.readText() }
            val schema = JSONObject(asset).getJSONObject("database")
            val file = context.getDatabasePath(name); file.parentFile?.mkdirs()
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL("PRAGMA foreign_keys=ON")
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                old.execSQL("INSERT INTO conversations(conversationId,type,title,unread_count,is_pinned,is_archived,manually_unread,created_at) VALUES('chat','DIRECT','Alice',0,0,0,0,1)")
                old.execSQL("INSERT INTO messages(logical_message_id,conversation_id,sender_id,type,body,direction,status,created_at,edit_version) VALUES('message','chat','peer','TEXT','original searchable','INCOMING','DELIVERED',1,0)")
                old.version = 15
            }
            withDatabase(name) { db ->
                assertEquals("original searchable", db.messageDao().getById("message")?.body)
                assertEquals(1, db.featureDao().search("original", "chat").first().size)
                db.messageDao().updateBodyAndEdit("message", "changed searchable", 1, 2)
                assertTrue(db.featureDao().search("original", "chat").first().isEmpty())
                assertEquals(1, db.featureDao().search("changed", "chat").first().size)
                db.localMessageStateDao().upsert(LocalMessageStateEntity("message", "chat"))
                assertTrue(db.featureDao().search("changed", "chat").first().isEmpty())
                db.localMessageStateDao().delete("message")
                db.messageDao().markDeleted("message", 3)
                assertTrue(db.featureDao().search("changed", "chat").first().isEmpty())
                assertNotNull(db.messageDao().getById("message"))
            }
        } finally { context.deleteDatabase(name) }
    }
}
