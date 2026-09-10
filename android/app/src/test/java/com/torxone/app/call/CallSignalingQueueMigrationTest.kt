package com.torxone.app.call

import com.torxone.app.agent.EnvelopeType
import com.torxone.app.agent.TorXAgent
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.CallSignalingOutboxDao
import com.torxone.app.network.MeshProtocol
import com.torxone.app.network.MessageRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CallSignalingQueueMigrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val db: AppDatabase = mock()
    private val outboxDao: CallSignalingOutboxDao = mock()
    private val messageRouter: MessageRouter = mock()
    private val torXAgent: TorXAgent = mock()

    private val peerKey = "peer_pubkey_1234567890abcdef"
    private val callId = "call_abc_123"

    private lateinit var signalingHandler: CallSignalingHandler

    @Before
    fun setup() {
        whenever(db.callSignalingOutboxDao()).thenReturn(outboxDao)
        whenever(messageRouter.mySigningKeyHex).thenReturn("my_pubkey_1234567890abcdef")
        kotlinx.coroutines.runBlocking {
            whenever(messageRouter.buildEncryptedWireFrame(any(), any(), any())).thenAnswer { invocation ->
                "encrypted_wire_for_${invocation.getArgument<String>(2)}"
            }
        }

        signalingHandler = CallSignalingHandler(
            messageRouter = messageRouter,
            db = db,
            scopeOverride = testScope,
            ioDispatcherOverride = testDispatcher
        )
        signalingHandler.agent = torXAgent
    }

    @Test
    fun testSendOfferQueuesInTorXAgent() = runTest(testDispatcher) {
        val sdp = AstraSessionDescription("offer", "v=0\r\no=alice...")
        val signalId = signalingHandler.sendOffer(
            peerKey = peerKey,
            callId = callId,
            mode = CallMode.AUDIO,
            description = sdp
        )

        assertNotNull(signalId)

        // Verifies TorXAgent was called with EnvelopeType.CALL_OFFER
        verify(torXAgent).queueForDelivery(
            recipientKey = eq(peerKey),
            messageId = any(),
            messageType = eq(EnvelopeType.CALL_OFFER),
            encryptedPayload = argThat { contains("call_offer") }
        )

        // Verifies duplicate legacy outbox insertion was bypassed
        verify(outboxDao, never()).insertSignal(any())
    }

    @Test
    fun testSendAnswerQueuesInTorXAgent() = runTest(testDispatcher) {
        val sdp = AstraSessionDescription("answer", "v=0\r\no=bob...")
        val signalId = signalingHandler.sendAnswer(
            peerKey = peerKey,
            callId = callId,
            mode = CallMode.AUDIO,
            description = sdp
        )

        assertNotNull(signalId)

        verify(torXAgent).queueForDelivery(
            recipientKey = eq(peerKey),
            messageId = any(),
            messageType = eq(EnvelopeType.CALL_ANSWER),
            encryptedPayload = argThat { contains("call_answer") }
        )

        verify(outboxDao, never()).insertSignal(any())
    }

    @Test
    fun testSendIceCandidateQueuesInTorXAgent() = runTest(testDispatcher) {
        val candidate = AstraIceCandidate("audio", 0, "candidate:1 1 UDP 2130706431...")
        val signalId = signalingHandler.sendIceCandidate(
            peerKey = peerKey,
            callId = callId,
            mode = CallMode.AUDIO,
            candidate = candidate
        )

        assertNotNull(signalId)

        verify(torXAgent).queueForDelivery(
            recipientKey = eq(peerKey),
            messageId = any(),
            messageType = eq(EnvelopeType.ICE_CANDIDATE),
            encryptedPayload = argThat { contains("ice_candidate") }
        )

        verify(outboxDao, never()).insertSignal(any())
    }

    @Test
    fun testSendEndQueuesInTorXAgent() = runTest(testDispatcher) {
        val signalId = signalingHandler.sendEnd(
            peerKey = peerKey,
            callId = callId,
            mode = CallMode.AUDIO,
            reason = "hangup"
        )

        assertNotNull(signalId)

        verify(torXAgent).queueForDelivery(
            recipientKey = eq(peerKey),
            messageId = any(),
            messageType = eq(EnvelopeType.CALL_END),
            encryptedPayload = argThat { contains("call_end") }
        )

        verify(outboxDao, never()).insertSignal(any())
    }

    @Test
    fun testSendAckQueuesInTorXAgent() = runTest(testDispatcher) {
        signalingHandler.sendAck(
            peerKey = peerKey,
            callId = callId,
            ackSignalId = "sig_offer_123"
        )

        verify(torXAgent).queueForDelivery(
            recipientKey = eq(peerKey),
            messageId = any(),
            messageType = eq(EnvelopeType.CALL_ACK),
            encryptedPayload = argThat { contains("call_ack") }
        )
    }
}
