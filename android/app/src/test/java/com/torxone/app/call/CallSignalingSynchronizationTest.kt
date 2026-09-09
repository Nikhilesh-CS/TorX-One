package com.torxone.app.call

import android.Manifest
import android.content.Context
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.CallSignalingOutboxDao
import com.torxone.app.data.CallSignalingOutboxEntity
import com.torxone.app.data.ContactDao
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.MeshProtocol
import com.torxone.app.network.MessageRouter
import com.torxone.app.network.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallSignalingSynchronizationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var callScope: CoroutineScope
    private lateinit var context: Context

    private val db: AppDatabase = mock()
    private val contactDao: ContactDao = mock()
    private val messageRouter: MessageRouter = mock()
    private val fakeSignalingDao = FakeCallSignalingOutboxDao()

    private val testPeerKey = "peer_test_pubkey_12345"
    private val testContact = ContactEntity(
        signingPublicKey = testPeerKey,
        encryptionPublicKey = "enc_pubkey_test",
        name = "Alice",
        endpointId = "ep_alice",
        onionAddress = "alice.onion",
        isConnected = true
    )

    private val sentSignals = Collections.synchronizedList(mutableListOf<SentSignalRecord>())

    data class SentSignalRecord(
        val peerKey: String,
        val rawPayload: String,
        val messageType: String,
        val json: JSONObject = JSONObject(rawPayload)
    )

    class FakeCallSignalingOutboxDao : CallSignalingOutboxDao {
        val signals = ConcurrentHashMap<String, CallSignalingOutboxEntity>()

        override fun insertSignal(signal: CallSignalingOutboxEntity) {
            signals[signal.signalId] = signal
        }

        override fun getSignal(signalId: String): CallSignalingOutboxEntity? = signals[signalId]

        override fun getPendingSignals(now: Long, limit: Int): List<CallSignalingOutboxEntity> {
            return signals.values.filter { it.nextRetryAt <= now }.sortedBy { it.createdAt }.take(limit)
        }

        override fun getSignalsForCall(callId: String): List<CallSignalingOutboxEntity> {
            return signals.values.filter { it.callId == callId }
        }

        override fun deleteSignal(signalId: String) {
            signals.remove(signalId)
        }

        override fun deleteSignalsForCall(callId: String) {
            signals.entries.removeIf { it.value.callId == callId }
        }

        override fun updateRetry(signalId: String, nextRetryAt: Long) {
            signals[signalId]?.let {
                signals[signalId] = it.copy(retryCount = it.retryCount + 1, nextRetryAt = nextRetryAt)
            }
        }

        override fun purgeOldSignals(cutoff: Long) {
            signals.entries.removeIf { it.value.createdAt < cutoff }
        }
    }

    class TestEngine : CallEngine {
        override val capabilities = CallEngineCapabilities(
            type = CallEngineType.WEBRTC,
            supportsLiveAudio = true,
            supportsLiveVideo = false,
            supportsAsyncSegments = false,
            supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
        )

        val appliedCandidates = Collections.synchronizedList(mutableListOf<AstraIceCandidate>())
        val acceptedOffers = Collections.synchronizedList(mutableListOf<AstraSessionDescription>())
        val remoteDescriptions = Collections.synchronizedList(mutableListOf<AstraSessionDescription>())
        val isEnded = AtomicBoolean(false)
        val startedCount = AtomicInteger(0)

        override fun isAvailable(context: CallRouteContext): Boolean = true

        override suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult {
            startedCount.incrementAndGet()
            return CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
        }

        override suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult {
            acceptedOffers.add(offer)
            startedCount.incrementAndGet()
            return CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
        }

        override fun handleRemoteDescription(description: AstraSessionDescription) {
            remoteDescriptions.add(description)
        }

        override fun handleIceCandidate(candidate: AstraIceCandidate) {
            appliedCandidates.add(candidate)
        }

        override fun end() {
            isEnded.set(true)
        }
    }

    private lateinit var testEngine: TestEngine
    private lateinit var callManager: CallManager

    @Before
    fun setup() {
        sentSignals.clear()
        fakeSignalingDao.signals.clear()
        Dispatchers.setMain(testDispatcher)
        callScope = CoroutineScope(SupervisorJob() + testDispatcher)
        context = RuntimeEnvironment.getApplication()
        val app = context as android.app.Application
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        whenever(messageRouter.getBestTransport(any())).thenReturn(Transport.NEARBY_DIRECT)
        whenever(messageRouter.mySigningKeyHex).thenReturn("my_signing_key")
        whenever(db.contactDao()).thenReturn(contactDao)
        whenever(db.callSignalingOutboxDao()).thenReturn(fakeSignalingDao)
        whenever(contactDao.getContact(testPeerKey)).thenReturn(testContact)

        kotlinx.coroutines.runBlocking {
            whenever(messageRouter.sendRawPayload(any(), any(), any())).thenAnswer { invocation ->
                val peer = invocation.getArgument<String>(0)
                val raw = invocation.getArgument<String>(1)
                val type = invocation.getArgument<String>(2)
                sentSignals.add(SentSignalRecord(peer, raw, type))
                com.torxone.app.network.SendResult(true, Transport.NEARBY_DIRECT, "OK")
            }
        }

        testEngine = TestEngine()
        callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(testEngine),
            scopeOverride = callScope
        )
    }

    @After
    fun tearDown() {
        callScope.cancel()
        Dispatchers.resetMain()
    }

    // 1. Room-persist critical signaling outbox for OFFER
    @Test
    fun testOfferPersistedToRoomOutbox() = runTest(testDispatcher) {
        val signaling = CallSignalingHandler(messageRouter, db, callScope)
        val offerDesc = AstraSessionDescription("offer", "v=0\r\no=alice...")
        val signalId = signaling.sendOffer(testPeerKey, "call_1", CallMode.AUDIO, offerDesc, generation = 1L)
        testDispatcher.scheduler.advanceUntilIdle()

        val persisted = fakeSignalingDao.getSignal(signalId)
        assertNotNull("OFFER must be persisted to Room outbox", persisted)
        assertEquals("call_1", persisted?.callId)
        assertEquals("OFFER", persisted?.signalType)
        assertEquals(MeshProtocol.TYPE_CALL_OFFER, persisted?.messageType)
    }

    // 2. Room-persist critical signaling outbox for ANSWER
    @Test
    fun testAnswerPersistedToRoomOutbox() = runTest(testDispatcher) {
        val signaling = CallSignalingHandler(messageRouter, db, callScope)
        val answerDesc = AstraSessionDescription("answer", "v=0\r\no=bob...")
        val signalId = signaling.sendAnswer(testPeerKey, "call_2", CallMode.AUDIO, answerDesc, generation = 1L)
        testDispatcher.scheduler.advanceUntilIdle()

        val persisted = fakeSignalingDao.getSignal(signalId)
        assertNotNull("ANSWER must be persisted to Room outbox", persisted)
        assertEquals("call_2", persisted?.callId)
        assertEquals("ANSWER", persisted?.signalType)
    }

    // 3. Room-persist critical signaling outbox for END
    @Test
    fun testEndPersistedToRoomOutbox() = runTest(testDispatcher) {
        val signaling = CallSignalingHandler(messageRouter, db, callScope)
        val signalId = signaling.sendEnd(testPeerKey, "call_3", CallMode.AUDIO, "Hangup", generation = 1L)
        testDispatcher.scheduler.advanceUntilIdle()

        val persisted = fakeSignalingDao.getSignal(signalId)
        assertNotNull("END must be persisted to Room outbox", persisted)
        assertEquals("call_3", persisted?.callId)
        assertEquals("END", persisted?.signalType)
    }

    // 4. ICE candidate transmitted transiently (not inserted into Room outbox)
    @Test
    fun testIceCandidateTransmittedMemoryOnly() = runTest(testDispatcher) {
        val signaling = CallSignalingHandler(messageRouter, db, callScope)
        val candidate = AstraIceCandidate("audio", 0, "candidate:1 1 UDP...")
        val signalId = signaling.sendIceCandidate(testPeerKey, "call_4", CallMode.AUDIO, candidate, generation = 1L)
        testDispatcher.scheduler.advanceUntilIdle()

        val persisted = fakeSignalingDao.getSignal(signalId)
        assertNull("ICE candidates should NOT be persisted to Room outbox", persisted)
    }

    // 5. Receiving ACK removes OFFER from Room outbox
    @Test
    fun testOfferAckDeletesPersistedOffer() = runTest(testDispatcher) {
        val signaling = CallSignalingHandler(messageRouter, db, callScope)
        val offerDesc = AstraSessionDescription("offer", "v=0\r\no=alice...")
        val signalId = signaling.sendOffer(testPeerKey, "call_5", CallMode.AUDIO, offerDesc, generation = 1L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(fakeSignalingDao.getSignal(signalId))

        val ackSignal = CallSignal(
            callId = "call_5",
            ackSignalId = signalId,
            ackType = "OFFER_ACK"
        )
        signaling.handleAck(ackSignal)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull("Persisted OFFER must be deleted after receiving OFFER_ACK", fakeSignalingDao.getSignal(signalId))
    }

    // 6. Early ICE candidate queuing before accept
    @Test
    fun testEarlyIceCandidatesQueuedBeforeAccept() = runTest(testDispatcher) {
        val callId = "call_early_ice"
        val offerPayload = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_offer")
            .put("generation", 1L)
            .put("seq", 1)
            .put("mode", "AUDIO")
            .put("sdp", "v=0\r\no=alice...")
            .toString()

        // 1. Offer arrives while user has not accepted yet (Ringing state)
        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerPayload, testPeerKey)
        testDispatcher.scheduler.runCurrent()
        assertTrue(callManager.stateStore.state.value is CallUiState.Ringing)

        // 2. Peer immediately sends ICE candidates before user accepts
        val icePayload1 = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_ice_1")
            .put("generation", 1L)
            .put("seq", 2)
            .put("mode", "AUDIO")
            .put("candidate", "candidate:1 1 UDP 2122260223 192.168.1.100 50000 typ host")
            .put("sdpMid", "audio")
            .put("sdpMLineIndex", 0)
            .toString()

        callManager.handleSignal(MeshProtocol.TYPE_ICE_CANDIDATE, icePayload1, testPeerKey)
        testDispatcher.scheduler.runCurrent()

        // TestEngine has not started yet (waiting for user accept)
        assertEquals(0, testEngine.appliedCandidates.size)

        // 3. User accepts call -> early ICE candidate must be drained into engine
        callManager.acceptIncomingCall()
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, testEngine.appliedCandidates.size)
        assertEquals("audio", testEngine.appliedCandidates[0].sdpMid)
    }

    // 9. Signal ordering: seq 1, seq 3, seq 2
    @Test
    fun testSignalOrderingBuffering() = runTest(testDispatcher) {
        val callId = "call_seq_ordering"
        val offerPayload = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_offer")
            .put("generation", 1L)
            .put("seq", 1)
            .put("mode", "AUDIO")
            .put("sdp", "v=0\r\no=alice...")
            .toString()

        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerPayload, testPeerKey)
        testDispatcher.scheduler.runCurrent()
        callManager.acceptIncomingCall()
        testDispatcher.scheduler.runCurrent()

        // Now arrive out of order: seq 3 arrives before seq 2
        val icePayload3 = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_ice_3")
            .put("generation", 1L)
            .put("seq", 3)
            .put("mode", "AUDIO")
            .put("candidate", "candidate:3 1 UDP 2122260223 192.168.1.100 50002 typ host")
            .put("sdpMid", "audio")
            .put("sdpMLineIndex", 0)
            .toString()

        callManager.handleSignal(MeshProtocol.TYPE_ICE_CANDIDATE, icePayload3, testPeerKey)
        testDispatcher.scheduler.runCurrent()

        // seq 3 should be buffered, not applied yet!
        assertTrue("Seq 3 must not be applied before seq 2", testEngine.appliedCandidates.none { it.sdp.contains("candidate:3") })

        // Now seq 2 arrives
        val icePayload2 = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_ice_2")
            .put("generation", 1L)
            .put("seq", 2)
            .put("mode", "AUDIO")
            .put("candidate", "candidate:2 1 UDP 2122260223 192.168.1.100 50001 typ host")
            .put("sdpMid", "audio")
            .put("sdpMLineIndex", 0)
            .toString()

        callManager.handleSignal(MeshProtocol.TYPE_ICE_CANDIDATE, icePayload2, testPeerKey)
        testDispatcher.scheduler.runCurrent()

        // Both seq 2 and then seq 3 must now be applied in proper order
        assertEquals(2, testEngine.appliedCandidates.size)
        assertTrue(testEngine.appliedCandidates[0].sdp.contains("candidate:2"))
        assertTrue(testEngine.appliedCandidates[1].sdp.contains("candidate:3"))
    }

    // 10. Call restart generation: generation 1 arrives late after generation 2
    @Test
    fun testCallRestartGenerationLateSignalIgnored() = runTest(testDispatcher) {
        val callId = "call_gen_test"

        // Gen 2 arrives first
        val offerGen2 = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_gen_2")
            .put("generation", 2L)
            .put("seq", 1)
            .put("mode", "AUDIO")
            .put("sdp", "v=0\r\no=alice_gen2...")
            .toString()

        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerGen2, testPeerKey)
        testDispatcher.scheduler.runCurrent()
        callManager.acceptIncomingCall()
        testDispatcher.scheduler.runCurrent()

        val initialCandidatesCount = testEngine.appliedCandidates.size

        // Late Gen 1 candidate arrives
        val lateIceGen1 = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_late_gen_1")
            .put("generation", 1L)
            .put("seq", 5)
            .put("mode", "AUDIO")
            .put("candidate", "candidate:late 1 UDP 2122260223 192.168.1.100 50000 typ host")
            .put("sdpMid", "audio")
            .put("sdpMLineIndex", 0)
            .toString()

        callManager.handleSignal(MeshProtocol.TYPE_ICE_CANDIDATE, lateIceGen1, testPeerKey)
        testDispatcher.scheduler.runCurrent()

        // Verify late generation-1 signal was ignored
        assertEquals("Late generation 1 signal must be ignored", initialCandidatesCount, testEngine.appliedCandidates.size)

        // Verify STALE_GEN_ACK was sent back so sender ceases retrying
        val staleAck = sentSignals.firstOrNull { it.json.optString("ackSignalId") == "sig_late_gen_1" }
        assertNotNull("STALE_GEN_ACK must be returned", staleAck)
        assertEquals("STALE_GEN_ACK", staleAck?.json?.optString("ackType"))
    }

    // 11. Lost ACK: sender retries duplicate OFFER, recipient re-ACKs and does NOT process twice
    @Test
    fun testLostAckDuplicateOfferHandling() = runTest(testDispatcher) {
        val callId = "call_lost_ack"
        val offerPayload = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_offer_lost_ack")
            .put("generation", 1L)
            .put("seq", 1)
            .put("mode", "AUDIO")
            .put("sdp", "v=0\r\no=alice...")
            .toString()

        // 1. Initial OFFER arrives
        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerPayload, testPeerKey)
        testDispatcher.scheduler.runCurrent()

        val firstAckCount = sentSignals.count { it.json.optString("ackSignalId") == "sig_offer_lost_ack" }
        assertEquals(1, firstAckCount)

        // 2. Sender retries exact same OFFER because ACK was lost
        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerPayload, testPeerKey)
        testDispatcher.scheduler.runCurrent()

        // Recipient re-sends ACK
        val secondAckCount = sentSignals.count { it.json.optString("ackSignalId") == "sig_offer_lost_ack" }
        assertEquals(2, secondAckCount)

        // State is still Ringing, NOT processed twice
        assertTrue(callManager.stateStore.state.value is CallUiState.Ringing)
    }

    // 13. ICE flood: No duplicate candidate application & queue bounded
    @Test
    fun testIceFloodDeduplicationAndBoundedQueue() = runTest(testDispatcher) {
        val callId = "call_ice_flood"
        val offerPayload = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_offer_flood")
            .put("generation", 1L)
            .put("seq", 1)
            .put("mode", "AUDIO")
            .put("sdp", "v=0\r\no=alice...")
            .toString()

        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerPayload, testPeerKey)
        testDispatcher.scheduler.runCurrent()

        // Flood 150 candidates (many duplicates) while still ringing
        for (i in 1..150) {
            val candidateId = i % 20 // Only 20 unique candidates repeated
            val icePayload = JSONObject()
                .put("callId", callId)
                .put("signalId", "sig_ice_flood_$i")
                .put("generation", 1L)
                .put("seq", 1 + i)
                .put("mode", "AUDIO")
                .put("candidate", "candidate:flood_$candidateId 1 UDP 2122260223 192.168.1.100 50000 typ host")
                .put("sdpMid", "audio")
                .put("sdpMLineIndex", 0)
                .toString()

            callManager.handleSignal(MeshProtocol.TYPE_ICE_CANDIDATE, icePayload, testPeerKey)
        }
        testDispatcher.scheduler.runCurrent()

        // Accept call
        callManager.acceptIncomingCall()
        testDispatcher.scheduler.runCurrent()

        // The applied candidates must be deduplicated (at most 20, not 150)
        assertEquals(20, testEngine.appliedCandidates.size)
    }

    // 14. Call end race: END, ICE, ANSWER arriving out of order; END prevents resurrection
    @Test
    fun testCallEndRaceNoResurrection() = runTest(testDispatcher) {
        val callId = "call_end_race"
        val offerPayload = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_offer_end_race")
            .put("generation", 1L)
            .put("seq", 1)
            .put("mode", "AUDIO")
            .put("sdp", "v=0\r\no=alice...")
            .toString()

        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerPayload, testPeerKey)
        testDispatcher.scheduler.runCurrent()

        // 1. Authoritative CALL_END arrives
        val endPayload = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_end")
            .put("generation", 1L)
            .put("seq", 10)
            .put("reason", "Caller cancelled")
            .toString()

        callManager.handleSignal(MeshProtocol.TYPE_CALL_END, endPayload, testPeerKey)
        testDispatcher.scheduler.runCurrent()

        assertTrue(callManager.stateStore.state.value is CallUiState.Ended)

        // 2. Stale ICE candidate arrives after END
        val staleIce = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_stale_ice")
            .put("generation", 1L)
            .put("seq", 5)
            .put("mode", "AUDIO")
            .put("candidate", "candidate:stale 1 UDP 2122260223 192.168.1.100 50000 typ host")
            .put("sdpMid", "audio")
            .put("sdpMLineIndex", 0)
            .toString()

        callManager.handleSignal(MeshProtocol.TYPE_ICE_CANDIDATE, staleIce, testPeerKey)
        testDispatcher.scheduler.runCurrent()

        // 3. Stale ANSWER arrives after END
        val staleAnswer = JSONObject()
            .put("callId", callId)
            .put("signalId", "sig_stale_answer")
            .put("generation", 1L)
            .put("seq", 2)
            .put("mode", "AUDIO")
            .put("sdp", "v=0\r\no=bob_stale...")
            .toString()

        callManager.handleSignal(MeshProtocol.TYPE_CALL_ANSWER, staleAnswer, testPeerKey)
        testDispatcher.scheduler.runCurrent()

        // Must NOT resurrect call; call must remain in Ended or reset Idle
        assertFalse("Stale signals must not resurrect call", callManager.stateStore.state.value is CallUiState.Connected)
        assertFalse("Stale signals must not resurrect call", callManager.stateStore.state.value is CallUiState.Accepted)
        assertFalse("Stale signals must not resurrect call", callManager.stateStore.state.value is CallUiState.Ringing)

        // Verify TERMINATED_ACK was sent for the stale signals
        val termAckIce = sentSignals.firstOrNull { it.json.optString("ackSignalId") == "sig_stale_ice" }
        assertNotNull("TERMINATED_ACK must be sent for stale ICE", termAckIce)
        assertEquals("TERMINATED_ACK", termAckIce?.json?.optString("ackType"))
    }
}
