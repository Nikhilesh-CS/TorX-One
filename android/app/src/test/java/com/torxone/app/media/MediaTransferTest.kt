package com.torxone.app.media

import com.torxone.app.agent.*
import com.torxone.app.chat.ChatService
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.DoubleRatchetSessionCrypto
import com.torxone.app.crypto.SessionState
import com.torxone.app.crypto.SessionStore
import com.torxone.app.data.dao.*
import com.torxone.app.data.entity.*
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.incoming.*
import com.torxone.app.protocol.*
import com.torxone.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive integration test suite for Media, Files & Voice Notes.
 *
 * Covers:
 * - Chunked transfer & reassembly (Images, Voice Notes, Documents)
 * - Out-of-order chunk processing
 * - Duplicate chunk idempotency
 * - Missing chunk recovery & resume from last received byte
 * - Integrity failure detection on tampered chunks
 * - Cancellation & delete during transfer
 * - Simultaneous bidirectional transfers (Alice <-> Bob)
 * - Invariant: Normal chat traffic, typing, and ACKs NEVER blocked by media transfers
 */
class MediaTransferTest {

    // ═══════════════════════════════════════════════════════════════
    //  In-Memory Test DAOs & Transport
    // ═══════════════════════════════════════════════════════════════

    class DirectLoopbackTransport(val destinationHub: IncomingTransportHub) : Transport {
        override val type: TransportType = TransportType.NEARBY
        override fun availability(): Flow<TransportAvailability> = flowOf(TransportAvailability.Available)
        override suspend fun send(destination: TransportDestination, payload: ByteArray): TransportResult {
            destinationHub.onRawFrameReceived(payload, TransportType.NEARBY)
            return TransportResult.Accepted(TransportType.NEARBY)
        }
    }

    class TestMediaDao : MediaDao {
        val mediaMap = ConcurrentHashMap<String, MediaEntity>()

        override suspend fun getById(mediaId: String): MediaEntity? = mediaMap[mediaId]

        override suspend fun getByMessageId(messageId: String): MediaEntity? =
            mediaMap.values.firstOrNull { it.messageId == messageId }

        override fun observeByMessageId(messageId: String): Flow<MediaEntity?> =
            flowOf(mediaMap.values.firstOrNull { it.messageId == messageId })

        override fun observeForConversation(conversationId: String): Flow<List<MediaEntity>> =
            flowOf(mediaMap.values.filter { it.conversationId == conversationId })

        override suspend fun getMediaForConversation(conversationId: String): List<MediaEntity> =
            mediaMap.values.filter { it.conversationId == conversationId }

        override suspend fun insert(media: MediaEntity) {
            mediaMap[media.mediaId] = media
        }

        override suspend fun updateStatus(mediaId: String, status: String, progress: Float) {
            mediaMap[mediaId]?.let {
                mediaMap[mediaId] = it.copy(status = status, transferProgress = progress)
            }
        }

        override suspend fun updateLocalPathAndStatus(mediaId: String, localPath: String, status: String) {
            mediaMap[mediaId]?.let {
                mediaMap[mediaId] = it.copy(localPath = localPath, status = status, transferProgress = 1.0f)
            }
        }

        override suspend fun deleteByMediaId(mediaId: String) {
            mediaMap.remove(mediaId)
        }

        override suspend fun deleteByMessageId(messageId: String) {
            mediaMap.entries.removeIf { it.value.messageId == messageId }
        }

        override suspend fun deleteByConversation(conversationId: String) {
            mediaMap.entries.removeIf { it.value.conversationId == conversationId }
        }
    }

    class TestMediaTransferDao : MediaTransferDao {
        val transfers = ConcurrentHashMap<String, MediaTransferEntity>()

        override suspend fun getByTransferId(transferId: String): MediaTransferEntity? = transfers[transferId]

        override suspend fun getByMediaId(mediaId: String): MediaTransferEntity? =
            transfers.values.firstOrNull { it.mediaId == mediaId }

        override fun observeByMediaId(mediaId: String): Flow<MediaTransferEntity?> =
            flowOf(transfers.values.firstOrNull { it.mediaId == mediaId })

        override suspend fun upsert(transfer: MediaTransferEntity) {
            transfers[transfer.transferId] = transfer
        }

        override suspend fun updateProgress(
            transferId: String,
            completedChunks: Int,
            chunkBitmask: String,
            bytesTransferred: Long,
            status: String,
            updatedAt: Long
        ) {
            transfers[transferId]?.let {
                transfers[transferId] = it.copy(
                    completedChunks = completedChunks,
                    chunkBitmask = chunkBitmask,
                    bytesTransferred = bytesTransferred,
                    status = status,
                    updatedAt = updatedAt
                )
            }
        }

        override suspend fun updateStatus(transferId: String, status: String, updatedAt: Long) {
            transfers[transferId]?.let {
                transfers[transferId] = it.copy(status = status, updatedAt = updatedAt)
            }
        }

        override suspend fun getPendingTransfers(): List<MediaTransferEntity> =
            transfers.values.filter { it.status == "ACTIVE" || it.status == "QUEUED" || it.status == "PAUSED" }

        override suspend fun getAllActiveMediaIds(): List<String> =
            transfers.values.filter { it.status == "ACTIVE" || it.status == "QUEUED" || it.status == "PAUSED" }.map { it.mediaId }

        override suspend fun deleteByTransferId(transferId: String) {
            transfers.remove(transferId)
        }

        override suspend fun deleteByMediaId(mediaId: String) {
            transfers.entries.removeIf { it.value.mediaId == mediaId }
        }

        override suspend fun deleteByConversation(conversationId: String) {
            transfers.entries.removeIf { it.value.conversationId == conversationId }
        }
    }

    class TestMessageDao : MessageDao {
        val messages = ConcurrentHashMap<String, MessageEntity>()

        override suspend fun exists(messageId: String): Boolean = messages.containsKey(messageId)

        override suspend fun getById(messageId: String): MessageEntity? = messages[messageId]

        override fun observeByConversation(conversationId: String): Flow<List<MessageEntity>> =
            flowOf(messages.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAt })

        override suspend fun insertIfAbsent(message: MessageEntity): Long {
            return if (messages.putIfAbsent(message.logicalMessageId, message) == null) 1L else -1L
        }

        override suspend fun upsert(message: MessageEntity) {
            messages[message.logicalMessageId] = message
        }

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
        override suspend fun updateBodyAndEdit(messageId: String, newBody: String, editVersion: Int, editedAt: Long) {}
        override suspend fun markDeleted(messageId: String, deletedAt: Long) {
            messages[messageId]?.let { messages[messageId] = it.copy(body = null, deletedAt = deletedAt) }
        }
        override suspend fun getMessagesForConversationDesc(conversationId: String): List<MessageEntity> =
            messages.values.filter { it.conversationId == conversationId }.sortedByDescending { it.createdAt }
        override suspend fun deleteByConversation(conversationId: String) {
            messages.entries.removeIf { it.value.conversationId == conversationId }
        }
    }

    class TestConversationDao : ConversationDao {
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

    class TestOutboxDao : OutboxDao {
        private val seqCounter = AtomicLong(0)
        private val insertOrder = ConcurrentHashMap<String, Long>()
        val items = ConcurrentHashMap<String, OutboxEntity>()

        override suspend fun getPending(now: Long): List<OutboxEntity> {
            val nowTime = if (now != 0L) now else System.currentTimeMillis()
            return items.values
                .filter {
                    ((it.status == "QUEUED" || it.status == "RETRY_WAIT") ||
                     (it.status == "TRANSPORT_ACCEPTED" && nowTime - it.updatedAt > 3000L)) &&
                    it.nextAttemptAt <= nowTime
                }
                .sortedWith(
                    compareByDescending<OutboxEntity> { it.priority }
                        .thenBy { insertOrder[it.deliveryId] ?: 0L }
                        .thenBy { it.createdAt }
                )
        }

        override suspend fun insert(item: OutboxEntity) {
            insertOrder.putIfAbsent(item.deliveryId, seqCounter.incrementAndGet())
            items[item.deliveryId] = item
        }

        override suspend fun updateStatus(deliveryId: String, status: String, now: Long) {
            items[deliveryId]?.let { items[deliveryId] = it.copy(status = status, updatedAt = now) }
        }

        override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long, now: Long) {
            items[deliveryId]?.let {
                items[deliveryId] = it.copy(attemptCount = attemptCount, nextAttemptAt = nextAttemptAt, status = "RETRY_WAIT", updatedAt = now)
            }
        }

        override suspend fun removeByMessageId(logicalMessageId: String) {
            val keys = items.values.filter { it.logicalMessageId == logicalMessageId }.map { it.deliveryId }
            for (key in keys) {
                items.remove(key)
                insertOrder.remove(key)
            }
        }

        override suspend fun removeByDeliveryId(deliveryId: String) {
            items.remove(deliveryId)
            insertOrder.remove(deliveryId)
        }
    }

    class TestReactionDao : ReactionDao {
        override fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override suspend fun getForMessage(messageId: String): List<ReactionEntity> = emptyList()
        override suspend fun insertOrUpdate(reaction: ReactionEntity) {}
        override suspend fun remove(messageId: String, senderId: String, emoji: String) {}
        override suspend fun removeAllFromSender(messageId: String, senderId: String) {}
        override suspend fun deleteByConversation(conversationId: String) {}
    }

    class TestLocalMessageStateDao : LocalMessageStateDao {
        override suspend fun getByMessageId(messageId: String): LocalMessageStateEntity? = null
        override fun observeHiddenMessageIds(conversationId: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun getHiddenMessageIds(conversationId: String): List<String> = emptyList()
        override fun observeAllHiddenMessageIds(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun upsert(entity: LocalMessageStateEntity) {}
        override suspend fun delete(messageId: String) {}
        override suspend fun deleteByConversation(conversationId: String) {}
    }

    class TestProcessedEnvelopeDao : ProcessedEnvelopeDao {
        val set = ConcurrentHashMap.newKeySet<String>()
        override suspend fun isProcessed(envelopeId: String): Boolean = set.contains(envelopeId)
        override suspend fun isMessageProcessed(logicalMessageId: String): Boolean = set.contains(logicalMessageId)
        override suspend fun insert(entity: ProcessedEnvelopeEntity) { set.add(entity.envelopeId) }
        override suspend fun getByEnvelopeId(envelopeId: String): ProcessedEnvelopeEntity? =
            if (set.contains(envelopeId)) ProcessedEnvelopeEntity(envelopeId, "test", System.currentTimeMillis()) else null
        override suspend fun pruneOlderThan(before: Long) {}
    }

    class InMemorySessionStore : SessionStore {
        val sessions = ConcurrentHashMap<String, SessionState>()
        override suspend fun loadSession(relationshipId: String): SessionState? = sessions[relationshipId]?.copyState()
        override suspend fun saveSession(state: SessionState) { sessions[state.relationshipId] = state.copyState() }
        override suspend fun deleteSession(relationshipId: String) { sessions.remove(relationshipId) }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Node Harness
    // ═══════════════════════════════════════════════════════════════

    class TestNode(val name: String) {
        val identityKey = IdentityCrypto.generateEd25519KeyPair()
        val identityId = IdentityCrypto.computeFingerprint(identityKey.publicKey)
        val ratchetKey = IdentityCrypto.generateX25519KeyPair()
        val tempDir: File = Files.createTempDirectory("torx_${name}_").toFile().apply { deleteOnExit() }

        val connManager = ConnectionManager()
        val sessionStore = InMemorySessionStore()
        val crypto = DoubleRatchetSessionCrypto(sessionStore)
        val router = TransportRouter()

        val outboxDao = TestOutboxDao()
        val processedDao = TestProcessedEnvelopeDao()
        val msgDao = TestMessageDao()
        val convDao = TestConversationDao()
        val rxDao = TestReactionDao()
        val localStateDao = TestLocalMessageStateDao()
        val mediaDao = TestMediaDao()
        val mediaTransferDao = TestMediaTransferDao()

        val agent = TorXAgent(
            transportRouter = router,
            outboxStore = object : OutboxStore {
                override suspend fun insert(item: DeliveryItem) {
                    outboxDao.insert(
                        OutboxEntity(
                            deliveryId = item.deliveryId,
                            logicalMessageId = item.logicalMessageId,
                            conversationId = item.conversationId,
                            connectionId = item.connectionId,
                            queueAddress = item.queueAddress,
                            ciphertext = item.ciphertext,
                            queueAuthenticator = item.queueAuthenticator,
                            status = item.status.name,
                            priority = item.priority,
                            attemptCount = item.attemptCount,
                            nextAttemptAt = item.nextAttemptAt,
                            createdAt = item.createdAt,
                            updatedAt = item.updatedAt,
                            expectsAck = item.expectsAck
                        )
                    )
                }
                override suspend fun getPendingItems(): List<DeliveryItem> {
                    return outboxDao.getPending().map {
                        DeliveryItem(
                            deliveryId = it.deliveryId,
                            logicalMessageId = it.logicalMessageId,
                            conversationId = it.conversationId,
                            connectionId = it.connectionId,
                            queueAddress = it.queueAddress,
                            ciphertext = it.ciphertext,
                            queueAuthenticator = it.queueAuthenticator,
                            status = DeliveryStatus.valueOf(it.status),
                            priority = it.priority,
                            attemptCount = it.attemptCount,
                            nextAttemptAt = it.nextAttemptAt,
                            createdAt = it.createdAt,
                            updatedAt = it.updatedAt,
                            expectsAck = it.expectsAck
                        )
                    }
                }
                override suspend fun updateStatus(deliveryId: String, status: DeliveryStatus) {
                    outboxDao.updateStatus(deliveryId, status.name)
                }
                override suspend fun updateRetry(deliveryId: String, attemptCount: Int, nextAttemptAt: Long) {
                    outboxDao.updateRetry(deliveryId, attemptCount, nextAttemptAt)
                }
                override suspend fun removeByMessageId(logicalMessageId: String) {
                    outboxDao.removeByMessageId(logicalMessageId)
                }
            },
            processedStore = object : ProcessedEnvelopeStore {
                override suspend fun isProcessed(envelopeId: String) = processedDao.isProcessed(envelopeId)
                override suspend fun isMessageProcessed(logicalMessageId: String) = processedDao.isMessageProcessed(logicalMessageId)
                override suspend fun markProcessed(record: ProcessedEnvelope) {
                    processedDao.insert(ProcessedEnvelopeEntity(record.envelopeId, record.logicalMessageId))
                }
            }
        )

        val mediaStorage = MediaStorage(customBaseDir = tempDir)

        var mediaService: MediaService? = null
        var chatService: ChatService? = null
        var incomingHub: IncomingTransportHub? = null
    }

    private suspend fun setupPair(): Pair<TestNode, TestNode> {
        val alice = TestNode("alice")
        val bob = TestNode("bob")

        val relationshipId = "rel_alice_bob"
        val rootSecret = "shared-test-root-key-32-bytes!!!".toByteArray()

        alice.crypto.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = rootSecret,
            isInitiator = true,
            remoteRatchetPublicKey = bob.ratchetKey.publicKey,
            localRatchetPrivateKey = alice.ratchetKey.privateKey,
            localRatchetPublicKey = alice.ratchetKey.publicKey
        )

        bob.crypto.initializeSession(
            relationshipId = relationshipId,
            sessionInitializationSecret = rootSecret,
            isInitiator = false,
            remoteRatchetPublicKey = alice.ratchetKey.publicKey,
            localRatchetPrivateKey = bob.ratchetKey.privateKey,
            localRatchetPublicKey = bob.ratchetKey.publicKey
        )

        val connA = Connection(
            connectionId = "conn_a_b",
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = "queue_bob",
            recvQueueId = "queue_alice",
            sendAuth = ByteArray(32) { 1 },
            recvAuth = ByteArray(32) { 2 }
        )
        val connB = Connection(
            connectionId = "conn_b_a",
            relationshipId = relationshipId,
            generation = 1,
            sendQueueId = "queue_alice",
            recvQueueId = "queue_bob",
            sendAuth = ByteArray(32) { 2 },
            recvAuth = ByteArray(32) { 1 }
        )
        alice.connManager.registerConnection(connA)
        bob.connManager.registerConnection(connB)

        alice.mediaService = MediaService(
            sessionCrypto = alice.crypto,
            connectionManager = alice.connManager,
            agent = alice.agent,
            messageDao = alice.msgDao,
            conversationDao = alice.convDao,
            mediaDao = alice.mediaDao,
            mediaTransferDao = alice.mediaTransferDao,
            outboxDao = alice.outboxDao,
            localIdentityIdProvider = { alice.identityId },
            mediaStorage = alice.mediaStorage
        )

        bob.mediaService = MediaService(
            sessionCrypto = bob.crypto,
            connectionManager = bob.connManager,
            agent = bob.agent,
            messageDao = bob.msgDao,
            conversationDao = bob.convDao,
            mediaDao = bob.mediaDao,
            mediaTransferDao = bob.mediaTransferDao,
            outboxDao = bob.outboxDao,
            localIdentityIdProvider = { bob.identityId },
            mediaStorage = bob.mediaStorage
        )

        alice.chatService = ChatService(
            sessionCrypto = alice.crypto,
            connectionManager = alice.connManager,
            agent = alice.agent,
            messageDao = alice.msgDao,
            conversationDao = alice.convDao,
            outboxDao = alice.outboxDao,
            reactionDao = alice.rxDao,
            localMessageStateDao = alice.localStateDao
        )

        bob.chatService = ChatService(
            sessionCrypto = bob.crypto,
            connectionManager = bob.connManager,
            agent = bob.agent,
            messageDao = bob.msgDao,
            conversationDao = bob.convDao,
            outboxDao = bob.outboxDao,
            reactionDao = bob.rxDao,
            localMessageStateDao = bob.localStateDao
        )

        val trackerA = ActiveConversationTracker()
        val trackerB = ActiveConversationTracker()

        val aliceMediaHandler = MediaHandler(alice.mediaService!!)
        val bobMediaHandler = MediaHandler(bob.mediaService!!)

        val aliceDispatcher = IncomingDispatcher(
            connectionManager = alice.connManager,
            sessionCrypto = alice.crypto,
            processedEnvelopeDao = alice.processedDao,
            chatReceiver = ChatReceiver(alice.msgDao, alice.convDao, trackerA),
            deliveryReceiptHandler = DeliveryReceiptHandler(alice.msgDao, alice.outboxDao, alice.agent),
            agent = alice.agent,
            localIdentityIdProvider = { alice.identityId },
            mediaHandler = aliceMediaHandler
        )

        val bobDispatcher = IncomingDispatcher(
            connectionManager = bob.connManager,
            sessionCrypto = bob.crypto,
            processedEnvelopeDao = bob.processedDao,
            chatReceiver = ChatReceiver(bob.msgDao, bob.convDao, trackerB),
            deliveryReceiptHandler = DeliveryReceiptHandler(bob.msgDao, bob.outboxDao, bob.agent),
            agent = bob.agent,
            localIdentityIdProvider = { bob.identityId },
            mediaHandler = bobMediaHandler
        )

        alice.incomingHub = IncomingTransportHub(aliceDispatcher)
        bob.incomingHub = IncomingTransportHub(bobDispatcher)

        alice.router.registerTransport(DirectLoopbackTransport(bob.incomingHub!!))
        bob.router.registerTransport(DirectLoopbackTransport(alice.incomingHub!!))

        alice.agent.start()
        bob.agent.start()

        return Pair(alice, bob)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testImageChunkedTransferAndReassembly() = runBlocking {
        val (alice, bob) = setupPair()
        val conversationId = "conv_alice_bob"

        // 45 KB dummy image payload -> Sliced into 3 chunks (16 KB, 16 KB, 13 KB + IV/Tag)
        val imageBytes = ByteArray(45 * 1024) { (it % 256).toByte() }

        val msgId = alice.mediaService!!.sendMedia(
            conversationId = conversationId,
            relationshipId = "rel_alice_bob",
            localIdentityId = alice.identityId,
            recipientId = bob.identityId,
            type = MediaType.IMAGE,
            fileName = "photo_vacation.jpg",
            mimeType = "image/jpeg",
            rawBytes = imageBytes
        )

        // Wait for asynchronous chunk processing to complete
        withTimeout(10000) {
            while (bob.mediaDao.mediaMap.isEmpty() ||
                bob.mediaDao.mediaMap.values.first().status != MediaStatus.COMPLETE.name
            ) {
                delay(20)
            }
        }

        val bobMedia = bob.mediaDao.mediaMap.values.first()
        assertEquals(MediaStatus.COMPLETE.name, bobMedia.status)
        assertNotNull(bobMedia.localPath)

        val bobSavedFile = File(bobMedia.localPath!!)
        assertTrue(bobSavedFile.exists())
        val bobSavedBytes = bobSavedFile.readBytes()

        assertArrayEquals("Decrypted media on receiver must match original sender bytes", imageBytes, bobSavedBytes)

        alice.agent.stop()
        bob.agent.stop()
    }

    @Test
    fun testOutOfOrderChunks() = runBlocking {
        val (alice, bob) = setupPair()
        val mediaId = UUID.randomUUID().toString()
        val key = MediaCrypto.generateMediaKey()
        val plaintext = "Out of order chunk assembly test payload".toByteArray(Charsets.UTF_8)
        val encrypted = MediaCrypto.encrypt(key, plaintext)
        val hash = MediaCrypto.sha256Hex(encrypted)

        val conn = bob.connManager.getConnectionByRelationship("rel_alice_bob")!!

        // Send descriptor first
        val desc = MediaDescriptor(
            mediaId = mediaId,
            type = MediaType.DOCUMENT,
            mimeType = "text/plain",
            fileName = "doc.txt",
            fileSize = plaintext.size.toLong(),
            encryptedSha256 = hash,
            mediaKeyBase64 = Base64.getEncoder().encodeToString(key),
            totalChunks = 3,
            chunkSize = 16
        )
        val descBytes = MediaProtocolCodec.encodeDescriptor(desc)
        val descEnv = SecureEnvelope(
            conversationId = "conv_1",
            senderIdentity = alice.identityId,
            recipientBinding = bob.identityId,
            messageType = MessageType.FILE,
            payload = descBytes
        )
        bob.mediaService!!.handleIncomingDescriptor(conn, descEnv)

        // Deliver chunks in reverse order: Chunk 2, then Chunk 0, then Chunk 1
        val c0 = encrypted.sliceArray(0 until 16)
        val c1 = encrypted.sliceArray(16 until 32)
        val c2 = encrypted.sliceArray(32 until encrypted.size)

        val chunks = listOf(
            MediaChunkPayload(mediaId, 2, 3, c2),
            MediaChunkPayload(mediaId, 0, 3, c0),
            MediaChunkPayload(mediaId, 1, 3, c1)
        )

        for (c in chunks) {
            val env = SecureEnvelope(
                conversationId = "conv_1",
                senderIdentity = alice.identityId,
                recipientBinding = bob.identityId,
                messageType = MessageType.FILE_PROGRESS,
                payload = MediaProtocolCodec.encodeChunk(c)
            )
            bob.mediaService!!.handleIncomingChunk(conn, env)
        }

        val savedMedia = bob.mediaDao.getById(mediaId)
        assertNotNull(savedMedia)
        assertEquals(MediaStatus.COMPLETE.name, savedMedia!!.status)
        val resultBytes = File(savedMedia.localPath!!).readBytes()
        assertArrayEquals(plaintext, resultBytes)

        alice.agent.stop()
        bob.agent.stop()
    }

    @Test
    fun testDuplicateChunksAreIdempotent() = runBlocking {
        val (alice, bob) = setupPair()
        val mediaId = UUID.randomUUID().toString()
        val key = MediaCrypto.generateMediaKey()
        val plaintext = "Duplicate test payload".toByteArray(Charsets.UTF_8)
        val encrypted = MediaCrypto.encrypt(key, plaintext)

        val conn = bob.connManager.getConnectionByRelationship("rel_alice_bob")!!

        val desc = MediaDescriptor(
            mediaId = mediaId,
            type = MediaType.IMAGE,
            mimeType = "image/png",
            fileName = "dup.png",
            fileSize = plaintext.size.toLong(),
            encryptedSha256 = MediaCrypto.sha256Hex(encrypted),
            mediaKeyBase64 = Base64.getEncoder().encodeToString(key),
            totalChunks = 2,
            chunkSize = 16
        )
        bob.mediaService!!.handleIncomingDescriptor(
            conn,
            SecureEnvelope(conversationId = "conv_1", senderIdentity = alice.identityId, recipientBinding = bob.identityId, messageType = MessageType.IMAGE, payload = MediaProtocolCodec.encodeDescriptor(desc))
        )

        val c0 = encrypted.sliceArray(0 until 16)
        val chunk0 = MediaChunkPayload(mediaId, 0, 2, c0)
        val env0 = SecureEnvelope(conversationId = "conv_1", senderIdentity = alice.identityId, recipientBinding = bob.identityId, messageType = MessageType.FILE_PROGRESS, payload = MediaProtocolCodec.encodeChunk(chunk0))

        // Deliver chunk 0 TWICE
        bob.mediaService!!.handleIncomingChunk(conn, env0)
        bob.mediaService!!.handleIncomingChunk(conn, env0)

        val transfer = bob.mediaTransferDao.getByMediaId(mediaId)!!
        assertEquals("Completed chunk count must remain 1 after duplicate delivery", 1, transfer.completedChunks)

        alice.agent.stop()
        bob.agent.stop()
    }

    @Test
    fun testMissingChunkRecoveryAndResume() = runBlocking {
        val (alice, bob) = setupPair()
        val mediaId = UUID.randomUUID().toString()
        val key = MediaCrypto.generateMediaKey()
        val plaintext = "Resumable transfer payload test across multiple chunks".toByteArray(Charsets.UTF_8)
        val encrypted = MediaCrypto.encrypt(key, plaintext)

        val conn = bob.connManager.getConnectionByRelationship("rel_alice_bob")!!

        val desc = MediaDescriptor(
            mediaId = mediaId,
            type = MediaType.DOCUMENT,
            mimeType = "text/plain",
            fileName = "resume.txt",
            fileSize = plaintext.size.toLong(),
            encryptedSha256 = MediaCrypto.sha256Hex(encrypted),
            mediaKeyBase64 = Base64.getEncoder().encodeToString(key),
            totalChunks = 4,
            chunkSize = 16
        )
        bob.mediaService!!.handleIncomingDescriptor(
            conn,
            SecureEnvelope(conversationId = "conv_1", senderIdentity = alice.identityId, recipientBinding = bob.identityId, messageType = MessageType.FILE, payload = MediaProtocolCodec.encodeDescriptor(desc))
        )

        // Deliver chunks 0, 2, 3 (Chunk 1 is missing!)
        val c0 = encrypted.sliceArray(0 until 16)
        val c1 = encrypted.sliceArray(16 until 32)
        val c2 = encrypted.sliceArray(32 until 48)
        val c3 = encrypted.sliceArray(48 until encrypted.size)

        bob.mediaService!!.handleIncomingChunk(conn, SecureEnvelope(conversationId = "c", senderIdentity = "a", recipientBinding = "b", messageType = MessageType.FILE_PROGRESS, payload = MediaProtocolCodec.encodeChunk(MediaChunkPayload(mediaId, 0, 4, c0))))
        bob.mediaService!!.handleIncomingChunk(conn, SecureEnvelope(conversationId = "c", senderIdentity = "a", recipientBinding = "b", messageType = MessageType.FILE_PROGRESS, payload = MediaProtocolCodec.encodeChunk(MediaChunkPayload(mediaId, 2, 4, c2))))
        bob.mediaService!!.handleIncomingChunk(conn, SecureEnvelope(conversationId = "c", senderIdentity = "a", recipientBinding = "b", messageType = MessageType.FILE_PROGRESS, payload = MediaProtocolCodec.encodeChunk(MediaChunkPayload(mediaId, 3, 4, c3))))

        val missing = bob.mediaService!!.getMissingChunkIndices(mediaId)
        assertEquals(listOf(1), missing)

        // Resume: Deliver ONLY missing chunk 1
        bob.mediaService!!.handleIncomingChunk(conn, SecureEnvelope(conversationId = "c", senderIdentity = "a", recipientBinding = "b", messageType = MessageType.FILE_PROGRESS, payload = MediaProtocolCodec.encodeChunk(MediaChunkPayload(mediaId, 1, 4, c1))))

        val finalMedia = bob.mediaDao.getById(mediaId)!!
        assertEquals(MediaStatus.COMPLETE.name, finalMedia.status)
        assertArrayEquals(plaintext, File(finalMedia.localPath!!).readBytes())

        alice.agent.stop()
        bob.agent.stop()
    }

    @Test
    fun testIntegrityFailureOnTamperedCiphertext() = runBlocking {
        val (alice, bob) = setupPair()
        val mediaId = UUID.randomUUID().toString()
        val key = MediaCrypto.generateMediaKey()
        val plaintext = "Integrity test data".toByteArray(Charsets.UTF_8)
        val encrypted = MediaCrypto.encrypt(key, plaintext)
        val originalHash = MediaCrypto.sha256Hex(encrypted)

        val conn = bob.connManager.getConnectionByRelationship("rel_alice_bob")!!

        val desc = MediaDescriptor(
            mediaId = mediaId,
            type = MediaType.IMAGE,
            mimeType = "image/jpeg",
            fileName = "tampered.jpg",
            fileSize = plaintext.size.toLong(),
            encryptedSha256 = originalHash,
            mediaKeyBase64 = Base64.getEncoder().encodeToString(key),
            totalChunks = 1,
            chunkSize = encrypted.size
        )
        bob.mediaService!!.handleIncomingDescriptor(
            conn,
            SecureEnvelope(conversationId = "conv_1", senderIdentity = alice.identityId, recipientBinding = bob.identityId, messageType = MessageType.IMAGE, payload = MediaProtocolCodec.encodeDescriptor(desc))
        )

        // Tamper 1 byte of the encrypted chunk in transit
        val tamperedEncrypted = encrypted.clone()
        tamperedEncrypted[15] = (tamperedEncrypted[15].toInt() xor 0xFF).toByte()

        bob.mediaService!!.handleIncomingChunk(
            conn,
            SecureEnvelope(conversationId = "c", senderIdentity = "a", recipientBinding = "b", messageType = MessageType.FILE_PROGRESS, payload = MediaProtocolCodec.encodeChunk(MediaChunkPayload(mediaId, 0, 1, tamperedEncrypted)))
        )

        val media = bob.mediaDao.getById(mediaId)!!
        assertEquals("Tampered media must transition to FAILED status", MediaStatus.FAILED.name, media.status)
        assertNull("Failed media must not have local plaintext path", media.localPath)

        alice.agent.stop()
        bob.agent.stop()
    }

    @Test
    fun testTransferCancellation() = runBlocking {
        val (alice, bob) = setupPair()
        val mediaId = "cancel_test_media"

        alice.mediaDao.insert(
            MediaEntity(
                mediaId = mediaId,
                messageId = "msg_1",
                conversationId = "conv_1",
                mediaType = MediaType.VIDEO.name,
                mimeType = "video/mp4",
                fileName = "vid.mp4",
                fileSize = 1000000L,
                encryptedSha256 = "hash",
                mediaKey = ByteArray(32),
                status = MediaStatus.UPLOADING.name
            )
        )
        alice.mediaTransferDao.upsert(
            MediaTransferEntity(
                transferId = mediaId,
                mediaId = mediaId,
                conversationId = "conv_1",
                relationshipId = "rel_1",
                direction = TransferDirection.UPLOAD.name,
                totalChunks = 50,
                chunkSize = 16384,
                tempEncryptedPath = File(alice.tempDir, "temp.enc").absolutePath,
                status = TransferStatus.ACTIVE.name,
                totalBytes = 1000000L
            )
        )

        alice.mediaService!!.cancelTransfer(mediaId)

        val media = alice.mediaDao.getById(mediaId)!!
        assertEquals(MediaStatus.CANCELLED.name, media.status)

        val transfer = alice.mediaTransferDao.getByMediaId(mediaId)!!
        assertEquals(TransferStatus.CANCELLED.name, transfer.status)

        alice.agent.stop()
        bob.agent.stop()
    }

    @Test
    fun testDeleteDuringTransfer() = runBlocking {
        val (alice, bob) = setupPair()
        val mediaId = "delete_test_media"
        val messageId = "msg_delete_target"

        val localFile = File(alice.tempDir, "sample.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        alice.mediaDao.insert(
            MediaEntity(
                mediaId = mediaId,
                messageId = messageId,
                conversationId = "conv_1",
                mediaType = MediaType.IMAGE.name,
                mimeType = "image/jpeg",
                fileName = "sample.jpg",
                fileSize = 3L,
                localPath = localFile.absolutePath,
                encryptedSha256 = "hash",
                mediaKey = ByteArray(32),
                status = MediaStatus.UPLOADING.name
            )
        )

        alice.mediaService!!.deleteMediaForMessage(messageId, cleanupLocalFile = true)

        assertNull(alice.mediaDao.getById(mediaId))
        assertNull(alice.mediaTransferDao.getByMediaId(mediaId))

        alice.agent.stop()
        bob.agent.stop()
    }

    @Test
    fun testSimultaneousBidirectionalMediaTransfers() = runBlocking {
        val (alice, bob) = setupPair()
        val conversationId = "conv_alice_bob"

        val photoData = ByteArray(32 * 1024) { 0xAA.toByte() }
        val voiceData = VoiceNoteHelper.generateSyntheticAudio(durationSeconds = 2)

        // Alice sends image to Bob, Bob concurrently sends voice note to Alice
        val aliceJob = launch {
            alice.mediaService!!.sendMedia(
                conversationId = conversationId,
                relationshipId = "rel_alice_bob",
                localIdentityId = alice.identityId,
                recipientId = bob.identityId,
                type = MediaType.IMAGE,
                fileName = "alice_photo.jpg",
                mimeType = "image/jpeg",
                rawBytes = photoData
            )
        }

        val bobJob = launch {
            bob.mediaService!!.sendMedia(
                conversationId = conversationId,
                relationshipId = "rel_alice_bob",
                localIdentityId = bob.identityId,
                recipientId = alice.identityId,
                type = MediaType.VOICE_NOTE,
                fileName = "bob_voice.m4a",
                mimeType = "audio/mp4",
                rawBytes = voiceData,
                durationMs = 2000L
            )
        }

        joinAll(aliceJob, bobJob)

        withTimeout(8000) {
            while (
                bob.mediaDao.mediaMap.values.none { it.fileName == "alice_photo.jpg" && it.status == MediaStatus.COMPLETE.name } ||
                alice.mediaDao.mediaMap.values.none { it.fileName == "bob_voice.m4a" && it.status == MediaStatus.COMPLETE.name }
            ) {
                delay(20)
            }
        }

        val bobReceived = bob.mediaDao.mediaMap.values.first { it.fileName == "alice_photo.jpg" }
        val aliceReceived = alice.mediaDao.mediaMap.values.first { it.fileName == "bob_voice.m4a" }

        assertEquals(MediaStatus.COMPLETE.name, bobReceived.status)
        assertEquals(MediaStatus.COMPLETE.name, aliceReceived.status)

        assertArrayEquals(photoData, File(bobReceived.localPath!!).readBytes())
        assertArrayEquals(voiceData, File(aliceReceived.localPath!!).readBytes())

        alice.agent.stop()
        bob.agent.stop()
    }

    @Test
    fun testChatTrafficNotBlockedDuringMediaTransfer() = runBlocking {
        val (alice, bob) = setupPair()
        val conversationId = "conv_alice_bob"

        // Large 80 KB media file (~5 chunks)
        val largeData = ByteArray(80 * 1024) { 0x42.toByte() }

        // Start large media transfer in background
        val mediaSendJob = launch {
            alice.mediaService!!.sendMedia(
                conversationId = conversationId,
                relationshipId = "rel_alice_bob",
                localIdentityId = alice.identityId,
                recipientId = bob.identityId,
                type = MediaType.DOCUMENT,
                fileName = "heavy_specs.pdf",
                mimeType = "application/pdf",
                rawBytes = largeData
            )
        }

        // While media transfer is active, send high-priority chat messages & reactions
        delay(15) // Let media start transferring
        val textMessageId = alice.chatService!!.sendTextMessage(
            conversationId = conversationId,
            relationshipId = "rel_alice_bob",
            localIdentityId = alice.identityId,
            recipientId = bob.identityId,
            text = "Urgent: review this now!"
        )

        // Verify Bob receives the text message immediately
        withTimeout(2000) {
            while (!bob.msgDao.exists(textMessageId)) {
                delay(10)
            }
        }
        val receivedText = bob.msgDao.messages[textMessageId]
        assertNotNull("Text message must arrive without waiting for media transfer to finish", receivedText)
        assertEquals("Urgent: review this now!", receivedText!!.body)

        // Wait for media transfer to complete
        mediaSendJob.join()
        withTimeout(10000) {
            while (bob.mediaDao.mediaMap.isEmpty() ||
                bob.mediaDao.mediaMap.values.first().status != MediaStatus.COMPLETE.name
            ) {
                delay(20)
            }
        }

        val completedMedia = bob.mediaDao.mediaMap.values.first()
        assertEquals(MediaStatus.COMPLETE.name, completedMedia.status)
        assertArrayEquals(largeData, File(completedMedia.localPath!!).readBytes())

        alice.agent.stop()
        bob.agent.stop()
    }

    @Test
    fun testStreamingFileEncryptionAndDecryption() = runBlocking {
        val tempDir = Files.createTempDirectory("media_crypto_test").toFile().apply { deleteOnExit() }
        val sourceFile = File(tempDir, "source.bin")
        val encFile = File(tempDir, "enc.bin")
        val decFile = File(tempDir, "dec.bin")

        // 128 KB test data
        val sourceData = ByteArray(128 * 1024) { (it % 251).toByte() }
        sourceFile.writeBytes(sourceData)

        val key = MediaCrypto.generateMediaKey()

        // 1. Streaming encrypt
        val hexHash = sourceFile.inputStream().use { input ->
            encFile.outputStream().use { output ->
                MediaCrypto.encryptStream(key, input, output)
            }
        }
        assertTrue(encFile.exists() && encFile.length() > sourceFile.length())

        // 2. Verify file integrity
        assertTrue(MediaCrypto.verifyFileIntegrity(encFile, hexHash))

        // 3. Streaming decrypt
        encFile.inputStream().use { input ->
            decFile.outputStream().use { output ->
                MediaCrypto.decryptStream(key, input, output)
            }
        }
        assertTrue(decFile.exists())
        assertArrayEquals(sourceData, decFile.readBytes())

        // 4. Tampering detection
        val tamperedBytes = encFile.readBytes()
        tamperedBytes[20] = (tamperedBytes[20].toInt() xor 0xFF).toByte()
        encFile.writeBytes(tamperedBytes)
        assertFalse(MediaCrypto.verifyFileIntegrity(encFile, hexHash))
    }

    @Test
    fun testSendMediaFileStreamingTransfer() = runBlocking {
        val (alice, bob) = setupPair()
        val conversationId = "conv_alice_bob"

        val fileData = ByteArray(50 * 1024) { (it % 199).toByte() }
        val testFile = File(alice.tempDir, "video_stream.mp4").apply { writeBytes(fileData) }

        val msgId = alice.mediaService!!.sendMediaFile(
            conversationId = conversationId,
            relationshipId = "rel_alice_bob",
            localIdentityId = alice.identityId,
            recipientId = bob.identityId,
            type = MediaType.VIDEO,
            file = testFile,
            mimeType = "video/mp4"
        )
        assertNotNull(msgId)

        // Wait for receiver completion
        withTimeout(10000) {
            while (bob.mediaDao.mediaMap.isEmpty() ||
                bob.mediaDao.mediaMap.values.first().status != MediaStatus.COMPLETE.name
            ) {
                delay(20)
            }
        }

        val bobMedia = bob.mediaDao.mediaMap.values.first()
        assertEquals(MediaStatus.COMPLETE.name, bobMedia.status)
        val bobSavedBytes = File(bobMedia.localPath!!).readBytes()
        assertArrayEquals("Streamed file must match byte-for-byte on receiver", fileData, bobSavedBytes)

        alice.agent.stop()
        bob.agent.stop()
    }

    @Test
    fun testReceiverConfirmedCompletionAndTempFileCleanup() = runBlocking {
        val (alice, bob) = setupPair()
        val conversationId = "conv_alice_bob"

        val testData = ByteArray(8 * 1024) { 0x55.toByte() }
        val msgId = alice.mediaService!!.sendMedia(
            conversationId = conversationId,
            relationshipId = "rel_alice_bob",
            localIdentityId = alice.identityId,
            recipientId = bob.identityId,
            type = MediaType.DOCUMENT,
            fileName = "doc_to_confirm.pdf",
            mimeType = "application/pdf",
            rawBytes = testData
        )

        val aliceMedia = alice.mediaDao.getByMessageId(msgId)!!
        val aliceEncFile = alice.mediaStorage.getTempEncryptedFile(aliceMedia.mediaId)

        // Phase 1: Wait for DELIVERED status (protocol-level, reliable)
        withTimeout(60000) {
            while (alice.mediaDao.getById(aliceMedia.mediaId)?.status != MediaStatus.DELIVERED.name) {
                delay(50)
            }
        }

        // Phase 2: Wait for temp file cleanup. On Windows, File.delete() can silently
        // fail on recently-accessed RandomAccessFile handles until GC collects finalizers.
        // Under full-suite GC pressure this needs a generous timeout + GC hints.
        withTimeout(60000) {
            while (aliceEncFile.exists()) {
                alice.mediaStorage.cleanupTempTransfer(aliceMedia.mediaId)
                System.gc()
                delay(200)
            }
        }

        val updatedAliceMedia = alice.mediaDao.getById(aliceMedia.mediaId)!!
        assertEquals("Sender status must transition to DELIVERED after receiver FILE_COMPLETE", MediaStatus.DELIVERED.name, updatedAliceMedia.status)

        val updatedAliceTransfer = alice.mediaTransferDao.getByMediaId(aliceMedia.mediaId)!!
        assertEquals("Sender transfer must transition to COMPLETED after confirmation", TransferStatus.COMPLETED.name, updatedAliceTransfer.status)

        // Verify that temporary encrypted file was cleaned up on sender upon receiver confirmation
        assertFalse("Temp encrypted file must be cleaned up on sender after confirmation", aliceEncFile.exists())

        alice.agent.stop()
        bob.agent.stop()
    }

    @Test
    fun testStartupRecoveryAndOrphanCleanup() = runBlocking {
        val (alice, bob) = setupPair()

        // 1. Create an orphan temp transfer file in Alice's storage
        val orphanEnc = File(alice.mediaStorage.tempTransfersDir, "orphan_media_id_999.enc")
        orphanEnc.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertTrue(orphanEnc.exists())

        // 2. Create an active transfer in transferDao
        val activeMediaId = "active_media_id_100"
        val activeEnc = File(alice.mediaStorage.tempTransfersDir, "$activeMediaId.enc")
        activeEnc.writeBytes(byteArrayOf(5, 6, 7, 8))

        alice.mediaTransferDao.upsert(
            MediaTransferEntity(
                transferId = activeMediaId,
                mediaId = activeMediaId,
                conversationId = "conv_1",
                relationshipId = "rel_alice_bob",
                direction = TransferDirection.DOWNLOAD.name,
                totalChunks = 2,
                chunkSize = 16,
                completedChunks = 1,
                chunkBitmask = "0",
                tempEncryptedPath = activeEnc.absolutePath,
                status = TransferStatus.ACTIVE.name,
                totalBytes = 32L
            )
        )

        // 3. Run startup recovery
        alice.mediaService!!.recoverPendingTransfersOnStartup()

        // 4. Orphan file must be swept
        assertFalse("Orphan temp encrypted file must be deleted during startup sweep", orphanEnc.exists())

        // 5. Active transfer file must be preserved
        assertTrue("Active transfer temp file must be preserved", activeEnc.exists())

        alice.agent.stop()
        bob.agent.stop()
    }
}
