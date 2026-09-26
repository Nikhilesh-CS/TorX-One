package com.torxone.app.contacts

import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.OutboxStore
import com.torxone.app.agent.ProcessedEnvelopeStore
import com.torxone.app.agent.TorXAgent
import com.torxone.app.calls.CallManager
import com.torxone.app.calls.CallStateMachineTest
import com.torxone.app.calls.CallType
import com.torxone.app.chat.ChatService
import com.torxone.app.chat.DirectConversationRoutingTest
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.crypto.SessionRatchet
import com.torxone.app.crypto.SessionState
import com.torxone.app.crypto.SessionStore
import com.torxone.app.data.dao.OutboxDao
import com.torxone.app.data.entity.ContactEntity
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.ConversationType
import com.torxone.app.data.entity.OutboxEntity
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.transport.TransportRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class LegacyAndFreshContactTest {

    private lateinit var messageDao: DirectConversationRoutingTest.FakeMessageDao
    private lateinit var conversationDao: DirectConversationRoutingTest.FakeConversationDao
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var connectionManager: ConnectionManager
    private lateinit var sessionStore: FakeSessionStore
    private lateinit var sessionCrypto: DoubleRatchetSessionCrypto
    private lateinit var agent: TorXAgent
    private lateinit var chatService: ChatService
    private lateinit var callManager: CallManager

    @Before
    fun setUp() = runBlocking {
        messageDao = DirectConversationRoutingTest.FakeMessageDao()
        conversationDao = DirectConversationRoutingTest.FakeConversationDao()
        outboxDao = FakeOutboxDao()
        connectionManager = ConnectionManager()
        sessionStore = FakeSessionStore()
        sessionCrypto = DoubleRatchetSessionCrypto(sessionStore)

        val router = TransportRouter()
        val inMemoryOutbox = object : OutboxStore {
            override suspend fun insert(item: DeliveryItem) {}
            override suspend fun getPendingItems() = emptyList<DeliveryItem>()
            override suspend fun updateStatus(deliveryId: String, status: DeliveryStatus) {}
            override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long) {}
            override suspend fun removeByMessageId(logicalMessageId: String) {}
        }
        val inMemoryProcessed = object : ProcessedEnvelopeStore {
            override suspend fun isProcessed(envelopeId: String) = false
            override suspend fun isMessageProcessed(logicalMessageId: String) = false
            override suspend fun markProcessed(record: com.torxone.app.agent.ProcessedEnvelope) {}
        }
        agent = TorXAgent(router, inMemoryOutbox, inMemoryProcessed, Dispatchers.Default)

        chatService = ChatService(
            database = null,
            sessionCrypto = sessionCrypto,
            connectionManager = connectionManager,
            agent = agent,
            messageDao = messageDao,
            conversationDao = conversationDao,
            outboxDao = outboxDao,
            sessionStore = sessionStore,
            transactionRunner = { block -> block() }
        )

        callManager = CallManager(
            callService = CallStateMachineTest.FakeCallSignaling(),
            localIdentityId = "alice-local-id"
        )
    }

    private fun setupActiveConnection(relationshipId: String) {
        val conn = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = "q-send-$relationshipId",
            recvQueueId = "q-recv-$relationshipId",
            sendAuth = byteArrayOf(1, 2, 3),
            recvAuth = byteArrayOf(4, 5, 6)
        )
        connectionManager.registerConnection(conn)

        val aliceKeys = IdentityCrypto.generateX25519KeyPair()
        val bobKeys = IdentityCrypto.generateX25519KeyPair()
        val sessionState = SessionRatchet.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = ByteArray(32) { 7 },
            isInitiator = true,
            remoteRatchetPublicKey = bobKeys.publicKey,
            localRatchetPrivateKey = aliceKeys.privateKey,
            localRatchetPublicKey = aliceKeys.publicKey
        )
        runBlocking { sessionStore.saveSession(sessionState) }
    }

    @Test
    fun testFreshContactUsesCorrectRemoteIdentityId() = runBlocking {
        val relId = "rel-fresh-1"
        val convId = "conv-fresh-1"
        val peerId = "bob-valid-identity-pk"

        setupActiveConnection(relId)
        conversationDao.upsert(ConversationEntity(convId, ConversationType.DIRECT, "Bob"))

        val contact = ContactEntity(
            contactId = "c-bob",
            displayName = "Bob",
            relationshipId = relId,
            conversationId = convId,
            remoteIdentityId = peerId,
            signingPublicKey = ByteArray(32)
        )

        // Verify helper
        assertTrue("Fresh contact must report isRemoteIdentityKnown = true", contact.isRemoteIdentityKnown)

        // Sending message should succeed
        val msgId = chatService.sendTextMessage(
            conversationId = convId,
            relationshipId = relId,
            localIdentityId = "alice-local-id",
            recipientId = contact.remoteIdentityId,
            text = "Hello fresh peer!"
        )

        assertNotNull(msgId)
        val storedMsg = messageDao.getById(msgId)
        assertNotNull("Message must be stored", storedMsg)
        assertEquals("Hello fresh peer!", storedMsg?.body)
        assertEquals(1, outboxDao.items.size)
        assertEquals(connQueue(relId), outboxDao.items.values.first().queueAddress)
    }

    @Test
    fun testLegacyContactWithMissingRemoteIdentityFailsSafely() = runBlocking {
        val relId = "rel-legacy-1"
        val convId = "conv-legacy-1"

        setupActiveConnection(relId)
        conversationDao.upsert(ConversationEntity(convId, ConversationType.DIRECT, "Legacy Contact"))

        // 1. Test REMOTE_IDENTITY_UNKNOWN sentinel
        val legacyContact = ContactEntity(
            contactId = "c-legacy-1",
            displayName = "Legacy User",
            relationshipId = relId,
            conversationId = convId,
            remoteIdentityId = ContactEntity.REMOTE_IDENTITY_UNKNOWN,
            signingPublicKey = ByteArray(32)
        )

        assertFalse("Legacy contact must have isRemoteIdentityKnown = false", legacyContact.isRemoteIdentityKnown)

        try {
            chatService.sendTextMessage(
                conversationId = convId,
                relationshipId = relId,
                localIdentityId = "alice-local-id",
                recipientId = legacyContact.remoteIdentityId,
                text = "Secret message that should never be sent"
            )
            fail("Expected IllegalStateException for legacy contact with unknown remote identity")
        } catch (e: IllegalStateException) {
            assertTrue("Exception message should indicate security refresh needed",
                e.message?.contains("Security information for this contact needs to be refreshed") == true)
        }

        // Verify no message or outbox entry was committed
        assertEquals(0, messageDao.messages.size)
        assertEquals(0, outboxDao.items.size)

        // 2. Test Blank remoteIdentityId
        val blankContact = ContactEntity(
            contactId = "c-legacy-2",
            displayName = "Blank User",
            relationshipId = relId,
            conversationId = convId,
            remoteIdentityId = "   ",
            signingPublicKey = ByteArray(32)
        )
        assertFalse(blankContact.isRemoteIdentityKnown)

        try {
            chatService.sendTextMessage(
                conversationId = convId,
                relationshipId = relId,
                localIdentityId = "alice-local-id",
                recipientId = blankContact.remoteIdentityId,
                text = "Another blocked message"
            )
            fail("Expected IllegalStateException for blank recipientId")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Security information for this contact needs to be refreshed") == true)
        }

        // 3. Test CallManager blocks call to legacy contact
        val callSession = callManager.startOutgoingCall(
            conversationId = convId,
            relationshipId = relId,
            peerIdentityId = legacyContact.remoteIdentityId,
            type = CallType.VOICE
        )
        assertNull("CallManager must return null and block call to legacy contact with unknown identity", callSession)
        assertNull(callManager.activeCall.value)
    }

    private fun connQueue(relId: String) = "q-send-$relId"

    class FakeOutboxDao : OutboxDao {
        val items = ConcurrentHashMap<String, OutboxEntity>()
        override suspend fun getPending(now: Long): List<OutboxEntity> = items.values.toList()
        override suspend fun insert(item: OutboxEntity) { items[item.deliveryId] = item }
        override suspend fun updateStatus(deliveryId: String, status: String, now: Long) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(status = status, updatedAt = now) }
        }
        override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long, now: Long) {}
        override suspend fun removeByMessageId(logicalMessageId: String) {
            items.entries.removeIf { it.value.logicalMessageId == logicalMessageId }
        }
        override suspend fun removeByDeliveryId(deliveryId: String) {
            items.remove(deliveryId)
        }
    }

    class FakeSessionStore : SessionStore {
        val sessions = ConcurrentHashMap<String, SessionState>()
        override suspend fun loadSession(relationshipId: String): SessionState? = sessions[relationshipId]?.copyState()
        override suspend fun saveSession(state: SessionState) { sessions[state.relationshipId] = state.copyState() }
        override suspend fun deleteSession(relationshipId: String) { sessions.remove(relationshipId) }
    }
}
