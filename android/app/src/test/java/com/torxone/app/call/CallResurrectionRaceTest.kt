package com.torxone.app.call

import android.Manifest
import android.content.Context
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactDao
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.MessageRouter
import com.torxone.app.network.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallResurrectionRaceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var callScope: CoroutineScope

    private lateinit var context: Context
    private val db: AppDatabase = mock()
    private val contactDao: ContactDao = mock()
    private val messageRouter: MessageRouter = mock()

    private val testPeerKey = "peer_pubkey_123"
    private val testContact = ContactEntity(
        signingPublicKey = testPeerKey,
        encryptionPublicKey = "enc_pubkey_123",
        name = "Bob",
        endpointId = "ep1",
        onionAddress = "bob.onion",
        isConnected = true
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        callScope = CoroutineScope(SupervisorJob() + testDispatcher)
        context = RuntimeEnvironment.getApplication()
        val app = context as android.app.Application
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        whenever(messageRouter.getBestTransport(any())).thenReturn(Transport.NEARBY_DIRECT)
        whenever(db.contactDao()).thenReturn(contactDao)
        whenever(contactDao.getContact(testPeerKey)).thenReturn(testContact)
    }

    @After
    fun tearDown() {
        callScope.cancel()
        Dispatchers.resetMain()
    }

    private class ControllableCallEngine(
        override val capabilities: CallEngineCapabilities
    ) : CallEngine {
        val startDeferred = CompletableDeferred<CallStartResult>()
        val acceptDeferred = CompletableDeferred<CallStartResult>()
        val endCalled = AtomicBoolean(false)

        override fun isAvailable(context: CallRouteContext): Boolean = true

        override suspend fun startOutgoing(
            callId: String,
            contact: ContactEntity,
            context: CallRouteContext
        ): CallStartResult {
            return startDeferred.await()
        }

        override suspend fun acceptIncoming(
            callId: String,
            contact: ContactEntity,
            offer: AstraSessionDescription
        ): CallStartResult {
            return acceptDeferred.await()
        }

        override fun handleRemoteDescription(description: AstraSessionDescription) {}
        override suspend fun handleRenegotiationOffer(offer: AstraSessionDescription, peerKey: String, callId: String) {}
        override fun handleIceCandidate(candidate: AstraIceCandidate) {}

        override fun end() {
            endCalled.set(true)
        }
    }

    @Test
    fun testStartCall_endedBeforeStartOutgoingCompletes_dropsResultAndNeverResurrects() = runTest {
        val engine = ControllableCallEngine(
            CallEngineCapabilities(
                type = CallEngineType.WEBRTC,
                supportsLiveAudio = true,
                supportsLiveVideo = false,
                supportsAsyncSegments = false,
                supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
            )
        )

        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(engine),
            scopeOverride = callScope
        )

        // 1. User starts call
        callManager.startAudioCall(testPeerKey)
        testScheduler.runCurrent()

        // Verify initial state is Ringing
        val initialState = callManager.stateStore.state.value
        assertTrue("State should be Ringing initially, but was $initialState", initialState is CallUiState.Ringing)

        // 2. User presses End while startOutgoing is still suspended
        callManager.endCall("User ended call")
        testScheduler.runCurrent()

        val stateAfterEnd = callManager.stateStore.state.value
        assertTrue("State should be Ended after endCall", stateAfterEnd is CallUiState.Ended)
        assertEquals("User ended call", (stateAfterEnd as CallUiState.Ended).reason)

        // 3. startOutgoing finally completes in the background (e.g. 10 seconds later)
        engine.startDeferred.complete(
            CallStartResult.Started(
                callId = "stale-call-id",
                mode = CallMode.AUDIO,
                engineType = CallEngineType.WEBRTC
            )
        )
        testScheduler.runCurrent()

        // 4. Assert: State MUST remain Ended. Never resurrect to Negotiating or Connected!
        val stateAfterStaleResult = callManager.stateStore.state.value
        assertTrue(
            "State must remain Ended and NOT resurrect to Negotiating/Connected! Actual: $stateAfterStaleResult",
            stateAfterStaleResult is CallUiState.Ended
        )
        assertEquals("User ended call", (stateAfterStaleResult as CallUiState.Ended).reason)

        // 5. Assert: Lingering engine resources were torn down
        assertTrue("engine.end() must be called to clean up stale resources", engine.endCalled.get())
    }

    @Test
    fun testEndCall_thenRemoteEnd_thenOldCallback_noResurrectionOrCrash() = runTest {
        val engine = ControllableCallEngine(
            CallEngineCapabilities(
                type = CallEngineType.WEBRTC,
                supportsLiveAudio = true,
                supportsLiveVideo = false,
                supportsAsyncSegments = false,
                supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
            )
        )

        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(engine),
            scopeOverride = callScope
        )

        callManager.startAudioCall(testPeerKey)
        testScheduler.runCurrent()
        val callId = (callManager.stateStore.state.value as CallUiState.Ringing).callId

        // Local user ends call
        callManager.endCall("Local hangup")
        testScheduler.runCurrent()

        // Remote peer sends CALL_END packet after local end
        val remoteEndSignalJson = """
            {"callId":"$callId","mode":"AUDIO","reason":"Remote hung up"}
        """.trimIndent()
        callManager.handleSignal(com.torxone.app.network.MeshProtocol.TYPE_CALL_END, remoteEndSignalJson, testPeerKey)
        testScheduler.runCurrent()

        // Background startOutgoing finally completes
        engine.startDeferred.complete(
            CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
        )
        testScheduler.runCurrent()

        // State must remain Ended
        assertTrue(callManager.stateStore.state.value is CallUiState.Ended)
        assertTrue(engine.endCalled.get())
    }

    @Test
    fun testAcceptIncoming_endedBeforeAcceptCompletes_dropsResult() = runTest {
        val engine = ControllableCallEngine(
            CallEngineCapabilities(
                type = CallEngineType.WEBRTC,
                supportsLiveAudio = true,
                supportsLiveVideo = false,
                supportsAsyncSegments = false,
                supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
            )
        )

        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(engine),
            scopeOverride = callScope
        )

        // 1. Incoming call arrives
        val offerSignalJson = """
            {"callId":"incoming-call-123","mode":"AUDIO","sdp":"v=0..."}
        """.trimIndent()
        callManager.handleSignal(com.torxone.app.network.MeshProtocol.TYPE_CALL_OFFER, offerSignalJson, testPeerKey)
        testScheduler.runCurrent()

        assertTrue(callManager.stateStore.state.value is CallUiState.Ringing)

        // 2. User accepts call
        callManager.acceptIncomingCall()
        testScheduler.runCurrent()

        // 3. User immediately ends call while acceptIncoming is still executing
        callManager.endCall("Declined after accept")
        testScheduler.runCurrent()

        assertTrue(callManager.stateStore.state.value is CallUiState.Ended)

        // 4. acceptIncoming completes later
        engine.acceptDeferred.complete(
            CallStartResult.Started("incoming-call-123", CallMode.AUDIO, CallEngineType.WEBRTC)
        )
        testScheduler.runCurrent()

        // 5. Must remain Ended, engine cleaned up
        assertTrue(callManager.stateStore.state.value is CallUiState.Ended)
        assertTrue(engine.endCalled.get())
    }

    @Test
    fun testFallbackRace_endedDuringFallback_dropsResultAndCleansUp() = runTest {
        val primaryEngine = ControllableCallEngine(
            CallEngineCapabilities(
                type = CallEngineType.WEBRTC,
                supportsLiveAudio = true,
                supportsLiveVideo = false,
                supportsAsyncSegments = false,
                supportedTransports = setOf(Transport.NEARBY_DIRECT)
            )
        )

        val fallbackEngine = ControllableCallEngine(
            CallEngineCapabilities(
                type = CallEngineType.VOICE_NOTE,
                supportsLiveAudio = false,
                supportsLiveVideo = false,
                supportsAsyncSegments = true,
                supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
            )
        )

        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(primaryEngine, fallbackEngine),
            scopeOverride = callScope
        )

        // Start call
        callManager.startAudioCall(testPeerKey)
        testScheduler.runCurrent()

        // Primary engine requests fallback
        primaryEngine.startDeferred.complete(
            CallStartResult.Fallback(
                preferredEngine = CallEngineType.VOICE_NOTE,
                reason = "WebRTC failed over Tor"
            )
        )
        testScheduler.runCurrent()

        // User ends call while fallback is starting
        callManager.endCall("Cancelled during fallback")
        testScheduler.runCurrent()

        assertTrue(callManager.stateStore.state.value is CallUiState.Ended)

        // Fallback finishes starting
        fallbackEngine.startDeferred.complete(
            CallStartResult.Started("fallback-call-id", CallMode.VOICE_NOTE, CallEngineType.VOICE_NOTE)
        )
        testScheduler.runCurrent()

        // Must still be Ended, and fallback engine must be ended
        assertTrue(callManager.stateStore.state.value is CallUiState.Ended)
        assertTrue(fallbackEngine.endCalled.get())
    }
}
