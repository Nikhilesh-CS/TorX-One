package com.torxone.app.service

import com.torxone.app.agent.EnvelopeType
import com.torxone.app.agent.TorXAgent
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.crypto.Identity
import com.torxone.app.data.*
import com.torxone.app.network.MeshProtocol
import com.torxone.app.network.Transport
import com.torxone.app.security.session.SessionCryptoService
import com.torxone.app.security.session.SessionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatMessageServiceTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val db: AppDatabase = mock()
    private val contactDao: ContactDao = mock()
    private val messageDao: MessageDao = mock()
    private val groupDao: GroupDao = mock()
    private val groupKeyDao: GroupKeyDao = mock()
    private val torXAgent: TorXAgent = mock()
    private val sessionCryptoService: SessionCryptoService = mock()

    private val identity = Identity(
        name = "Alice",
        signingPublicKey = ByteArray(32) { 1 },
        signingSecretKey = ByteArray(64) { 2 },
        encryptionPublicKey = ByteArray(32) { 3 },
        encryptionSecretKey = ByteArray(32) { 4 }
    )
    private val myKeyHex = CryptoManager.toHex(identity.signingPublicKey)

    private val peerKey = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val peerContact = ContactEntity(
        signingPublicKey = peerKey,
        encryptionPublicKey = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        name = "Bob"
    )

    private lateinit var chatMessageService: ChatMessageService

    @Before
    fun setup() {
        whenever(db.contactDao()).thenReturn(contactDao)
        whenever(db.messageDao()).thenReturn(messageDao)
        whenever(db.groupDao()).thenReturn(groupDao)
        whenever(db.groupKeyDao()).thenReturn(groupKeyDao)

        chatMessageService = ChatMessageService(
            scope = testScope,
            db = db,
            torXAgent = torXAgent,
            sessionCryptoService = sessionCryptoService,
            identityProvider = { identity },
            onionAddressProvider = { "alice.onion" }
        )
    }

    @Test
    fun testSendMessageQueuesDirectlyInTorXAgent() = runTest(testDispatcher) {
        whenever(contactDao.getContact(peerKey)).thenReturn(peerContact)
        val mockWireJson = "{\"type\":\"session_msg\",\"ciphertext\":\"abc\"}"
        whenever(sessionCryptoService.encrypt(eq(peerContact), any(), eq(MeshProtocol.TYPE_MSG), any()))
            .thenReturn(SessionManager.SessionWirePayload(mockWireJson, "sess-1", "msg-1"))

        val result = chatMessageService.sendMessage(peerKey, "Hello SimpleX!")

        assertTrue(result.success)
        assertEquals(Transport.PENDING, result.transport)

        // Verify message was saved to local Room DB with status 'sending'
        verify(messageDao).insertMessage(argThat {
            contactKey == peerKey && text == "Hello SimpleX!" && status == "sending" && direction == "sent"
        })

        // Verify message was queued strictly into TorXAgent delivery queue
        verify(torXAgent).queueForDelivery(
            recipientKey = eq(peerKey),
            messageId = any(),
            messageType = eq(EnvelopeType.MSG),
            encryptedPayload = eq(mockWireJson)
        )
    }

    @Test
    fun testSendRawPayloadQueuesProperEnvelopeType() = runTest(testDispatcher) {
        whenever(contactDao.getContact(peerKey)).thenReturn(peerContact)
        val mockWireJson = "{\"type\":\"presence_msg\"}"
        whenever(sessionCryptoService.encrypt(eq(peerContact), any(), eq(MeshProtocol.TYPE_PRESENCE), any()))
            .thenReturn(SessionManager.SessionWirePayload(mockWireJson, "sess-1", "msg-2"))

        val result = chatMessageService.sendRawPayload(peerKey, "presence_payload", MeshProtocol.TYPE_PRESENCE)

        assertTrue(result.success)
        assertEquals(Transport.PENDING, result.transport)

        // Verify presence was routed through TorXAgent with PRESENCE EnvelopeType
        verify(torXAgent).queueForDelivery(
            recipientKey = eq(peerKey),
            messageId = any(),
            messageType = eq(EnvelopeType.PRESENCE),
            encryptedPayload = eq(mockWireJson)
        )
    }

    @Test
    fun testSendGroupMessageDistributesPairwiseViaTorXAgent() = runTest(testDispatcher) {
        val groupId = "group-123"
        val group = GroupEntity(
            groupId = groupId,
            name = "Test Group",
            creatorKey = myKeyHex,
            myRole = "owner",
            createdAt = System.currentTimeMillis()
        )
        val member1 = GroupMemberEntity(groupId = groupId, memberKey = myKeyHex, role = "owner", joinedAt = 0)
        val member2 = GroupMemberEntity(groupId = groupId, memberKey = peerKey, role = "member", joinedAt = 0)
        val member3Key = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        val member3 = GroupMemberEntity(groupId = groupId, memberKey = member3Key, role = "member", joinedAt = 0)

        whenever(groupDao.getGroup(groupId)).thenReturn(group)
        whenever(groupDao.getGroupMember(groupId, myKeyHex)).thenReturn(member1)
        whenever(groupDao.getGroupMembersSync(groupId)).thenReturn(listOf(member1, member2, member3))

        val groupKey = GroupKeyEntity(
            groupId = groupId,
            keyVersion = 1,
            aesKeyBase64 = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=",
            distributedAt = System.currentTimeMillis()
        )
        whenever(groupKeyDao.getLatestKey(groupId)).thenReturn(groupKey)

        whenever(contactDao.getContact(peerKey)).thenReturn(peerContact)
        val contact3 = ContactEntity(signingPublicKey = member3Key, encryptionPublicKey = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", name = "Charlie")
        whenever(contactDao.getContact(member3Key)).thenReturn(contact3)

        whenever(sessionCryptoService.encrypt(any(), any(), eq(MeshProtocol.TYPE_GROUP_MESSAGE), any()))
            .thenAnswer { invocation ->
                val contact = invocation.getArgument<ContactEntity>(0)
                SessionManager.SessionWirePayload("{\"group_wire\":\"${contact.signingPublicKey}\"}", "sess", "id")
            }

        val result = chatMessageService.sendGroupMessage(groupId, "Group Announcement")

        assertTrue(result.success)
        assertEquals(Transport.PENDING, result.transport)

        // Verify group message queued for peer2 and peer3, but NOT self
        verify(torXAgent).queueForDelivery(
            recipientKey = eq(peerKey),
            messageId = any(),
            messageType = eq(EnvelopeType.GROUP_MESSAGE),
            encryptedPayload = argThat { contains(peerKey) }
        )

        verify(torXAgent).queueForDelivery(
            recipientKey = eq(member3Key),
            messageId = any(),
            messageType = eq(EnvelopeType.GROUP_MESSAGE),
            encryptedPayload = argThat { contains(member3Key) }
        )

        verify(torXAgent, never()).queueForDelivery(
            recipientKey = eq(myKeyHex),
            messageId = any(),
            messageType = any(),
            encryptedPayload = any()
        )
    }
}
