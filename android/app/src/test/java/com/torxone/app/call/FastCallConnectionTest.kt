package com.torxone.app.call

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
import org.junit.Assert.assertNotNull
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
class FastCallConnectionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var callScope: CoroutineScope
    private lateinit var context: Context
    private val db: AppDatabase = mock()
    private val contactDao: ContactDao = mock()
    private val messageRouter: MessageRouter = mock()

    private val testPeerKey = "peer_pubkey_rahul_fast"
    private val testContact = ContactEntity(
        signingPublicKey = testPeerKey,
        encryptionPublicKey = "enc_pubkey_rahul_fast",
        name = "Rahul Sharma",
        endpointId = "ep_fast",
        onionAddress = "rahul_fast.onion",
        isConnected = true
    )

    private class MockEngine(
        var stateStore: CallStateStore? = null,
        override val capabilities: CallEngineCapabilities = CallEngineCapabilities(
            type = CallEngineType.WEBRTC,
            supportsLiveAudio = true,
            supportsLiveVideo = false,
            supportsAsyncSegments = false,
            supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
        )
    ) : CallEngine {
        var diagnostics: CallConnectionDiagnostics? = null
        var activeCallId: String? = null
        var activePeerKey: String = ""
        var activePeerName: String = ""
        val endCalled = AtomicBoolean(false)

        override fun isAvailable(context: CallRouteContext): Boolean = true

        override suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult {
            activeCallId = callId
            activePeerKey = contact.signingPublicKey
            activePeerName = contact.name
            diagnostics?.markOfferSent()
            return CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
        }

        override suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult {
            activeCallId = callId
            activePeerKey = contact.signingPublicKey
            activePeerName = contact.name
            diagnostics?.markAnswerSent()
            return CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
        }

        fun simulateIceChecking() {
            val callId = activeCallId ?: return
            diagnostics?.markIceChecking()
            stateStore?.update(CallUiState.IceConnecting(callId, activePeerKey, activePeerName, CallMode.AUDIO))
        }

        fun simulateIceConnected() {
            val callId = activeCallId ?: return
            diagnostics?.markIceConnected()
            stateStore?.update(CallUiState.MediaConnecting(callId, activePeerKey, activePeerName, CallMode.AUDIO))
        }

        fun simulateMediaReceived() {
            val callId = activeCallId ?: return
            diagnostics?.markRemoteTrackReceived()
            diagnostics?.markFullyConnected()
            stateStore?.update(CallUiState.Connected(callId, activePeerKey, activePeerName, CallMode.AUDIO))
        }

        fun simulateNetworkHandover() {
            val callId = activeCallId ?: return
            diagnostics?.record("NETWORK_HANDOVER")
            stateStore?.update(CallUiState.Reconnecting(callId, activePeerKey, activePeerName, CallMode.AUDIO))
        }

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
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        whenever(messageRouter.getBestTransport(any())).thenReturn(Transport.NEARBY_DIRECT)
        whenever(db.contactDao()).thenReturn(contactDao)
        whenever(contactDao.getContact(testPeerKey)).thenReturn(testContact)

        // Mock openCallTransportSession
        whenever(messageRouter.openCallTransportSession(any(), any(), any())).thenAnswer { invocation ->
            val callId = invocation.getArgument<String>(0)
            val contact = invocation.getArgument<ContactEntity>(1)
            val transport = invocation.getArgument<Transport>(2)
            CallTransportSession(callId, contact.signingPublicKey, transport)
        }
    }

    @After
    fun tearDown() {
        callScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun testOutgoingCallProgression_peerNamePreservedAcrossAllStates() = runTest {
        val mockEngine = MockEngine()

        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(mockEngine),
            scopeOverride = callScope
        )
        mockEngine.stateStore = callManager.stateStore

        // 1. Start outgoing audio call
        callManager.startAudioCall(testPeerKey)
        testScheduler.runCurrent()

        // Verify initial state is Negotiating (or Ringing -> Negotiating)
        val negState = callManager.stateStore.state.value
        assertTrue("Expected Negotiating, was $negState", negState is CallUiState.Negotiating)
        assertEquals("Rahul Sharma", (negState as CallUiState.Negotiating).peerName)

        // 2. Simulate ICE Checking -> IceConnecting
        mockEngine.simulateIceChecking()
        testScheduler.runCurrent()

        val iceState = callManager.stateStore.state.value
        assertTrue("Expected IceConnecting, was $iceState", iceState is CallUiState.IceConnecting)
        assertEquals("Rahul Sharma", (iceState as CallUiState.IceConnecting).peerName)
        assertEquals("Connecting to peer…", iceState.bannerStatusText())

        // 3. Simulate ICE Connected -> MediaConnecting
        mockEngine.simulateIceConnected()
        testScheduler.runCurrent()

        val mediaState = callManager.stateStore.state.value
        assertTrue("Expected MediaConnecting, was $mediaState", mediaState is CallUiState.MediaConnecting)
        assertEquals("Rahul Sharma", (mediaState as CallUiState.MediaConnecting).peerName)
        assertEquals("Starting audio…", mediaState.bannerStatusText())

        // 4. Simulate Media Received -> Connected
        mockEngine.simulateMediaReceived()
        testScheduler.runCurrent()

        val connectedState = callManager.stateStore.state.value
        assertTrue("Expected Connected, was $connectedState", connectedState is CallUiState.Connected)
        assertEquals("Rahul Sharma", (connectedState as CallUiState.Connected).peerName)
        assertTrue(connectedState.bannerStatusText().contains("Connected"))

        // 5. Simulate Network Handover -> Reconnecting -> Connected
        mockEngine.simulateNetworkHandover()
        testScheduler.runCurrent()

        val reconnectingState = callManager.stateStore.state.value
        assertTrue("Expected Reconnecting, was $reconnectingState", reconnectingState is CallUiState.Reconnecting)
        assertEquals("Rahul Sharma", (reconnectingState as CallUiState.Reconnecting).peerName)
        assertEquals("Reconnecting…", reconnectingState.bannerStatusText())

        mockEngine.simulateMediaReceived()
        testScheduler.runCurrent()
        assertTrue(callManager.stateStore.state.value is CallUiState.Connected)
    }

    @Test
    fun testConnectionEstablishmentTimeout_endsStuckCall() = runTest {
        val mockEngine = MockEngine()

        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(mockEngine),
            scopeOverride = callScope
        )
        mockEngine.stateStore = callManager.stateStore

        // Start outgoing call
        callManager.startAudioCall(testPeerKey)
        testScheduler.runCurrent()

        assertTrue(callManager.stateStore.state.value is CallUiState.Negotiating)

        // Advance time past 25s connection establishment timeout
        testScheduler.advanceTimeBy(26_000L)
        testScheduler.runCurrent()

        // Call must transition to Ended with "Connection timed out"
        val state = callManager.stateStore.state.value
        assertTrue("Expected Ended, was $state", state is CallUiState.Ended)
        assertEquals("Connection timed out", (state as CallUiState.Ended).reason)
    }
}
