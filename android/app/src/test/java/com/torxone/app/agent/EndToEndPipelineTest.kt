package com.torxone.app.agent

import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.crypto.SessionState
import com.torxone.app.crypto.SessionStore
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import com.torxone.app.identity.*
import com.torxone.app.incoming.*
import com.torxone.app.protocol.*
import com.torxone.app.relationship.ContactBootstrapPayload
import com.torxone.app.relationship.RelationshipService
import com.torxone.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class EndToEndPipelineTest {

    class InMemoryOutboxStore : OutboxStore {
        private val seqCounter = java.util.concurrent.atomic.AtomicLong(0)
        private val insertOrder = ConcurrentHashMap<String, Long>()
        val items = ConcurrentHashMap<String, DeliveryItem>()

        override suspend fun insert(item: DeliveryItem) {
            insertOrder.putIfAbsent(item.deliveryId, seqCounter.incrementAndGet())
            items[item.deliveryId] = item
        }

        override suspend fun getPendingItems(): List<DeliveryItem> {
            val now = System.currentTimeMillis()
            return items.values
                .filter {
                    (it.status == DeliveryStatus.QUEUED ||
                     it.status == DeliveryStatus.RETRY_WAIT ||
                     it.status == DeliveryStatus.TRANSMITTING ||
                     it.status == DeliveryStatus.TRANSPORT_ACCEPTED) &&
                    it.nextAttemptAt <= now
                }
                .sortedWith(
                    compareByDescending<DeliveryItem> { it.priority }
                        .thenBy { insertOrder[it.deliveryId] ?: 0L }
                        .thenBy { it.createdAt }
                )
        }

        override suspend fun updateStatus(deliveryId: String, status: DeliveryStatus) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(status = status, updatedAt = System.currentTimeMillis()) }
        }

        override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(attemptCount = attemptCount, nextAttemptAt = nextAttemptAt, updatedAt = System.currentTimeMillis()) }
        }

        override suspend fun removeByMessageId(logicalMessageId: String) {
            val keys = items.values.filter { it.logicalMessageId == logicalMessageId }.map { it.deliveryId }
            for (key in keys) {
                items.remove(key)
                insertOrder.remove(key)
            }
        }
    }

    class InMemoryProcessedStore : ProcessedEnvelopeStore, ProcessedEnvelopeDao {
        val records = ConcurrentHashMap<String, ProcessedEnvelopeEntity>()
        val envelopes: MutableSet<String> get() = records.keys

        override suspend fun isProcessed(envelopeId: String): Boolean = records.containsKey(envelopeId)
        override suspend fun isMessageProcessed(logicalMessageId: String): Boolean = records.values.any { it.logicalMessageId == logicalMessageId }
        override suspend fun markProcessed(record: ProcessedEnvelope) { records[record.envelopeId] = ProcessedEnvelopeEntity(record.envelopeId, record.logicalMessageId, System.currentTimeMillis()) }
        override suspend fun insert(entity: ProcessedEnvelopeEntity) { records[entity.envelopeId] = entity }
        override suspend fun getByEnvelopeId(envelopeId: String): ProcessedEnvelopeEntity? = records[envelopeId]
        override suspend fun pruneOlderThan(before: Long) {}
    }

    class InMemorySessionStore : SessionStore {
        val sessions = ConcurrentHashMap<String, SessionState>()
        override suspend fun loadSession(relationshipId: String): SessionState? = sessions[relationshipId]?.copyState()
        override suspend fun saveSession(state: SessionState) { sessions[state.relationshipId] = state.copyState() }
        override suspend fun deleteSession(relationshipId: String) { sessions.remove(relationshipId) }
    }

    class InMemoryMessageDao : MessageDao {
        val messages = ConcurrentHashMap<String, MessageEntity>()
        override fun observeByConversation(conversationId: String): Flow<List<MessageEntity>> =
            flowOf(messages.values.filter { it.conversationId == conversationId })
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
        override suspend fun markOutgoingReadUpTo(conversationId: String, upToCreatedAt: Long, status: String, readAt: Long) {
            for ((id, msg) in messages) {
                if (msg.conversationId == conversationId && msg.direction == MessageDirection.OUTGOING && msg.createdAt <= upToCreatedAt) {
                    messages[id] = msg.copy(status = status, readAt = readAt)
                }
            }
        }
        override suspend fun markAllIncomingRead(conversationId: String, status: String, readAt: Long) {
            for ((id, msg) in messages) {
                if (msg.conversationId == conversationId && msg.direction == MessageDirection.INCOMING) {
                    messages[id] = msg.copy(status = status, readAt = readAt)
                }
            }
        }
        override suspend fun getLatestUnreadIncoming(conversationId: String): MessageEntity? {
            return messages.values
                .filter { it.conversationId == conversationId && it.direction == MessageDirection.INCOMING && it.status != "READ" }
                .maxByOrNull { it.createdAt }
        }
        override suspend fun updateBodyAndEdit(messageId: String, newBody: String, editVersion: Int, editedAt: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(body = newBody, editVersion = editVersion, editedAt = editedAt) }
        }
        override suspend fun markDeleted(messageId: String, deletedAt: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(body = null, deletedAt = deletedAt) }
        }
        override suspend fun getMessagesForConversationDesc(conversationId: String): List<MessageEntity> {
            return messages.values
                .filter { it.conversationId == conversationId }
                .sortedByDescending { it.createdAt }
        }
        override suspend fun deleteByConversation(conversationId: String) {
            messages.entries.removeIf { it.value.conversationId == conversationId }
        }
    }

    class InMemoryConversationDao : ConversationDao {
        val convs = ConcurrentHashMap<String, ConversationEntity>()
        override fun observeAll(): Flow<List<ConversationEntity>> = flowOf(convs.values.toList())
        override fun observeActive(): Flow<List<ConversationEntity>> =
            flowOf(convs.values.filter { !it.isArchived }.sortedWith(
                compareByDescending<ConversationEntity> { it.isPinned }
                    .thenByDescending { it.pinnedAt ?: 0L }
                    .thenByDescending { it.lastMessageTime ?: 0L }
            ))
        override fun observeArchived(): Flow<List<ConversationEntity>> =
            flowOf(convs.values.filter { it.isArchived }.sortedWith(
                compareByDescending<ConversationEntity> { it.archivedAt ?: 0L }
                    .thenByDescending { it.lastMessageTime ?: 0L }
            ))
        override fun observeArchivedCount(): Flow<Int> =
            flowOf(convs.values.count { it.isArchived })
        override suspend fun getById(id: String): ConversationEntity? = convs[id]
        override fun observeById(id: String): Flow<ConversationEntity?> = flowOf(convs[id])
        override suspend fun upsert(conversation: ConversationEntity) { convs[conversation.conversationId] = conversation }
        override suspend fun updateUnreadCount(id: String, count: Int) {
            convs[id]?.let { convs[id] = it.copy(unreadCount = count) }
        }
        override suspend fun updateManuallyUnread(id: String, manuallyUnread: Boolean) {
            convs[id]?.let { convs[id] = it.copy(manuallyUnread = manuallyUnread) }
        }
        override suspend fun updateLastMessage(conversationId: String, messageId: String, preview: String?, time: Long) {
            convs[conversationId]?.let { convs[conversationId] = it.copy(lastMessageId = messageId, lastMessagePreview = preview, lastMessageTime = time) }
        }
        override suspend fun updateLastMessagePreviewIfLatest(messageId: String, preview: String?) {
            for ((id, conv) in convs) {
                if (conv.lastMessageId == messageId) {
                    convs[id] = conv.copy(lastMessagePreview = preview)
                }
            }
        }
        override suspend fun setPinned(id: String, isPinned: Boolean, pinnedAt: Long?) {
            convs[id]?.let { convs[id] = it.copy(isPinned = isPinned, pinnedAt = pinnedAt) }
        }
        override suspend fun setArchived(id: String, isArchived: Boolean, archivedAt: Long?) {
            convs[id]?.let { convs[id] = it.copy(isArchived = isArchived, archivedAt = archivedAt) }
        }
        override suspend fun unarchive(id: String) {
            convs[id]?.let { convs[id] = it.copy(isArchived = false, archivedAt = null) }
        }
        override suspend fun setMutedUntil(id: String, mutedUntil: Long?) {
            convs[id]?.let { convs[id] = it.copy(mutedUntil = mutedUntil) }
        }
        override suspend fun deleteById(id: String) {
            convs.remove(id)
        }
        override fun searchConversations(query: String): Flow<List<ConversationEntity>> {
            val q = query.trim().lowercase()
            return flowOf(convs.values.filter {
                it.title?.lowercase()?.contains(q) == true || (it.lastMessagePreview?.lowercase()?.contains(q) == true)
            })
        }
    }

    class InMemoryOutboxDao(val store: InMemoryOutboxStore) : OutboxDao {
        override suspend fun getPending(now: Long): List<OutboxEntity> {
            return store.getPendingItems().map {
                OutboxEntity(
                    deliveryId = it.deliveryId,
                    logicalMessageId = it.logicalMessageId,
                    conversationId = it.conversationId,
                    connectionId = it.connectionId,
                    queueAddress = it.queueAddress,
                    ciphertext = it.ciphertext,
                    queueAuthenticator = it.queueAuthenticator,
                    status = it.status.name,
                    priority = it.priority,
                    attemptCount = it.attemptCount,
                    nextAttemptAt = it.nextAttemptAt,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    expectsAck = it.expectsAck
                )
            }
        }
        override suspend fun insert(item: OutboxEntity) {
            store.insert(DeliveryItem(
                deliveryId = item.deliveryId,
                logicalMessageId = item.logicalMessageId,
                conversationId = item.conversationId,
                connectionId = item.connectionId,
                queueAddress = item.queueAddress,
                ciphertext = item.ciphertext,
                queueAuthenticator = item.queueAuthenticator,
                status = DeliveryStatus.valueOf(item.status),
                priority = item.priority,
                attemptCount = item.attemptCount,
                nextAttemptAt = item.nextAttemptAt,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                expectsAck = item.expectsAck
            ))
        }
        override suspend fun updateStatus(deliveryId: String, status: String, now: Long) {
            store.updateStatus(deliveryId, DeliveryStatus.valueOf(status))
        }
        override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long, now: Long) {
            store.updateRetry(deliveryId, attemptCount, nextAttemptAt)
        }
        override suspend fun removeByMessageId(logicalMessageId: String) {
            store.removeByMessageId(logicalMessageId)
        }
    }

    class InMemoryConnectionDao : ConnectionDao {
        val connections = ConcurrentHashMap<String, ConnectionDbEntity>()
        override suspend fun getByRelationshipId(relationshipId: String): ConnectionDbEntity? =
            connections.values.find { it.relationshipId == relationshipId }
        override suspend fun getByRecvQueue(recvQueueId: String): ConnectionDbEntity? =
            connections.values.find { it.recvQueueId == recvQueueId }
        override suspend fun getBySendQueue(sendQueueId: String): ConnectionDbEntity? =
            connections.values.find { it.sendQueueId == sendQueueId }
        override suspend fun getAllActive(): List<ConnectionDbEntity> =
            connections.values.filter { it.state == "ACTIVE" }
        override suspend fun upsert(connection: ConnectionDbEntity) {
            connections[connection.connectionId] = connection
        }
        override suspend fun updateState(connectionId: String, state: String) {
            connections[connectionId]?.let { connections[connectionId] = it.copy(state = state) }
        }
        override suspend fun updateSendSequence(relationshipId: String, sendSequence: Long) {
            val conn = connections.values.find { it.relationshipId == relationshipId }
            if (conn != null) connections[conn.connectionId] = conn.copy(sendSequence = sendSequence)
        }
        override suspend fun updateRecvSequence(relationshipId: String, recvSequence: Long) {
            val conn = connections.values.find { it.relationshipId == relationshipId }
            if (conn != null) connections[conn.connectionId] = conn.copy(recvSequence = recvSequence)
        }
    }

    class InMemoryContactDao : ContactDao {
        val contacts = ConcurrentHashMap<String, ContactEntity>()
        override fun observeAll(): Flow<List<ContactEntity>> = flowOf(contacts.values.toList())
        override suspend fun getById(id: String): ContactEntity? = contacts[id]
        override suspend fun getByRelationshipId(relationshipId: String): ContactEntity? =
            contacts.values.find { it.relationshipId == relationshipId }
        override suspend fun getByConversationId(conversationId: String): ContactEntity? =
            contacts.values.find { it.conversationId == conversationId }
        override suspend fun getAll(): List<ContactEntity> = contacts.values.toList()
        override suspend fun upsert(contact: ContactEntity) {
            contacts[contact.contactId] = contact
        }
    }

    class InMemoryPendingInviteDao : PendingInviteDao {
        val invites = ConcurrentHashMap<String, PendingInviteEntity>()
        override suspend fun getById(inviteId: String): PendingInviteEntity? = invites[inviteId]
        override suspend fun getByPublicKey(publicKey: ByteArray): PendingInviteEntity? =
            invites.values.find { java.util.Arrays.equals(it.ephemeralPublicKey, publicKey) }
        override suspend fun getLatest(): PendingInviteEntity? =
            invites.values.maxByOrNull { it.createdAt }
        override suspend fun insert(invite: PendingInviteEntity) {
            invites[invite.inviteId] = invite
        }
        override suspend fun delete(inviteId: String) {
            invites.remove(inviteId)
        }
    }

    class InMemoryIdentityRepository(
        val identity: TorXIdentity,
        val pendingInviteDao: PendingInviteDao
    ) : IdentityRepository {
        override suspend fun createIdentity(displayName: String): TorXIdentity = identity
        override suspend fun loadIdentity(): TorXIdentity? = identity
        override suspend fun sign(data: ByteArray): ByteArray =
            IdentityCrypto.signEd25519(identity.signingPrivateKey, data)
        override suspend fun createContactInvite(): ContactInviteV1 {
            val ephemeralBootstrapPair = IdentityCrypto.generateX25519KeyPair()
            val inviteId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val expiresAt = now + (7 * 24 * 60 * 60 * 1000L)
            val signedData = ContactInviteCodec.serializeForSigning(
                protocolVersion = 1,
                inviteId = inviteId,
                identityId = identity.identityId,
                displayName = identity.displayName,
                signingPublicKey = identity.signingPublicKey,
                encryptionPublicKey = identity.encryptionPublicKey,
                bootstrapEphemeralPublicKey = ephemeralBootstrapPair.publicKey,
                createdAt = now,
                expiresAt = expiresAt
            )
            val signature = IdentityCrypto.signEd25519(identity.signingPrivateKey, signedData)
            pendingInviteDao.insert(
                PendingInviteEntity(
                    inviteId = inviteId,
                    ephemeralPublicKey = ephemeralBootstrapPair.publicKey,
                    ephemeralPrivateKey = ephemeralBootstrapPair.privateKey,
                    createdAt = now,
                    expiresAt = expiresAt
                )
            )
            return ContactInviteV1(
                protocolVersion = 1,
                inviteId = inviteId,
                identityId = identity.identityId,
                displayName = identity.displayName,
                identitySigningPublicKey = identity.signingPublicKey,
                identityEncryptionPublicKey = identity.encryptionPublicKey,
                bootstrapEphemeralPublicKey = ephemeralBootstrapPair.publicKey,
                createdAt = now,
                expiresAt = expiresAt,
                signature = signature
            )
        }
        override suspend fun getPendingInviteEphemeralPrivateKey(inviteId: String): ByteArray? =
            pendingInviteDao.getById(inviteId)?.ephemeralPrivateKey
    }

    class Node(val name: String) {
        val identity: TorXIdentity
        val messageDao = InMemoryMessageDao()
        val conversationDao = InMemoryConversationDao()
        val outboxStore = InMemoryOutboxStore()
        val outboxDao = InMemoryOutboxDao(outboxStore)
        val processedDao = InMemoryProcessedStore()
        val sessionStore = InMemorySessionStore()
        val sessionCrypto = DoubleRatchetSessionCrypto(sessionStore)
        val connectionDao = InMemoryConnectionDao()
        val contactDao = InMemoryContactDao()
        val pendingInviteDao = InMemoryPendingInviteDao()
        val identityRepo: InMemoryIdentityRepository
        val connectionManager = ConnectionManager()
        val activeTracker = ActiveConversationTracker()
        val transportRouter = TransportRouter()
        val fakeTransport = FakeTransport()
        val agent: TorXAgent
        val chatReceiver: ChatReceiver
        val receiptHandler: DeliveryReceiptHandler
        val dispatcher: IncomingDispatcher
        val incomingHub: IncomingTransportHub

        init {
            val signPair = IdentityCrypto.generateEd25519KeyPair()
            val encPair = IdentityCrypto.generateX25519KeyPair()
            identity = TorXIdentity(
                identityId = UUID.randomUUID().toString(),
                signingPublicKey = signPair.publicKey,
                signingPrivateKey = signPair.privateKey,
                encryptionPublicKey = encPair.publicKey,
                encryptionPrivateKey = encPair.privateKey,
                displayName = name
            )
            identityRepo = InMemoryIdentityRepository(identity, pendingInviteDao)
            agent = TorXAgent(
                transportRouter = transportRouter,
                outboxStore = outboxStore,
                processedStore = processedDao,
                coroutineDispatcher = Dispatchers.Default,
                baseRetryDelayMs = 200L,
                outboxPollIntervalMs = 50L
            )
            chatReceiver = ChatReceiver(messageDao, conversationDao, activeTracker)
            receiptHandler = DeliveryReceiptHandler(messageDao, outboxDao, agent)
            dispatcher = IncomingDispatcher(
                connectionManager = connectionManager,
                sessionCrypto = sessionCrypto,
                processedEnvelopeDao = processedDao,
                chatReceiver = chatReceiver,
                deliveryReceiptHandler = receiptHandler,
                agent = agent,
                localIdentityIdProvider = { identity.identityId },
                pendingInviteDao = pendingInviteDao,
                identityRepository = identityRepo,
                connectionDao = connectionDao,
                contactDao = contactDao,
                conversationDao = conversationDao
            )
            incomingHub = IncomingTransportHub(dispatcher)
            transportRouter.registerTransport(fakeTransport)
            agent.start()
        }

        fun stop() {
            agent.stop()
        }
    }

    private suspend fun waitFor(timeoutMs: Long = 5000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }

    private suspend fun setupAliceAndBobNodes(): Pair<Node, Node> {
        val alice = Node("Alice")
        val bob = Node("Bob")

        // Connect fake transports bidirectionally
        alice.fakeTransport.peerHub = bob.incomingHub
        bob.fakeTransport.peerHub = alice.incomingHub

        val relationshipId = "rel-alice-bob"
        val pairRootSecret = IdentityCrypto.generateX25519KeyPair().privateKey
        val secrets = RelationshipService.deriveSecrets(pairRootSecret)

        val (aToBQueue, bToAQueue, aSendAuth, bSendAuth) = RelationshipService.deriveDirectionalQueues(
            secrets.queueBootstrapSecret,
            alice.identity.signingPublicKey,
            bob.identity.signingPublicKey
        )

        // Register connections
        val aliceConn = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = aToBQueue,
            recvQueueId = bToAQueue,
            sendAuth = aSendAuth,
            recvAuth = bSendAuth
        )
        alice.connectionManager.registerConnection(aliceConn)
        alice.connectionDao.upsert(
            ConnectionDbEntity(
                connectionId = aliceConn.connectionId,
                relationshipId = aliceConn.relationshipId,
                generation = aliceConn.generation,
                sendQueueId = aliceConn.sendQueueId,
                recvQueueId = aliceConn.recvQueueId,
                sendAuth = aliceConn.sendAuth,
                recvAuth = aliceConn.recvAuth,
                state = "ACTIVE"
            )
        )

        val bobConn = Connection(
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = bToAQueue,
            recvQueueId = aToBQueue,
            sendAuth = bSendAuth,
            recvAuth = aSendAuth
        )
        bob.connectionManager.registerConnection(bobConn)
        bob.connectionDao.upsert(
            ConnectionDbEntity(
                connectionId = bobConn.connectionId,
                relationshipId = bobConn.relationshipId,
                generation = bobConn.generation,
                sendQueueId = bobConn.sendQueueId,
                recvQueueId = bobConn.recvQueueId,
                sendAuth = bobConn.sendAuth,
                recvAuth = bobConn.recvAuth,
                state = "ACTIVE"
            )
        )

        // Initialize Double Ratchet sessions
        val aliceRatchet = IdentityCrypto.generateX25519KeyPair()
        val bobRatchet = IdentityCrypto.generateX25519KeyPair()

        alice.sessionCrypto.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = secrets.sessionInitializationSecret,
            isInitiator = true,
            remoteRatchetPublicKey = bobRatchet.publicKey,
            localRatchetPrivateKey = aliceRatchet.privateKey,
            localRatchetPublicKey = aliceRatchet.publicKey
        )

        bob.sessionCrypto.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = secrets.sessionInitializationSecret,
            isInitiator = false,
            remoteRatchetPublicKey = aliceRatchet.publicKey,
            localRatchetPrivateKey = bobRatchet.privateKey,
            localRatchetPublicKey = bobRatchet.publicKey
        )

        return alice to bob
    }

    @Test
    fun testGoldenPathAliceSendsBobReceivesBobAcksAliceMarkedDelivered() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()

        val conversationId = "conv-1"
        val relationshipId = "rel-alice-bob"
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 1. Alice creates and encrypts message
        val seq = alice.connectionManager.incrementSendSequence(relationshipId)
        val envelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderIdentity = alice.identity.identityId,
            recipientBinding = bob.identity.identityId,
            directionSequence = seq,
            messageType = MessageType.TEXT,
            timestamp = now,
            payload = "Hello Bob!".toByteArray(Charsets.UTF_8)
        )
        val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
        val conn = alice.connectionManager.getConnectionByRelationship(relationshipId)!!
        val aad = "torx-aad-v1:${conn.generation}:${conn.sendQueueId}".toByteArray(Charsets.UTF_8)

        val encryptedMsg = alice.sessionCrypto.encrypt(relationshipId, envelopeBytes, aad)

        val messageEntity = MessageEntity(
            logicalMessageId = messageId,
            conversationId = conversationId,
            senderId = alice.identity.identityId,
            type = MessageType.TEXT.name,
            body = "Hello Bob!",
            direction = com.torxone.app.data.entity.MessageDirection.OUTGOING,
            status = DeliveryStatus.QUEUED.name,
            createdAt = now
        )
        alice.messageDao.insertIfAbsent(messageEntity)

        val deliveryItem = DeliveryItem(
            deliveryId = UUID.randomUUID().toString(),
            logicalMessageId = messageId,
            conversationId = conversationId,
            connectionId = conn.connectionId,
            queueAddress = conn.sendQueueId,
            ciphertext = encryptedMsg.serialize(),
            queueAuthenticator = conn.sendAuth,
            status = DeliveryStatus.QUEUED
        )

        // 2. Alice enqueues in TorXAgent
        alice.agent.enqueue(deliveryItem)

        // 3. Wait for Bob to receive and persist message
        waitFor { bob.messageDao.exists(messageId) }
        val bobMsg = bob.messageDao.getById(messageId)
        assertNotNull("Bob must have received and persisted the message", bobMsg)
        assertEquals("Hello Bob!", bobMsg!!.body)

        // 4. Verify Bob recorded dedup state
        waitFor { bob.processedDao.envelopes.isNotEmpty() }
        assertTrue("Bob must have marked envelope in processed table", bob.processedDao.envelopes.isNotEmpty())

        // 5. Verify Alice received Bob's authenticated ACK and marked message DELIVERED!
        waitFor { alice.messageDao.getById(messageId)?.status == DeliveryStatus.DELIVERED.name }
        val aliceMsg = alice.messageDao.getById(messageId)
        assertNotNull(aliceMsg)
        assertEquals("Alice message status must be DELIVERED (✓✓)", DeliveryStatus.DELIVERED.name, aliceMsg!!.status)
        assertNotNull("DeliveredAt timestamp must be recorded", aliceMsg.deliveredAt)

        alice.stop()
        bob.stop()
    }

    @Test
    fun testDuplicatePacketInjectionDeduplication() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()

        // Enable duplicate packet delivery on FakeTransport
        alice.fakeTransport.duplicateDelivery = true

        val conversationId = "conv-1"
        val relationshipId = "rel-alice-bob"
        val conn = alice.connectionManager.getConnectionByRelationship(relationshipId)!!

        for (i in 1..20) {
            val msgId = "dup-msg-$i"
            val seq = alice.connectionManager.incrementSendSequence(relationshipId)
            val env = SecureEnvelope(
                protocolVersion = 1,
                logicalMessageId = msgId,
                conversationId = conversationId,
                senderIdentity = alice.identity.identityId,
                recipientBinding = bob.identity.identityId,
                directionSequence = seq,
                messageType = MessageType.TEXT,
                payload = "Message $i".toByteArray()
            )
            val envBytes = ProtocolCodec.encodeSecureEnvelope(env)
            val aad = "torx-aad-v1:${conn.generation}:${conn.sendQueueId}".toByteArray()
            val enc = alice.sessionCrypto.encrypt(relationshipId, envBytes, aad)

            alice.messageDao.insertIfAbsent(MessageEntity(
                logicalMessageId = msgId,
                conversationId = conversationId,
                senderId = alice.identity.identityId,
                type = "TEXT",
                body = "Message $i",
                direction = com.torxone.app.data.entity.MessageDirection.OUTGOING,
                status = "QUEUED"
            ))

            alice.agent.enqueue(DeliveryItem(
                deliveryId = "dup-env-$i",
                logicalMessageId = msgId,
                conversationId = conversationId,
                connectionId = conn.connectionId,
                queueAddress = conn.sendQueueId,
                ciphertext = enc.serialize(),
                queueAuthenticator = conn.sendAuth
            ))
        }

        // Wait for all 20 messages to arrive at Bob
        waitFor(10000) { bob.messageDao.messages.size == 20 }

        // Bob should have EXACTLY 20 messages, despite receiving 40 packets on the wire!
        assertEquals("Bob should have exactly 20 messages with 0 duplicates", 20, bob.messageDao.messages.size)

        alice.stop()
        bob.stop()
    }

    private suspend fun sendMessage(
        sender: Node,
        recipient: Node,
        relationshipId: String,
        conversationId: String,
        msgId: String,
        text: String
    ) {
        val now = System.currentTimeMillis()
        val seq = sender.connectionManager.incrementSendSequence(relationshipId)
        val envelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = msgId,
            conversationId = conversationId,
            senderIdentity = sender.identity.identityId,
            recipientBinding = recipient.identity.identityId,
            directionSequence = seq,
            messageType = MessageType.TEXT,
            timestamp = now,
            payload = text.toByteArray(Charsets.UTF_8)
        )
        val envelopeBytes = ProtocolCodec.encodeSecureEnvelope(envelope)
        val conn = sender.connectionManager.getConnectionByRelationship(relationshipId)!!
        val aad = "torx-aad-v1:${conn.generation}:${conn.sendQueueId}".toByteArray(Charsets.UTF_8)
        val encryptedMsg = sender.sessionCrypto.encrypt(relationshipId, envelopeBytes, aad)

        sender.messageDao.insertIfAbsent(MessageEntity(
            logicalMessageId = msgId,
            conversationId = conversationId,
            senderId = sender.identity.identityId,
            type = MessageType.TEXT.name,
            body = text,
            direction = com.torxone.app.data.entity.MessageDirection.OUTGOING,
            status = DeliveryStatus.QUEUED.name,
            createdAt = now
        ))

        val deliveryItem = DeliveryItem(
            deliveryId = UUID.randomUUID().toString(),
            logicalMessageId = msgId,
            conversationId = conversationId,
            connectionId = conn.connectionId,
            queueAddress = conn.sendQueueId,
            ciphertext = encryptedMsg.serialize(),
            queueAuthenticator = conn.sendAuth,
            status = DeliveryStatus.QUEUED
        )
        sender.agent.enqueue(deliveryItem)
    }

    @Test
    fun testBidirectionalSimultaneousMessaging() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()
        val relationshipId = "rel-alice-bob"

        // Both nodes start sending concurrently
        val jobA = launch(Dispatchers.Default) {
            for (i in 1..10) {
                sendMessage(alice, bob, relationshipId, "conv-ab", "a-to-b-$i", "Alice message $i")
            }
        }
        val jobB = launch(Dispatchers.Default) {
            for (i in 1..10) {
                sendMessage(bob, alice, relationshipId, "conv-ba", "b-to-a-$i", "Bob message $i")
            }
        }
        jobA.join()
        jobB.join()

        // Wait for all 10 messages from Alice to arrive at Bob
        waitFor(10000) {
            bob.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING } == 10
        }
        // Wait for all 10 messages from Bob to arrive at Alice
        waitFor(10000) {
            alice.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING } == 10
        }

        // Wait for authenticated ACKs to confirm delivery on both sides (DELIVERED status = ✓✓)
        waitFor(10000) {
            alice.messageDao.messages.values.filter { it.direction == com.torxone.app.data.entity.MessageDirection.OUTGOING }
                .all { it.status == DeliveryStatus.DELIVERED.name } &&
            bob.messageDao.messages.values.filter { it.direction == com.torxone.app.data.entity.MessageDirection.OUTGOING }
                .all { it.status == DeliveryStatus.DELIVERED.name }
        }

        assertEquals(10, bob.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING })
        assertEquals(10, alice.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING })

        alice.stop()
        bob.stop()
    }

    @Test
    fun testAckLossAndRetryReliability() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()
        val relationshipId = "rel-alice-bob"

        // Inject 30% packet loss on Bob's outgoing transport (dropping 30% of ACKs)
        bob.fakeTransport.packetLossRate = 0.3

        for (i in 1..10) {
            sendMessage(alice, bob, relationshipId, "conv-ab", "loss-msg-$i", "Reliable test $i")
        }

        // Even with 30% ACK drops, Alice's retry engine and Bob's deduplication table ensure:
        // 1. Bob persists each logical message exactly once (no duplicates)
        // 2. Alice eventually receives ACKs for all 10 messages and marks them DELIVERED
        waitFor(15000) {
            alice.messageDao.messages.values.all { it.status == DeliveryStatus.DELIVERED.name }
        }

        assertEquals(10, bob.messageDao.messages.size)
        assertTrue(alice.messageDao.messages.values.all { it.status == DeliveryStatus.DELIVERED.name })

        alice.stop()
        bob.stop()
    }

    @Test
    fun testMalformedAndTamperedPacketsRejected() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()
        val relationshipId = "rel-alice-bob"
        val conn = alice.connectionManager.getConnectionByRelationship(relationshipId)!!

        // 1. Send truncated/malformed frame (< 8 bytes)
        val shortFrame = byteArrayOf(0x01, 0x02, 0x03)
        bob.incomingHub.onRawFrameReceived(shortFrame, TransportType.FAKE)

        // 2. Send unknown queue address
        val unknownQueueEnv = com.torxone.app.protocol.OpaqueTransportEnvelope(
            version = 1,
            envelopeId = "bad-env-1",
            queueAddress = "non-existent-queue",
            opaqueCiphertext = byteArrayOf(0x01, 0x02),
            queueAuthenticator = ByteArray(32)
        )
        bob.incomingHub.onRawFrameReceived(
            ProtocolCodec.encodeTransportEnvelope(unknownQueueEnv),
            TransportType.FAKE
        )

        // 3. Send invalid capability authenticator (bad HMAC)
        val badAuthEnv = com.torxone.app.protocol.OpaqueTransportEnvelope(
            version = 1,
            envelopeId = "bad-env-2",
            queueAddress = conn.sendQueueId, // destination queue is valid
            opaqueCiphertext = byteArrayOf(0x01, 0x02),
            queueAuthenticator = ByteArray(32) { 0xFF.toByte() } // wrong HMAC
        )
        bob.incomingHub.onRawFrameReceived(
            ProtocolCodec.encodeTransportEnvelope(badAuthEnv),
            TransportType.FAKE
        )

        // 4. Send corrupted ciphertext (valid queue and auth, but corrupted encrypted payload)
        val badCiphertextEnv = com.torxone.app.protocol.OpaqueTransportEnvelope(
            version = 1,
            envelopeId = "bad-env-3",
            queueAddress = conn.sendQueueId,
            opaqueCiphertext = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            queueAuthenticator = conn.sendAuth
        )
        bob.incomingHub.onRawFrameReceived(
            ProtocolCodec.encodeTransportEnvelope(badCiphertextEnv),
            TransportType.FAKE
        )

        // Verify: None of the above invalid/tampered packets were accepted into Bob's message database!
        delay(100)
        assertEquals("Bob must not persist any messages from malformed or tampered packets", 0, bob.messageDao.messages.size)

        alice.stop()
        bob.stop()
    }

    @Test
    fun testRealQrBilateralBootstrapAndMessaging() = runBlocking {
        val alice = Node("Alice")
        val bob = Node("Bob")
        alice.fakeTransport.peerHub = bob.incomingHub
        bob.fakeTransport.peerHub = alice.incomingHub

        // 1. Bob creates contact invite (simulating QR code generation)
        val invite = bob.identityRepo.createContactInvite()
        val qrString = ContactInviteCodec.encodeToQrString(invite)
        assertNotNull(qrString)

        // 2. Alice scans Bob's QR code and decodes invite
        val scannedInvite = ContactInviteCodec.decodeFromQrString(qrString)
        assertNotNull(scannedInvite)
        val validation = ContactInviteCodec.validate(scannedInvite!!, alice.identity)
        assertTrue("Scanned invite must be valid", validation is InviteValidationResult.Valid)

        // 3. Alice establishes relationship as initiator (3DH)
        val contactId = UUID.randomUUID().toString()
        val conversationId = UUID.randomUUID().toString()
        val bootstrap = RelationshipService.establishFromInvite(
            localIdentity = alice.identity,
            invite = scannedInvite,
            contactId = contactId
        )

        val aliceConn = Connection(
            relationshipId = bootstrap.relationship.relationshipId,
            generation = 1,
            sendQueueId = bootstrap.aliceToBobQueueId,
            recvQueueId = bootstrap.bobToAliceQueueId,
            sendAuth = bootstrap.aliceSendAuth,
            recvAuth = bootstrap.bobSendAuth
        )
        alice.connectionManager.registerConnection(aliceConn)
        alice.connectionDao.upsert(
            ConnectionDbEntity(
                connectionId = aliceConn.connectionId,
                relationshipId = aliceConn.relationshipId,
                generation = aliceConn.generation,
                sendQueueId = aliceConn.sendQueueId,
                recvQueueId = aliceConn.recvQueueId,
                sendAuth = aliceConn.sendAuth,
                recvAuth = aliceConn.recvAuth,
                state = "ACTIVE"
            )
        )

        // Alice initializes Double Ratchet session using the bootstrap ephemeral keypair
        alice.sessionCrypto.initializeSession(
            relationshipId = bootstrap.relationship.relationshipId,
            sessionInitializationSecret = bootstrap.secrets.sessionInitializationSecret,
            isInitiator = true,
            remoteRatchetPublicKey = scannedInvite.bootstrapEphemeralPublicKey,
            localRatchetPrivateKey = bootstrap.aliceEphemeralPrivateKey!!,
            localRatchetPublicKey = bootstrap.aliceEphemeralPublicKey
        )

        // 4. Alice sends wire ContactBootstrapPayload to Bob over invite queue
        val bootstrapSignedData = ContactBootstrapPayload.serializeForSigning(
            inviteId = scannedInvite.inviteId,
            initiatorIdentityId = alice.identity.identityId,
            displayName = alice.identity.displayName,
            signingPub = alice.identity.signingPublicKey,
            encryptionPub = alice.identity.encryptionPublicKey,
            ephemeralPub = bootstrap.aliceEphemeralPublicKey
        )
        val bootstrapSig = IdentityCrypto.signEd25519(alice.identity.signingPrivateKey, bootstrapSignedData)
        val bootstrapWire = ContactBootstrapPayload(
            inviteId = scannedInvite.inviteId,
            initiatorIdentityId = alice.identity.identityId,
            initiatorDisplayName = alice.identity.displayName,
            initiatorSigningPublicKey = alice.identity.signingPublicKey,
            initiatorEncryptionPublicKey = alice.identity.encryptionPublicKey,
            initiatorEphemeralPublicKey = bootstrap.aliceEphemeralPublicKey,
            signature = bootstrapSig
        )

        alice.agent.enqueue(
            DeliveryItem(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                connectionId = aliceConn.connectionId,
                queueAddress = "invite-${scannedInvite.inviteId}",
                ciphertext = bootstrapWire.toByteArray(),
                queueAuthenticator = ByteArray(0),
                status = DeliveryStatus.QUEUED,
                priority = DeliveryPriority.HIGH
            )
        )

        // 5. Wait for Bob's Stage 3 incoming dispatcher to process bootstrap and establish responder relationship
        val relationshipId = bootstrap.relationship.relationshipId
        waitFor(5000) {
            bob.connectionManager.getConnectionByRelationship(relationshipId) != null
        }
        val bobConn = bob.connectionManager.getConnectionByRelationship(relationshipId)
        assertNotNull("Bob must have registered matching connection", bobConn)
        assertEquals("Bob's send queue must equal Alice's recv queue", aliceConn.recvQueueId, bobConn!!.sendQueueId)
        assertEquals("Bob's recv queue must equal Alice's send queue", aliceConn.sendQueueId, bobConn.recvQueueId)

        // 6. Alice sends first message to Bob
        val msgAliceToBobId = "msg-alice-1"
        sendMessage(alice, bob, relationshipId, conversationId, msgAliceToBobId, "Hello Bob from QR!")

        waitFor(5000) { bob.messageDao.exists(msgAliceToBobId) }
        val receivedByBob = bob.messageDao.getById(msgAliceToBobId)
        assertNotNull("Bob must receive Alice's message", receivedByBob)
        assertEquals("Hello Bob from QR!", receivedByBob!!.body)

        // Alice receives Bob's ACK
        waitFor(5000) { alice.messageDao.getById(msgAliceToBobId)?.status == DeliveryStatus.DELIVERED.name }
        assertEquals(DeliveryStatus.DELIVERED.name, alice.messageDao.getById(msgAliceToBobId)?.status)

        // 7. Bob sends reply to Alice
        val msgBobToAliceId = "msg-bob-1"
        sendMessage(bob, alice, relationshipId, conversationId, msgBobToAliceId, "Hello Alice, responder online!")

        waitFor(5000) { alice.messageDao.exists(msgBobToAliceId) }
        val receivedByAlice = alice.messageDao.getById(msgBobToAliceId)
        assertNotNull("Alice must receive Bob's message", receivedByAlice)
        assertEquals("Hello Alice, responder online!", receivedByAlice!!.body)

        // Bob receives Alice's ACK
        waitFor(5000) { bob.messageDao.getById(msgBobToAliceId)?.status == DeliveryStatus.DELIVERED.name }
        assertEquals(DeliveryStatus.DELIVERED.name, bob.messageDao.getById(msgBobToAliceId)?.status)

        alice.stop()
        bob.stop()
    }

    @Test
    fun testSimultaneousDuplexStress() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()
        val relationshipId = "rel-alice-bob"
        val messageCount = 50

        val jobAlice = launch(Dispatchers.Default) {
            for (i in 1..messageCount) {
                sendMessage(alice, bob, relationshipId, "conv-ab", "stress-a-$i", "Stress message A $i")
            }
        }
        val jobBob = launch(Dispatchers.Default) {
            for (i in 1..messageCount) {
                sendMessage(bob, alice, relationshipId, "conv-ba", "stress-b-$i", "Stress message B $i")
            }
        }
        joinAll(jobAlice, jobBob)

        // Wait for all 50 messages from Alice to arrive at Bob
        waitFor(15000) {
            bob.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING } == messageCount
        }
        // Wait for all 50 messages from Bob to arrive at Alice
        waitFor(15000) {
            alice.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING } == messageCount
        }

        // Wait for all 100 messages total across both sides to be DELIVERED via ACKs
        waitFor(15000) {
            alice.messageDao.messages.values.filter { it.direction == com.torxone.app.data.entity.MessageDirection.OUTGOING }
                .all { it.status == DeliveryStatus.DELIVERED.name } &&
            bob.messageDao.messages.values.filter { it.direction == com.torxone.app.data.entity.MessageDirection.OUTGOING }
                .all { it.status == DeliveryStatus.DELIVERED.name }
        }

        assertEquals(messageCount, bob.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING })
        assertEquals(messageCount, alice.messageDao.messages.values.count { it.direction == com.torxone.app.data.entity.MessageDirection.INCOMING })

        alice.stop()
        bob.stop()
    }

    @Test
    fun testProcessRestartAndSessionRecovery() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()
        val relationshipId = "rel-alice-bob"

        // 1. Initial message before restart
        sendMessage(alice, bob, relationshipId, "conv-ab", "pre-restart-1", "Message before restart")
        waitFor(5000) { bob.messageDao.exists("pre-restart-1") }
        waitFor(5000) { alice.messageDao.getById("pre-restart-1")?.status == DeliveryStatus.DELIVERED.name }

        // 2. Simulate Bob killing app and restarting
        bob.agent.stop()

        // Create fresh ConnectionManager and restore from database
        val newBobConnectionManager = ConnectionManager()
        newBobConnectionManager.restoreFromDatabase(bob.connectionDao)

        // Create fresh SessionCrypto using the persisted SessionStore
        val newBobSessionCrypto = DoubleRatchetSessionCrypto(bob.sessionStore)

        // Re-wire Bob's incoming pipeline with restored managers
        val newBobDispatcher = IncomingDispatcher(
            connectionManager = newBobConnectionManager,
            sessionCrypto = newBobSessionCrypto,
            processedEnvelopeDao = bob.processedDao,
            chatReceiver = bob.chatReceiver,
            deliveryReceiptHandler = bob.receiptHandler,
            agent = bob.agent,
            localIdentityIdProvider = { bob.identity.identityId },
            pendingInviteDao = bob.pendingInviteDao,
            identityRepository = bob.identityRepo,
            connectionDao = bob.connectionDao,
            contactDao = bob.contactDao,
            conversationDao = bob.conversationDao
        )
        bob.incomingHub.dispatcher = newBobDispatcher
        bob.agent.start()

        // 3. Alice sends message after Bob's restart
        sendMessage(alice, bob, relationshipId, "conv-ab", "post-restart-1", "Message after Bob restarted")
        waitFor(5000) { bob.messageDao.exists("post-restart-1") }
        assertEquals("Message after Bob restarted", bob.messageDao.getById("post-restart-1")?.body)

        waitFor(5000) { alice.messageDao.getById("post-restart-1")?.status == DeliveryStatus.DELIVERED.name }

        // 4. Bob sends message to Alice using restored state
        val now = System.currentTimeMillis()
        val env = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = "bob-post-restart",
            conversationId = "conv-ba",
            senderIdentity = bob.identity.identityId,
            recipientBinding = alice.identity.identityId,
            directionSequence = newBobConnectionManager.incrementSendSequence(relationshipId),
            messageType = MessageType.TEXT,
            timestamp = now,
            payload = "Bob is back online!".toByteArray(Charsets.UTF_8)
        )
        val envBytes = ProtocolCodec.encodeSecureEnvelope(env)
        val conn = newBobConnectionManager.getConnectionByRelationship(relationshipId)!!
        val aad = "torx-aad-v1:${conn.generation}:${conn.sendQueueId}".toByteArray(Charsets.UTF_8)
        val enc = newBobSessionCrypto.encrypt(relationshipId, envBytes, aad)

        bob.messageDao.insertIfAbsent(MessageEntity(
            logicalMessageId = "bob-post-restart",
            conversationId = "conv-ba",
            senderId = bob.identity.identityId,
            type = "TEXT",
            body = "Bob is back online!",
            direction = com.torxone.app.data.entity.MessageDirection.OUTGOING,
            status = DeliveryStatus.QUEUED.name
        ))
        bob.agent.enqueue(DeliveryItem(
            deliveryId = UUID.randomUUID().toString(),
            logicalMessageId = "bob-post-restart",
            conversationId = "conv-ba",
            connectionId = conn.connectionId,
            queueAddress = conn.sendQueueId,
            ciphertext = enc.serialize(),
            queueAuthenticator = conn.sendAuth,
            status = DeliveryStatus.QUEUED
        ))

        waitFor(5000) { alice.messageDao.exists("bob-post-restart") }
        assertEquals("Bob is back online!", alice.messageDao.getById("bob-post-restart")?.body)
        waitFor(5000) { bob.messageDao.getById("bob-post-restart")?.status == DeliveryStatus.DELIVERED.name }

        alice.stop()
        bob.stop()
    }

    @Test
    fun testDirectionalSequencePolicyClassification() {
        // User-visible durable messages require directional sequence
        assertTrue(MessageType.TEXT.requiresApplicationSequence())
        assertTrue(MessageType.IMAGE.requiresApplicationSequence())
        assertTrue(MessageType.VIDEO.requiresApplicationSequence())
        assertTrue(MessageType.AUDIO.requiresApplicationSequence())
        assertTrue(MessageType.FILE.requiresApplicationSequence())
        assertTrue(MessageType.VOICE_NOTE.requiresApplicationSequence())
        assertTrue(MessageType.REACTION.requiresApplicationSequence())
        assertTrue(MessageType.EDIT.requiresApplicationSequence())
        assertTrue(MessageType.DELETE.requiresApplicationSequence())
        assertTrue(MessageType.GROUP_CREATE.requiresApplicationSequence())
        assertTrue(MessageType.GROUP_MEMBER_INVITE.requiresApplicationSequence())
        assertTrue(MessageType.GROUP_MEMBER_ACCEPT.requiresApplicationSequence())
        assertTrue(MessageType.GROUP_MEMBER_REMOVE.requiresApplicationSequence())
        assertTrue(MessageType.GROUP_ROLE_CHANGE.requiresApplicationSequence())
        assertTrue(MessageType.GROUP_NAME_CHANGE.requiresApplicationSequence())
        assertTrue(MessageType.GROUP_AVATAR_CHANGE.requiresApplicationSequence())
        assertTrue(MessageType.GROUP_KEY_ROTATE.requiresApplicationSequence())

        // Internal control and transfer frames are sequence-exempt
        assertFalse(MessageType.FILE_PROGRESS.requiresApplicationSequence())
        assertFalse(MessageType.FILE_COMPLETE.requiresApplicationSequence())
        assertFalse(MessageType.FILE_RESUME.requiresApplicationSequence())
        assertFalse(MessageType.FILE_CANCEL.requiresApplicationSequence())
        assertFalse(MessageType.DELIVERY_ACK.requiresApplicationSequence())
        assertFalse(MessageType.READ_RECEIPT.requiresApplicationSequence())
        assertFalse(MessageType.TYPING_START.requiresApplicationSequence())
        assertFalse(MessageType.TYPING_STOP.requiresApplicationSequence())
        assertFalse(MessageType.PRESENCE_UPDATE.requiresApplicationSequence())
    }

    @Test
    fun testNormalTextAndGroupMessageWithZeroSequenceRejected() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()
        val conversationId = "conv-seq-test"
        val relationshipId = "rel-alice-bob"
        val conn = alice.connectionManager.getConnectionByRelationship(relationshipId)!!

        // 1. Normal TEXT with zero sequence
        val envZeroText = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = "zero-seq-text",
            conversationId = conversationId,
            senderIdentity = alice.identity.identityId,
            recipientBinding = bob.identity.identityId,
            directionSequence = 0L, // INVALID for TEXT
            messageType = MessageType.TEXT,
            payload = "Rejected zero seq text".toByteArray()
        )
        val envBytes1 = ProtocolCodec.encodeSecureEnvelope(envZeroText)
        val aad1 = "torx-aad-v1:${conn.generation}:${conn.sendQueueId}".toByteArray()
        val enc1 = alice.sessionCrypto.encrypt(relationshipId, envBytes1, aad1)
        val raw1 = ProtocolCodec.encodeTransportEnvelope(
            OpaqueTransportEnvelope(
                version = 1,
                queueAddress = conn.sendQueueId,
                envelopeId = UUID.randomUUID().toString(),
                opaqueCiphertext = enc1.serialize(),
                queueAuthenticator = IdentityCrypto.computeQueueAuthenticator(conn.sendAuth, UUID.randomUUID().toString(), conn.sendQueueId, enc1.serialize())
            )
        )
        val textSuccess = bob.dispatcher.dispatch(raw1, TransportType.NEARBY)
        assertFalse("IncomingDispatcher must reject normal TEXT with zero sequence", textSuccess)
        assertNull("Bob must not persist zero-sequence message", bob.messageDao.getById("zero-seq-text"))
        assertEquals("Bob recvSequence must remain 0", 0L, bob.connectionManager.getConnectionByRelationship(relationshipId)?.recvSequence)

        // 2. GROUP_CREATE with zero sequence
        val envZeroGroup = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = "zero-seq-group",
            conversationId = "group-1",
            senderIdentity = alice.identity.identityId,
            recipientBinding = bob.identity.identityId,
            directionSequence = 0L, // INVALID for GROUP event
            messageType = MessageType.GROUP_CREATE,
            payload = "Rejected group create".toByteArray()
        )
        val envBytes2 = ProtocolCodec.encodeSecureEnvelope(envZeroGroup)
        val enc2 = alice.sessionCrypto.encrypt(relationshipId, envBytes2, aad1)
        val raw2 = ProtocolCodec.encodeTransportEnvelope(
            OpaqueTransportEnvelope(
                version = 1,
                queueAddress = conn.sendQueueId,
                envelopeId = UUID.randomUUID().toString(),
                opaqueCiphertext = enc2.serialize(),
                queueAuthenticator = IdentityCrypto.computeQueueAuthenticator(conn.sendAuth, UUID.randomUUID().toString(), conn.sendQueueId, enc2.serialize())
            )
        )
        val groupSuccess = bob.dispatcher.dispatch(raw2, TransportType.NEARBY)
        assertFalse("IncomingDispatcher must reject GROUP event with zero sequence", groupSuccess)
        assertEquals("Bob recvSequence must remain 0", 0L, bob.connectionManager.getConnectionByRelationship(relationshipId)?.recvSequence)

        alice.stop()
        bob.stop()
    }

    @Test
    fun testExemptControlsCannotAlterRecvSequenceAndNormalMonotonic() = runBlocking {
        val (alice, bob) = setupAliceAndBobNodes()
        val conversationId = "conv-seq-mono"
        val relationshipId = "rel-alice-bob"
        val conn = alice.connectionManager.getConnectionByRelationship(relationshipId)!!

        // 1. Send normal message with sequence 1
        sendMessage(alice, bob, relationshipId, conversationId, "m-seq-1", "First message")
        waitFor { bob.messageDao.exists("m-seq-1") }
        assertEquals("Bob recvSequence must advance to 1", 1L, bob.connectionManager.getConnectionByRelationship(relationshipId)?.recvSequence)

        // 2. Send normal message with sequence 2
        sendMessage(alice, bob, relationshipId, conversationId, "m-seq-2", "Second message")
        waitFor { bob.messageDao.exists("m-seq-2") }
        assertEquals("Bob recvSequence must advance to 2", 2L, bob.connectionManager.getConnectionByRelationship(relationshipId)?.recvSequence)

        // 3. Send exempt control packet (DELIVERY_ACK) with directionSequence = 99L
        val ackPayload = DeliveryAck("m-seq-2", "dummy-env", System.currentTimeMillis()).toByteArray()
        val ackEnvelope = SecureEnvelope(
            protocolVersion = 1,
            logicalMessageId = "ack-exempt",
            conversationId = conversationId,
            senderIdentity = alice.identity.identityId,
            recipientBinding = bob.identity.identityId,
            directionSequence = 99L, // Injected sequence on exempt packet
            messageType = MessageType.DELIVERY_ACK,
            payload = ackPayload
        )
        val envBytes = ProtocolCodec.encodeSecureEnvelope(ackEnvelope)
        val aad = "torx-aad-v1:${conn.generation}:${conn.sendQueueId}".toByteArray()
        val enc = alice.sessionCrypto.encrypt(relationshipId, envBytes, aad)
        val envelopeId = UUID.randomUUID().toString()
        val rawAck = ProtocolCodec.encodeTransportEnvelope(
            OpaqueTransportEnvelope(
                version = 1,
                queueAddress = conn.sendQueueId,
                envelopeId = envelopeId,
                opaqueCiphertext = enc.serialize(),
                queueAuthenticator = IdentityCrypto.computeQueueAuthenticator(conn.sendAuth, envelopeId, conn.sendQueueId, enc.serialize())
            )
        )
        val ackSuccess = bob.dispatcher.dispatch(rawAck, TransportType.NEARBY)
        assertTrue("Exempt control frame must be accepted", ackSuccess)
        assertEquals("Exempt control frame must NOT alter recvSequence (still 2)", 2L, bob.connectionManager.getConnectionByRelationship(relationshipId)?.recvSequence)

        // 4. Send normal message with sequence 3
        sendMessage(alice, bob, relationshipId, conversationId, "m-seq-3", "Third message")
        waitFor { bob.messageDao.exists("m-seq-3") }
        assertEquals("Bob recvSequence must advance monotonically to 3", 3L, bob.connectionManager.getConnectionByRelationship(relationshipId)?.recvSequence)

        alice.stop()
        bob.stop()
    }
}

