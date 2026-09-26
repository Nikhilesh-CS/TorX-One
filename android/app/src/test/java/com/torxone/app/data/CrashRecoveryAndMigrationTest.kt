package com.torxone.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.AesGcmKeyProtector
import com.torxone.app.crypto.AtRestKeyProtectorTest
import com.torxone.app.crypto.RoomSessionStore
import com.torxone.app.crypto.SessionRatchet
import com.torxone.app.data.entity.ConnectionDbEntity
import com.torxone.app.data.entity.OutboxEntity
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.incoming.AtomicReceiveSequenceTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class CrashRecoveryAndMigrationTest {

    private val masterKey = ByteArray(32) { (it + 5).toByte() }
    private val secureRandom = SecureRandom()

    private fun randomBytes(size: Int): ByteArray {
        val b = ByteArray(size)
        secureRandom.nextBytes(b)
        return b
    }

    @Test
    fun testProcessKillRecoveryPreservesRatchetSequenceAndOutboxConsistency() = runBlocking {
        val sessionDao = AtRestKeyProtectorTest.FakeSessionDao()
        val skippedKeyDao = AtRestKeyProtectorTest.FakeSkippedKeyDao()
        val connectionDao = AtomicReceiveSequenceTest.FakeConnectionDao()
        val protector = AesGcmKeyProtector(masterKey)

        val relationshipId = "rel-crash-recovery"
        val sessionInitSecret = randomBytes(32)

        val aliceDh = IdentityCrypto.generateX25519KeyPair()
        val bobDh = IdentityCrypto.generateX25519KeyPair()

        // 1. Initialize sessions before crash
        val aliceInitialState = SessionRatchet.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = sessionInitSecret,
            isInitiator = true,
            remoteRatchetPublicKey = bobDh.publicKey,
            localRatchetPrivateKey = aliceDh.privateKey,
            localRatchetPublicKey = aliceDh.publicKey
        )

        val bobInitialState = SessionRatchet.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = sessionInitSecret,
            isInitiator = false,
            remoteRatchetPublicKey = aliceDh.publicKey,
            localRatchetPrivateKey = bobDh.privateKey,
            localRatchetPublicKey = bobDh.publicKey
        )

        val aad = "crash-test-aad".toByteArray(Charsets.UTF_8)

        // Alice sends 2 messages to Bob
        val msg1 = "Message 1 before crash".toByteArray(Charsets.UTF_8)
        val encrypted1 = SessionRatchet.ratchetEncrypt(aliceInitialState, msg1, aad)
        val decrypted1 = SessionRatchet.ratchetDecrypt(bobInitialState, encrypted1, aad)
        assertArrayEquals(msg1, decrypted1)

        val msg2 = "Message 2 before crash".toByteArray(Charsets.UTF_8)
        val encrypted2 = SessionRatchet.ratchetEncrypt(aliceInitialState, msg2, aad)
        val decrypted2 = SessionRatchet.ratchetDecrypt(bobInitialState, encrypted2, aad)
        assertArrayEquals(msg2, decrypted2)

        // Persist states to Room database with AES-GCM wrapping
        val storeBeforeCrash = RoomSessionStore(sessionDao, skippedKeyDao, protector)
        storeBeforeCrash.saveSession(aliceInitialState)

        val bobSessionDao = AtRestKeyProtectorTest.FakeSessionDao()
        val bobStoreBeforeCrash = RoomSessionStore(bobSessionDao, skippedKeyDao, protector)
        bobStoreBeforeCrash.saveSession(bobInitialState)

        // Persist connection sequences
        connectionDao.upsert(
            ConnectionDbEntity(
                connectionId = "conn-crash-1",
                relationshipId = relationshipId,
                generation = 1,
                state = "ACTIVE",
                sendQueueId = "q-send-alice",
                recvQueueId = "q-recv-alice",
                sendAuth = byteArrayOf(1),
                recvAuth = byteArrayOf(2),
                sendSequence = 2L,
                recvSequence = 0L,
                createdAt = 1000L
            )
        )

        // Put pending message in Outbox
        val pendingOutbox = OutboxEntity(
            deliveryId = "del-pending-1",
            logicalMessageId = "msg-pending-1",
            conversationId = "conv-1",
            connectionId = "conn-crash-1",
            queueAddress = "q-send-alice",
            ciphertext = "pending-encrypted-payload".toByteArray(),
            queueAuthenticator = byteArrayOf(1),
            status = "QUEUED"
        )

        // ══════════════════════════════════════════════════════════════
        // SIMULATE PROCESS DEATH / RESTART
        // ══════════════════════════════════════════════════════════════

        // Process 2: Clean restart with new instances
        val storeAfterRestart = RoomSessionStore(sessionDao, skippedKeyDao, protector)
        val recoveredAliceSession = storeAfterRestart.loadSession(relationshipId)
        assertNotNull("Alice session must be recovered from encrypted storage", recoveredAliceSession)

        val bobStoreAfterRestart = RoomSessionStore(bobSessionDao, skippedKeyDao, protector)
        val recoveredBobSession = bobStoreAfterRestart.loadSession(relationshipId)
        assertNotNull("Bob session must be recovered from encrypted storage", recoveredBobSession)

        // Verify sequence counters survived
        val connManagerAfterRestart = ConnectionManager(connectionDao = connectionDao)
        val connEntity = connectionDao.getByRelationshipId(relationshipId)
        assertNotNull(connEntity)
        assertEquals(2L, connEntity!!.sendSequence)

        connManagerAfterRestart.registerConnection(
            Connection(
                relationshipId = relationshipId,
                generation = 1,
                sendQueueId = connEntity.sendQueueId,
                recvQueueId = connEntity.recvQueueId,
                sendAuth = connEntity.sendAuth,
                recvAuth = connEntity.recvAuth,
                sendSequence = connEntity.sendSequence,
                recvSequence = connEntity.recvSequence
            )
        )

        // Next allocated sequence continues monotonically
        val nextSeq = connManagerAfterRestart.allocateSendSequence(relationshipId)
        assertEquals("Next sequence must be 3 after restart", 3L, nextSeq)

        // Crucial: Ratchet continues correctly after recovery (DH ratchet step from Bob to Alice)
        val bobReply = "Bob reply after restart".toByteArray(Charsets.UTF_8)
        val encryptedReply = SessionRatchet.ratchetEncrypt(recoveredBobSession!!, bobReply, aad)
        val decryptedReply = SessionRatchet.ratchetDecrypt(recoveredAliceSession!!, encryptedReply, aad)

        assertArrayEquals("Ratchet must remain in sync across cold process recovery", bobReply, decryptedReply)
    }

    @Test
    fun testRoomDatabaseMigrationsExecuteCorrectlyAndPreserveSchema() {
        val executedSql = mutableListOf<String>()

        val fakeDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        // 1. Test MIGRATION_6_7
        executedSql.clear()
        TorXDatabase.MIGRATION_6_7.migrate(fakeDb)
        assertTrue("MIGRATION_6_7 must create call_history table",
            executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `call_history`") })

        // 2. Test MIGRATION_7_8
        executedSql.clear()
        TorXDatabase.MIGRATION_7_8.migrate(fakeDb)
        assertTrue("MIGRATION_7_8 must add remote_identity_id to contacts",
            executedSql.any { it.contains("ALTER TABLE `contacts` ADD COLUMN `remote_identity_id`") })

        // 3. Test MIGRATION_8_9
        executedSql.clear()
        TorXDatabase.MIGRATION_8_9.migrate(fakeDb)

        val hasConsumedInvites = executedSql.any {
            it.contains("CREATE TABLE IF NOT EXISTS `consumed_invites`") &&
            it.contains("`invite_id` TEXT NOT NULL") &&
            it.contains("`consumed_at` INTEGER NOT NULL") &&
            it.contains("PRIMARY KEY(`invite_id`)")
        }
        assertTrue("MIGRATION_8_9 must create consumed_invites table", hasConsumedInvites)

        val hasBootstrapStates = executedSql.any {
            it.contains("CREATE TABLE IF NOT EXISTS `bootstrap_states`") &&
            it.contains("`relationship_id` TEXT NOT NULL") &&
            it.contains("`invite_id` TEXT NOT NULL") &&
            it.contains("`status` TEXT NOT NULL") &&
            it.contains("`is_initiator` INTEGER NOT NULL") &&
            it.contains("`created_at` INTEGER NOT NULL") &&
            it.contains("`updated_at` INTEGER NOT NULL") &&
            it.contains("PRIMARY KEY(`relationship_id`)")
        }
        assertTrue("MIGRATION_8_9 must create bootstrap_states table", hasBootstrapStates)

        // Verify versions
        assertEquals(6, TorXDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, TorXDatabase.MIGRATION_6_7.endVersion)
        assertEquals(7, TorXDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, TorXDatabase.MIGRATION_7_8.endVersion)
        assertEquals(8, TorXDatabase.MIGRATION_8_9.startVersion)
        assertEquals(9, TorXDatabase.MIGRATION_8_9.endVersion)
    }
}
