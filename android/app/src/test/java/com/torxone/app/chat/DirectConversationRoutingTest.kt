package com.torxone.app.chat

import com.torxone.app.connection.Connection
import com.torxone.app.data.dao.ContactDao
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.entity.ContactEntity
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.ConversationType
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.incoming.ActiveConversationTracker
import com.torxone.app.incoming.ChatReceiver
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.SecureEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class DirectConversationRoutingTest {

    private lateinit var messageDao: FakeMessageDao
    private lateinit var conversationDao: FakeConversationDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var tracker: ActiveConversationTracker
    private lateinit var chatReceiver: ChatReceiver

    @Before
    fun setUp() {
        messageDao = FakeMessageDao()
        conversationDao = FakeConversationDao()
        contactDao = FakeContactDao()
        tracker = ActiveConversationTracker()
        chatReceiver = ChatReceiver(
            messageDao = messageDao,
            conversationDao = conversationDao,
            activeConversationTracker = tracker,
            notificationManager = null,
            contactDao = contactDao
        )
    }

    @Test
    fun testDirectChatWithDifferentLocalConversationIdsOnEachPeer() = runBlocking {
        // Alice has conversationId "conv-alice-local"
        // Bob has conversationId "conv-bob-local" for relationship "rel-123"
        val relationshipId = "rel-123"
        val aliceWireConversationId = "conv-alice-local"
        val bobLocalConversationId = "conv-bob-local"

        // Set up Bob's contact for Alice
        val bobContactForAlice = ContactEntity(
            contactId = "contact-alice",
            displayName = "Alice",
            relationshipId = relationshipId,
            conversationId = bobLocalConversationId,
            remoteIdentityId = "alice-identity-id",
            signingPublicKey = ByteArray(32)
        )
        contactDao.upsert(bobContactForAlice)

        // Set up Bob's local conversation
        val bobConversation = ConversationEntity(
            conversationId = bobLocalConversationId,
            type = ConversationType.DIRECT,
            title = "Alice"
        )
        conversationDao.upsert(bobConversation)

        // Alice sends a message with wire conversationId = "conv-alice-local"
        val connection = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = "q-send-alice",
            recvQueueId = "q-recv-bob",
            sendAuth = byteArrayOf(1),
            recvAuth = byteArrayOf(2)
        )

        val wireEnvelope = SecureEnvelope(
            logicalMessageId = "msg-101",
            conversationId = aliceWireConversationId, // Wire ID differs from Bob's local ID
            senderIdentity = "alice-identity-id",
            recipientBinding = "bob-identity-id",
            messageType = MessageType.TEXT,
            payload = "Hello Bob!".toByteArray(Charsets.UTF_8),
            directionSequence = 1L,
            timestamp = System.currentTimeMillis()
        )

        // Bob receives the message
        val accepted = chatReceiver.receiveTextMessage(connection, wireEnvelope)
        assertTrue("Message should be accepted by receiver", accepted)

        // Verify Bob stored the message under Bob's local conversationId, NOT Alice's wire ID
        val storedMsg = messageDao.getById("msg-101")
        assertNotNull("Message must be stored in database", storedMsg)
        assertEquals("Bob's local conversation ID must be used", bobLocalConversationId, storedMsg!!.conversationId)
        assertNotEquals("Alice's wire conversation ID must NOT be stored", aliceWireConversationId, storedMsg.conversationId)
        assertEquals("Hello Bob!", storedMsg.body)
        assertEquals(MessageDirection.INCOMING, storedMsg.direction)

        // Verify Bob's conversation was updated
        val updatedConv = conversationDao.getById(bobLocalConversationId)
        assertNotNull("Bob's conversation must exist", updatedConv)
        assertEquals("msg-101", updatedConv!!.lastMessageId)
        assertEquals("Hello Bob!", updatedConv.lastMessagePreview)

        // Verify Alice's wire conversation was NOT created on Bob's side
        assertNull("Wire conversation ID must not create duplicate conversation", conversationDao.getById(aliceWireConversationId))
    }

    @Test
    fun testNoDuplicateDirectConversationCreationFromWireId() = runBlocking {
        val relationshipId = "rel-456"
        val bobLocalConversationId = "conv-bob-local-456"

        // Bob has established contact
        val contact = ContactEntity(
            contactId = "contact-charlie",
            displayName = "Charlie",
            relationshipId = relationshipId,
            conversationId = bobLocalConversationId,
            remoteIdentityId = "charlie-identity-id",
            signingPublicKey = ByteArray(32)
        )
        contactDao.upsert(contact)

        val conversation = ConversationEntity(
            conversationId = bobLocalConversationId,
            type = ConversationType.DIRECT,
            title = "Charlie"
        )
        conversationDao.upsert(conversation)

        val connection = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = "q-send-charlie",
            recvQueueId = "q-recv-bob",
            sendAuth = byteArrayOf(1),
            recvAuth = byteArrayOf(2)
        )

        // Attacker or foreign client sends arbitrary wire conversation ID
        val arbitraryWireConvId = "attacker-injected-wire-conv-id"
        val wireEnvelope = SecureEnvelope(
            logicalMessageId = "msg-202",
            conversationId = arbitraryWireConvId,
            senderIdentity = "attacker-identity-id",
            recipientBinding = "bob-identity-id",
            messageType = MessageType.TEXT,
            payload = "Test message with foreign conv ID".toByteArray(Charsets.UTF_8),
            directionSequence = 2L,
            timestamp = System.currentTimeMillis()
        )

        val accepted = chatReceiver.receiveTextMessage(connection, wireEnvelope)
        assertTrue(accepted)

        // Only one conversation should exist on Bob's side: bobLocalConversationId
        assertEquals(1, conversationDao.convs.size)
        assertNotNull(conversationDao.getById(bobLocalConversationId))
        assertNull("No duplicate conversation created for foreign wire ID", conversationDao.getById(arbitraryWireConvId))

        // Message routed to local conversation
        val storedMsg = messageDao.getById("msg-202")
        assertEquals(bobLocalConversationId, storedMsg?.conversationId)
    }

    // ── Test In-Memory Fakes ──

    class FakeMessageDao : MessageDao {
        val messages = ConcurrentHashMap<String, MessageEntity>()

        override fun observeByConversation(conversationId: String): Flow<List<MessageEntity>> =
            flowOf(messages.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAt })

        override suspend fun getById(messageId: String): MessageEntity? = messages[messageId]
        override suspend fun exists(messageId: String): Boolean = messages.containsKey(messageId)
        override suspend fun insertIfAbsent(message: MessageEntity): Long {
            return if (messages.putIfAbsent(message.logicalMessageId, message) == null) 1L else -1L
        }
        override suspend fun upsert(message: MessageEntity) { messages[message.logicalMessageId] = message }
        override suspend fun updateStatus(messageId: String, status: String) {
            messages[messageId]?.let { messages[messageId] = it.copy(status = status) }
        }
        override suspend fun markDelivered(messageId: String, status: String, deliveredAt: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(status = status, deliveredAt = deliveredAt) }
        }
        override suspend fun markRead(messageId: String, status: String, readAt: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(status = status, readAt = readAt) }
        }
        override suspend fun markOutgoingReadUpTo(conversationId: String, upToCreatedAt: Long, status: String, readAt: Long) {}
        override suspend fun markAllIncomingRead(conversationId: String, status: String, readAt: Long) {}
        override suspend fun getLatestUnreadIncoming(conversationId: String): MessageEntity? = null
        override suspend fun updateBodyAndEdit(messageId: String, newBody: String, editVersion: Int, editedAt: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(body = newBody, editVersion = editVersion, editedAt = editedAt) }
        }
        override suspend fun markDeleted(messageId: String, deletedAt: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(body = null, deletedAt = deletedAt) }
        }
        override suspend fun getMessagesForConversationDesc(conversationId: String): List<MessageEntity> =
            messages.values.filter { it.conversationId == conversationId }.sortedByDescending { it.createdAt }
        override suspend fun deleteByConversation(conversationId: String) {
            messages.entries.removeIf { it.value.conversationId == conversationId }
        }
    }

    class FakeConversationDao : ConversationDao {
        val convs = ConcurrentHashMap<String, ConversationEntity>()

        override fun observeActive(): Flow<List<ConversationEntity>> = flowOf(convs.values.toList())
        override fun observeArchived(): Flow<List<ConversationEntity>> = flowOf(emptyList())
        override fun observeArchivedCount(): Flow<Int> = flowOf(0)
        override suspend fun getById(id: String): ConversationEntity? = convs[id]
        override fun observeById(id: String): Flow<ConversationEntity?> = flowOf(convs[id])
        override suspend fun upsert(conversation: ConversationEntity) { convs[conversation.conversationId] = conversation }
        override suspend fun updateUnreadCount(id: String, count: Int) { convs[id]?.let { convs[id] = it.copy(unreadCount = count) } }
        override suspend fun updateManuallyUnread(id: String, manuallyUnread: Boolean) {}
        override suspend fun updateLastMessage(conversationId: String, messageId: String, preview: String?, time: Long) {
            convs[conversationId]?.let {
                convs[conversationId] = it.copy(lastMessageId = messageId, lastMessagePreview = preview, lastMessageTime = time)
            }
        }
        override suspend fun updateLastMessagePreviewIfLatest(messageId: String, preview: String?) {}
        override suspend fun setPinned(id: String, isPinned: Boolean, pinnedAt: Long?) {}
        override suspend fun setArchived(id: String, isArchived: Boolean, archivedAt: Long?) {}
        override suspend fun unarchive(id: String) {}
        override suspend fun setMutedUntil(id: String, mutedUntil: Long?) {}
        override suspend fun deleteById(id: String) { convs.remove(id) }
        override fun searchConversations(query: String): Flow<List<ConversationEntity>> = flowOf(emptyList())
    }

    class FakeContactDao : ContactDao {
        val contacts = ConcurrentHashMap<String, ContactEntity>()

        override fun observeAll(): Flow<List<ContactEntity>> = flowOf(contacts.values.toList())
        override suspend fun getById(id: String): ContactEntity? = contacts[id]
        override suspend fun getByRelationshipId(relationshipId: String): ContactEntity? =
            contacts.values.firstOrNull { it.relationshipId == relationshipId }
        override suspend fun getByConversationId(conversationId: String): ContactEntity? =
            contacts.values.firstOrNull { it.conversationId == conversationId }
        override suspend fun getAll(): List<ContactEntity> = contacts.values.toList()
        override suspend fun upsert(contact: ContactEntity) { contacts[contact.contactId] = contact }
    }
}
