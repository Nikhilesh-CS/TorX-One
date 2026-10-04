package com.torxone.app.media

import com.torxone.app.connection.Connection
import com.torxone.app.crypto.EncryptedSessionMessage
import com.torxone.app.data.entity.*
import com.torxone.app.groups.TestContactDao
import com.torxone.app.groups.TestGroupDao
import com.torxone.app.groups.TestGroupMessageDeliveryDao
import com.torxone.app.protocol.*
import com.torxone.app.transport.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class GroupMediaLifecycleTest {
    private class Harness(names: List<String>) {
        val sender = MediaTransferTest.TestNode("group_sender")
        val peers = names.associateWith { MediaTransferTest.TestNode("group_$it") }
        val contacts = TestContactDao()
        val groups = TestGroupDao()
        val deliveries = TestGroupMessageDeliveryDao()
        val connections = mutableMapOf<String, Connection>()
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val entered = ConcurrentHashMap.newKeySet<String>()
        val cancelled = ConcurrentHashMap.newKeySet<String>()
        val sent = ConcurrentHashMap<String, CopyOnWriteArrayList<Int>>()
        lateinit var service: MediaService
        lateinit var media: MediaEntity
        val groupId = "test-group"
        val metadata = GroupEnvelopeMetadata(groupId, 7, 7)

        val dedicated = object : DedicatedMediaTransport {
            override suspend fun send(destination: TransportDestination, frame: DedicatedMediaFrame): TransportResult {
                val relationship = requireNotNull(destination.relationshipId)
                entered += relationship
                try { gates[relationship]?.await() }
                catch (error: CancellationException) { cancelled += relationship; throw error }
                val saved = sender.mediaDao.getById(frame.mediaId)!!
                val bytes = DedicatedMediaChunkCrypto.decrypt(saved.mediaKey, frame.mediaId, relationship,
                    frame.chunkIndex, frame.totalChunks, frame.encryptedChunk)
                val transfer = sender.mediaTransferDao.getByMediaIdAndRelationship(frame.mediaId, relationship)!!
                assertArrayEquals(sender.mediaStorage.readChunk(File(transfer.tempEncryptedPath), frame.chunkIndex,
                    transfer.chunkSize, transfer.totalBytes), bytes)
                sent.computeIfAbsent(relationship) { CopyOnWriteArrayList() }.add(frame.chunkIndex)
                return TransportResult.Accepted(TransportType.TOR)
            }
        }

        suspend fun initialize() {
            sender.convDao.upsert(ConversationEntity(groupId, ConversationType.GROUP, "Group"))
            groups.upsert(GroupEntity(groupId, groupId, "Group", creatorIdentityId = sender.identityId, epoch = 7))
            for ((name, peer) in peers) {
                val relationship = relationship(name)
                val secret = ByteArray(32) { (name.hashCode() + it).toByte() }
                sender.crypto.initializeSession(relationship, secret, true, peer.ratchetKey.publicKey,
                    sender.ratchetKey.privateKey, sender.ratchetKey.publicKey)
                peer.crypto.initializeSession(relationship, secret, false, sender.ratchetKey.publicKey,
                    peer.ratchetKey.privateKey, peer.ratchetKey.publicKey)
                val connection = Connection(relationshipId = relationship, generation = 1,
                    sendQueueId = "queue-$name", recvQueueId = "return-$name", sendAuth = ByteArray(32) { 1 },
                    recvAuth = ByteArray(32) { 2 })
                connections[name] = connection
                sender.connManager.registerConnection(connection)
                contacts.upsert(ContactEntity("contact-$name", relationship, name,
                    signingPublicKey = peer.identityKey.publicKey, conversationId = "direct-$name",
                    remoteIdentityId = peer.identityId))
            }
            service = createService(dedicated)
            val messageId = service.sendGroupMedia(groupId, 7, sender.identityId,
                peers.map { (name, peer) -> MediaService.GroupMediaRecipient(peer.identityId, relationship(name)) },
                MediaType.DOCUMENT, "private-document.bin", "application/octet-stream",
                ByteArray(MediaService.DEFAULT_CHUNK_SIZE * 3) { (it % 251).toByte() })
            media = sender.mediaDao.getByMessageId(messageId)!!
        }

        fun createService(transport: DedicatedMediaTransport?) = MediaService(
            sessionCrypto = sender.crypto, connectionManager = sender.connManager, agent = sender.agent,
            messageDao = sender.msgDao, conversationDao = sender.convDao, mediaDao = sender.mediaDao,
            mediaTransferDao = sender.mediaTransferDao, outboxDao = sender.outboxDao,
            localIdentityIdProvider = { sender.identityId }, mediaStorage = sender.mediaStorage,
            contactDao = contacts, sessionStore = sender.sessionStore, dedicatedMediaTransport = transport,
            groupMessageDeliveryDao = deliveries, groupDao = groups, chunkRetryBaseMs = 10)

        fun relationship(name: String) = "relationship-$name"
        suspend fun transfer(name: String) = sender.mediaTransferDao
            .getByMediaIdAndRelationship(media.mediaId, relationship(name))!!

        fun envelope(name: String, type: MessageType) = SecureEnvelope(conversationId = groupId,
            senderIdentity = peers.getValue(name).identityId, recipientBinding = sender.identityId,
            messageType = type, groupMetadata = metadata, payload = ByteArray(0))

        suspend fun accept(name: String) = assertTrue(service.handleIncomingAccept(connections.getValue(name),
            envelope(name, MessageType.FILE_ACCEPT), MediaAcceptPayload(media.mediaId)))

        suspend fun complete(name: String) = assertTrue(service.handleIncomingCompletion(connections.getValue(name),
            envelope(name, MessageType.FILE_COMPLETE), MediaCompletePayload(media.mediaId, media.encryptedSha256)))

        suspend fun seed(name: String, chunks: Set<Int>, status: TransferStatus) {
            val saved = transfer(name)
            val bytes = chunks.sumOf { minOf(saved.chunkSize.toLong(), saved.totalBytes - it.toLong() * saved.chunkSize) }
            sender.mediaTransferDao.updateProgress(saved.transferId, chunks.size, chunks.sorted().joinToString(","), bytes, status.name)
        }

        suspend fun waitForUploads(vararg names: String) = withTimeout(10_000) {
            while (names.any { transfer(it).completedChunks != transfer(it).totalChunks }) delay(5)
        }

        suspend fun close() {
            if (::media.isInitialized) service.cancelTransfer(media.mediaId, notifyPeer = false)
            sender.agent.stop()
            for ((name, peer) in peers) {
                sender.crypto.closeSession(relationship(name))
                peer.crypto.closeSession(relationship(name))
                peer.agent.stop()
            }
        }
    }

    @Test fun pauseAndResumePreserveEachRecipientsBitmapAndCompletedProof() = runBlocking {
        val h = Harness(listOf("bob", "charlie", "dave"))
        try {
            h.initialize()
            h.seed("charlie", setOf(0), TransferStatus.ACTIVE)
            h.complete("dave")
            for (name in listOf("bob", "charlie")) h.gates[h.relationship(name)] = CompletableDeferred()
            h.accept("bob"); h.accept("charlie")
            withTimeout(5_000) { while (h.entered.size < 2) delay(5) }
            h.service.pauseTransfer(h.media.mediaId)
            withTimeout(5_000) { while (h.cancelled.size < 2) delay(5) }

            assertEquals(TransferStatus.PAUSED.name, h.transfer("bob").status)
            assertEquals("", h.transfer("bob").chunkBitmask)
            assertEquals(TransferStatus.PAUSED.name, h.transfer("charlie").status)
            assertEquals(MediaStatus.PAUSED.name, h.sender.mediaDao.getById(h.media.mediaId)!!.status)
            assertEquals("0", h.transfer("charlie").chunkBitmask)
            assertEquals(TransferStatus.COMPLETED.name, h.transfer("dave").status)
            h.service.recoverDeliveryPath()
            h.service.recoverPendingTransfersOnStartup()
            assertTrue(h.sent.isEmpty())

            h.gates.clear()
            h.service.resumeTransfer(h.media.mediaId)
            h.waitForUploads("bob", "charlie")
            assertEquals((0 until h.transfer("bob").totalChunks).toList(), h.sent[h.relationship("bob")]!!.toList())
            assertEquals((1 until h.transfer("charlie").totalChunks).toList(), h.sent[h.relationship("charlie")]!!.toList())
            assertNull(h.sent[h.relationship("dave")])
            assertNotEquals(MediaStatus.DELIVERED.name, h.sender.mediaDao.getById(h.media.mediaId)!!.status)
            assertTrue(File(h.transfer("bob").tempEncryptedPath).isFile)
            h.complete("bob")
            assertTrue(File(h.transfer("charlie").tempEncryptedPath).isFile)
            h.complete("charlie")
            assertEquals(MediaStatus.DELIVERED.name, h.sender.mediaDao.getById(h.media.mediaId)!!.status)
            assertFalse(File(h.transfer("charlie").tempEncryptedPath).exists())
        } finally { h.close() }
    }

    @Test fun authenticatedRecipientCancelDoesNotCancelOtherRecipientsOrDiscardSharedCiphertext() = runBlocking {
        val h = Harness(listOf("bob", "charlie"))
        try {
            h.initialize()
            for (name in h.peers.keys) h.gates[h.relationship(name)] = CompletableDeferred()
            h.accept("bob"); h.accept("charlie")
            withTimeout(5_000) { while (h.entered.size < 2) delay(5) }
            assertTrue(h.service.handleIncomingCancel(h.connections.getValue("bob"),
                h.envelope("bob", MessageType.FILE_CANCEL), h.media.mediaId))
            withTimeout(5_000) { while (h.relationship("bob") !in h.cancelled) delay(5) }
            assertEquals(TransferStatus.CANCELLED.name, h.transfer("bob").status)
            assertEquals(TransferStatus.ACTIVE.name, h.transfer("charlie").status)
            assertFalse(h.relationship("charlie") in h.cancelled)
            assertTrue(File(h.transfer("charlie").tempEncryptedPath).isFile)

            h.service.pauseTransfer(h.media.mediaId)
            h.gates.clear()
            h.service.resumeTransfer(h.media.mediaId)
            h.waitForUploads("charlie")
            assertNull(h.sent[h.relationship("bob")])
            assertEquals(TransferStatus.CANCELLED.name, h.transfer("bob").status)
            h.complete("charlie")
            assertNotEquals(MediaStatus.DELIVERED.name, h.sender.mediaDao.getById(h.media.mediaId)!!.status)
            assertFalse(File(h.transfer("charlie").tempEncryptedPath).exists())
        } finally { h.close() }
    }

    @Test fun localCancelStopsAllPendingRecipientsAndPreservesCompletedRecipient() = runBlocking {
        val h = Harness(listOf("bob", "charlie", "dave"))
        try {
            h.initialize()
            h.complete("dave")
            for (name in listOf("bob", "charlie")) h.gates[h.relationship(name)] = CompletableDeferred()
            h.accept("bob"); h.accept("charlie")
            withTimeout(5_000) { while (h.entered.size < 2) delay(5) }
            h.service.cancelTransfer(h.media.mediaId, notifyPeer = false)
            withTimeout(5_000) { while (h.cancelled.size < 2) delay(5) }
            assertEquals(TransferStatus.CANCELLED.name, h.transfer("bob").status)
            assertEquals(TransferStatus.CANCELLED.name, h.transfer("charlie").status)
            assertEquals(TransferStatus.COMPLETED.name, h.transfer("dave").status)
            assertFalse(File(h.transfer("bob").tempEncryptedPath).exists())
            assertNotEquals(MediaStatus.DELIVERED.name, h.sender.mediaDao.getById(h.media.mediaId)!!.status)
        } finally { h.close() }
    }

    @Test fun startupRecoveryContainsMissingPeerAndVerifiedRecoveryUsesOnlyThatPeersMissingChunks() = runBlocking {
        val h = Harness(listOf("bob", "charlie"))
        try {
            h.initialize()
            h.seed("bob", setOf(0), TransferStatus.QUEUED)
            h.seed("charlie", setOf(0, 1), TransferStatus.QUEUED)
            h.sender.connManager.closeConnection(h.relationship("bob"))
            h.service.recoverPendingTransfersOnStartup()
            h.waitForUploads("charlie")
            assertEquals("0", h.transfer("bob").chunkBitmask)
            assertNull(h.sent[h.relationship("bob")])
            val charlieSent = h.sent[h.relationship("charlie")]!!.toList()
            assertEquals((2 until h.transfer("charlie").totalChunks).toList(), charlieSent)
            h.sender.connManager.registerConnection(h.connections.getValue("bob"))
            h.service.recoverDeliveryPath(h.relationship("bob"))
            h.waitForUploads("bob")
            assertEquals((1 until h.transfer("bob").totalChunks).toList(), h.sent[h.relationship("bob")]!!.toList())
            assertEquals(charlieSent, h.sent[h.relationship("charlie")]!!.toList())
            assertTrue(File(h.transfer("bob").tempEncryptedPath).isFile)
        } finally { h.close() }
    }

    @Test fun legacyResumeRetainsBitmapAndAuthenticatedGroupMetadata() = runBlocking {
        val h = Harness(listOf("bob", "charlie"))
        try {
            h.initialize()
            h.seed("bob", setOf(0), TransferStatus.PAUSED)
            h.seed("charlie", setOf(0, 1), TransferStatus.PAUSED)
            h.service = h.createService(null)
            h.service.resumeTransfer(h.media.mediaId)
            h.waitForUploads("bob", "charlie")
            for ((name, peer) in h.peers) {
                val connection = h.connections.getValue(name)
                val envelopes = h.sender.outboxDao.items.values.filter { it.relationshipId == h.relationship(name) }
                    .sortedBy { it.applicationSequence }.map { item ->
                        ProtocolCodec.decodeSecureEnvelope(peer.crypto.decrypt(h.relationship(name),
                            EncryptedSessionMessage.deserialize(item.ciphertext),
                            "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray()))
                    }
                val chunks = envelopes.filter { it.messageType == MessageType.FILE_PROGRESS }
                assertTrue(chunks.all { it.groupMetadata == h.metadata })
                val firstMissing = if (name == "bob") 1 else 2
                assertEquals((firstMissing until h.transfer(name).totalChunks).toList(),
                    chunks.map { MediaProtocolCodec.decodeChunk(it.payload).chunkIndex })
                assertEquals((0 until h.transfer(name).totalChunks).joinToString(","), h.transfer(name).chunkBitmask)
            }
            assertNotEquals(MediaStatus.DELIVERED.name, h.sender.mediaDao.getById(h.media.mediaId)!!.status)
            assertTrue(File(h.transfer("bob").tempEncryptedPath).isFile)
        } finally { h.close() }
    }
}
