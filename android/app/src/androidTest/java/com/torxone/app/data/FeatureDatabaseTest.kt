package com.torxone.app.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
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
    @Test fun initialPairingSessionInviteEndpointAndBootstrapCommitTogetherAndSurviveReopen() = runBlocking {
        val name = "atomic-pairing-recovery-test.db"
        context.deleteDatabase(name)
        val local = com.torxone.app.identity.IdentityCrypto.generateX25519KeyPair()
        val remote = com.torxone.app.identity.IdentityCrypto.generateX25519KeyPair()
        var sessionId: String? = null
        fun store(db: TorXDatabase) = com.torxone.app.crypto.RoomSessionStore(db.sessionDao(), db.skippedKeyDao(),
            com.torxone.app.crypto.NoOpKeyProtector(), { block -> db.withTransaction { block() } })
        try {
            withDatabase(name) { db ->
                val sessions = store(db)
                val crypto = com.torxone.app.crypto.DoubleRatchetSessionCrypto(sessions)
                suspend fun pair(failAfterOutbox: Boolean) {
                    crypto.initializeAndCommit("pair", ByteArray(32) { 7 }, true,
                        remote.publicKey, local.privateKey, local.publicKey) { initial -> db.withTransaction {
                        db.pairRelationshipDao().upsert(PairRelationshipEntity("pair", "local", "contact", ByteArray(32)))
                        db.conversationDao().upsert(ConversationEntity("pair", title = "Peer"))
                        db.contactDao().upsert(ContactEntity(contactId = "contact", relationshipId = "pair", displayName = "Peer",
                            signingPublicKey = ByteArray(32), conversationId = "pair", remoteIdentityId = "peer"))
                        db.connectionDao().upsert(ConnectionDbEntity("connection", "pair", 1, "send", "recv", ByteArray(32), ByteArray(32),
                            state = "LOCAL_ESTABLISHED"))
                        db.peerTorEndpointDao().upsert(PeerTorEndpointEntity("pair", "a".repeat(56) + ".onion", 17654))
                        db.bootstrapStateDao().upsert(BootstrapStateEntity("pair", "invite", BootstrapStatus.BOOTSTRAP_QUEUED, true))
                        db.consumedInviteDao().insert(ConsumedInviteEntity("invite", 1))
                        sessions.saveSession(initial)
                        db.outboxDao().insert(OutboxEntity("bootstrap", "invite", "pair", "connection", "invite-invite",
                            byteArrayOf(1), ByteArray(32), "QUEUED", relationshipId = "pair"))
                        if (failAfterOutbox) throw java.io.IOException("Injected commit failure")
                        sessionId = initial.sessionId
                    } }
                }
                try {
                    try { pair(true); fail("Expected transaction rollback") } catch (_: java.io.IOException) {}
                    assertNull(sessions.loadSession("pair"))
                    assertNull(db.pairRelationshipDao().getById("pair"))
                    assertNull(db.consumedInviteDao().getById("invite"))
                    assertNull(db.bootstrapStateDao().getByRelationshipId("pair"))
                    assertNull(db.peerTorEndpointDao().getByRelationshipId("pair"))
                    assertNull(db.outboxDao().getByDeliveryId("bootstrap"))
                    pair(false)
                } finally { crypto.closeSession("pair") }
            }
            withDatabase(name) { db ->
                assertEquals(sessionId, store(db).loadSession("pair")?.sessionId)
                assertNotNull(db.consumedInviteDao().getById("invite"))
                assertEquals(BootstrapStatus.BOOTSTRAP_QUEUED, db.bootstrapStateDao().getByRelationshipId("pair")?.status)
                assertNotNull(db.peerTorEndpointDao().getByRelationshipId("pair"))
                assertArrayEquals(byteArrayOf(1), db.outboxDao().getByDeliveryId("bootstrap")?.ciphertext)
                assertEquals("LOCAL_ESTABLISHED", db.connectionDao().getByRelationshipId("pair")?.state)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun waitingSequenceAndVerifiedEndpointSurviveReopenWithoutSkippingHead() = runBlocking {
        val name = "waiting-peer-recovery-test.db"
        context.deleteDatabase(name)
        try {
            withDatabase(name) { db ->
                db.conversationDao().upsert(ConversationEntity("chat", title = "Peer"))
                db.messageDao().insertIfAbsent(MessageEntity("message", "chat", "local", "TEXT", "fixture",
                    MessageDirection.OUTGOING, "WAITING_FOR_PEER"))
                db.outboxDao().insert(OutboxEntity("head", "message", "chat", "connection", "queue",
                    byteArrayOf(1, 2), ByteArray(32), "WAITING_FOR_PEER", attemptCount = 8,
                    applicationSequence = 1, relationshipId = "rel"))
                db.outboxDao().insert(OutboxEntity("next", "next-message", "chat", "connection", "rotated-queue",
                    byteArrayOf(3), ByteArray(32), "QUEUED", applicationSequence = 2, relationshipId = "rel"))
                db.pairRelationshipDao().upsert(PairRelationshipEntity("rel", "local", "contact", ByteArray(32)))
                db.peerTorEndpointDao().upsert(PeerTorEndpointEntity("rel", "a".repeat(56) + ".onion", 17654))
            }
            withDatabase(name) { db ->
                val pending = db.outboxDao().getPending()
                assertEquals(listOf("head", "next"), pending.map { it.deliveryId })
                assertEquals(8, db.outboxDao().getByDeliveryId("head")?.attemptCount)
                assertArrayEquals(byteArrayOf(1, 2), db.outboxDao().getByDeliveryId("head")?.ciphertext)
                assertEquals(1, db.featureDao().pendingMessageCount("chat"))
                val routes = com.torxone.app.transport.tor.PeerTorEndpointRepository(db.peerTorEndpointDao())
                assertEquals("a".repeat(56) + ".onion", routes.resolvePersisted("rel")?.onionHost)
                // Exact receipt removal is still possible while the item is quiet/waiting.
                db.outboxDao().removeByDeliveryId("head")
                assertEquals(listOf("next"), db.outboxDao().getPending().map { it.deliveryId })
            }
        } finally { context.deleteDatabase(name) }
    }

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
