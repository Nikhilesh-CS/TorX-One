package com.torxone.app.call

import android.Manifest
import android.content.Context
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactDao
import com.torxone.app.data.ContactEntity
import com.torxone.app.network.MeshProtocol
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingCallRingingTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var callScope: CoroutineScope
    private lateinit var context: Context
    private val db: AppDatabase = mock()
    private val contactDao: ContactDao = mock()
    private val messageRouter: MessageRouter = mock()

    private val testPeerKey = "peer_pubkey_rahul"
    private val testContact = ContactEntity(
        signingPublicKey = testPeerKey,
        encryptionPublicKey = "enc_pubkey_rahul",
        name = "Rahul",
        endpointId = "ep1",
        onionAddress = "rahul.onion",
        isConnected = true
    )

    private class MockCallRingtoneManager(context: Context) : CallRingtoneManager(context) {
        val startCalled = AtomicBoolean(false)
        val stopCalled = AtomicBoolean(false)

        override fun start() {
            startCalled.set(true)
            isRinging = true
        }

        override fun stop() {
            stopCalled.set(true)
            isRinging = false
        }
    }

    private class ControllableCallEngine(
        override val capabilities: CallEngineCapabilities
    ) : CallEngine {
        val acceptDeferred = CompletableDeferred<CallStartResult>()
        val endCalled = AtomicBoolean(false)

        override fun isAvailable(context: CallRouteContext): Boolean = true
        override suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult =
            CallStartResult.Failed("Not used")

        override suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult =
            acceptDeferred.await()

        override fun handleRemoteDescription(description: AstraSessionDescription) {}
        override suspend fun handleRenegotiationOffer(offer: AstraSessionDescription, peerKey: String, callId: String) {}
        override fun handleIceCandidate(candidate: AstraIceCandidate) {}
        override fun end() { endCalled.set(true) }
    }

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

    @Test
    fun testIncomingOffer_triggersRingingAndStartsRingtoneImmediately() = runTest {
        val engine = ControllableCallEngine(
            CallEngineCapabilities(
                type = CallEngineType.WEBRTC,
                supportsLiveAudio = true,
                supportsLiveVideo = false,
                supportsAsyncSegments = false,
                supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
            )
        )
        val ringtoneManager = MockCallRingtoneManager(context)

        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(engine),
            scopeOverride = callScope,
            ringtoneManagerOverride = ringtoneManager
        )

        val offerJson = """
            {"callId":"call-rahul-1","mode":"AUDIO","sdp":"v=0..."}
        """.trimIndent()
        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerJson, testPeerKey)
        testScheduler.runCurrent()

        // 1. UI state must be Ringing immediately
        val state = callManager.stateStore.state.value
        assertTrue("State should be Ringing, was $state", state is CallUiState.Ringing)
        val ringing = state as CallUiState.Ringing
        assertEquals(CallDirection.INCOMING, ringing.direction)
        assertEquals("Rahul", ringing.peerName)

        // 2. Ringtone must have started
        assertTrue("Ringtone should start immediately on offer", ringtoneManager.startCalled.get())
        assertTrue("Ringtone should be reported as ringing", ringtoneManager.isRinging)
    }

    @Test
    fun testAcceptIncoming_stopsRingtoneImmediately() = runTest {
        val engine = ControllableCallEngine(
            CallEngineCapabilities(
                type = CallEngineType.WEBRTC,
                supportsLiveAudio = true,
                supportsLiveVideo = false,
                supportsAsyncSegments = false,
                supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
            )
        )
        val ringtoneManager = MockCallRingtoneManager(context)

        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(engine),
            scopeOverride = callScope,
            ringtoneManagerOverride = ringtoneManager
        )

        val offerJson = """
            {"callId":"call-rahul-2","mode":"AUDIO","sdp":"v=0..."}
        """.trimIndent()
        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerJson, testPeerKey)
        testScheduler.runCurrent()

        // Accept call (engine.acceptDeferred is NOT yet completed)
        callManager.acceptIncomingCall()
        testScheduler.runCurrent()

        // Ringtone must be stopped immediately upon accept, before WebRTC engine completes
        assertTrue("Ringtone must stop immediately on accept", ringtoneManager.stopCalled.get())
        assertFalse("Ringtone must not be ringing", ringtoneManager.isRinging)

        // State must transition to Accepted (Connecting...)
        val state = callManager.stateStore.state.value
        assertTrue("State should be Accepted, was $state", state is CallUiState.Accepted)
    }

    @Test
    fun testDeclineIncoming_stopsRingtoneAndSendsCallEnd() = runTest {
        val engine = ControllableCallEngine(
            CallEngineCapabilities(
                type = CallEngineType.WEBRTC,
                supportsLiveAudio = true,
                supportsLiveVideo = false,
                supportsAsyncSegments = false,
                supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
            )
        )
        val ringtoneManager = MockCallRingtoneManager(context)

        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(engine),
            scopeOverride = callScope,
            ringtoneManagerOverride = ringtoneManager
        )

        val offerJson = """
            {"callId":"call-rahul-3","mode":"AUDIO","sdp":"v=0..."}
        """.trimIndent()
        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerJson, testPeerKey)
        testScheduler.runCurrent()

        // Decline call
        callManager.rejectIncomingCall()
        testScheduler.runCurrent()

        // Ringtone stopped
        assertTrue("Ringtone must stop on decline", ringtoneManager.stopCalled.get())
        assertFalse(ringtoneManager.isRinging)

        // Call state must be Ended
        val state = callManager.stateStore.state.value
        assertTrue("State should be Ended, was $state", state is CallUiState.Ended)
        assertEquals("Call declined", (state as CallUiState.Ended).reason)
    }

    @Test
    fun testCallerCancelsWhileRinging_stopsRingtoneAndLogsMissedCall() = runTest {
        val engine = ControllableCallEngine(
            CallEngineCapabilities(
                type = CallEngineType.WEBRTC,
                supportsLiveAudio = true,
                supportsLiveVideo = false,
                supportsAsyncSegments = false,
                supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
            )
        )
        val ringtoneManager = MockCallRingtoneManager(context)

        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(engine),
            scopeOverride = callScope,
            ringtoneManagerOverride = ringtoneManager
        )

        val offerJson = """
            {"callId":"call-rahul-4","mode":"AUDIO","sdp":"v=0..."}
        """.trimIndent()
        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerJson, testPeerKey)
        testScheduler.runCurrent()

        // Remote caller sends CALL_END
        val endJson = """
            {"callId":"call-rahul-4","mode":"AUDIO","reason":"Caller cancelled"}
        """.trimIndent()
        callManager.handleSignal(MeshProtocol.TYPE_CALL_END, endJson, testPeerKey)
        testScheduler.runCurrent()

        // Ringtone stopped
        assertTrue("Ringtone must stop when caller cancels", ringtoneManager.stopCalled.get())

        // State Ended with reason "Caller cancelled"
        val state = callManager.stateStore.state.value
        assertTrue("State should be Ended, was $state", state is CallUiState.Ended)
        assertEquals("Caller cancelled", (state as CallUiState.Ended).reason)

        // Missed call logged
        verify(messageRouter).sendMessage(
            eq(testPeerKey),
            eq("📞 Missed call (Caller cancelled)"),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull()
        )
    }

    @Test
    fun testRingTimeout_stopsRingtoneAndLogsMissedCall() = runTest {
        val engine = ControllableCallEngine(
            CallEngineCapabilities(
                type = CallEngineType.WEBRTC,
                supportsLiveAudio = true,
                supportsLiveVideo = false,
                supportsAsyncSegments = false,
                supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
            )
        )
        val ringtoneManager = MockCallRingtoneManager(context)

        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(engine),
            scopeOverride = callScope,
            ringtoneManagerOverride = ringtoneManager
        )

        val offerJson = """
            {"callId":"call-rahul-5","mode":"AUDIO","sdp":"v=0..."}
        """.trimIndent()
        callManager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offerJson, testPeerKey)
        testScheduler.runCurrent()

        assertTrue(ringtoneManager.isRinging)

        // Advance past 45 seconds ring timeout
        testScheduler.advanceTimeBy(46_000L)
        testScheduler.runCurrent()

        // Ringtone stopped
        assertTrue("Ringtone must stop on timeout", ringtoneManager.stopCalled.get())

        // State Ended with Missed call
        val state = callManager.stateStore.state.value
        assertTrue("State should be Ended, was $state", state is CallUiState.Ended)
        assertEquals("Missed call", (state as CallUiState.Ended).reason)

        // Missed call logged
        verify(messageRouter).sendMessage(
            eq(testPeerKey),
            eq("📞 Missed call"),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull()
        )
    }
}
