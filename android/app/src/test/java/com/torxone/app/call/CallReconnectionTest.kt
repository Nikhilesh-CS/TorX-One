package com.torxone.app.call

import android.content.Context
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactDao
import com.torxone.app.data.ContactEntity
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallReconnectionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var callScope: CoroutineScope
    private lateinit var context: Context
    private val db: AppDatabase = mock()
    private val contactDao: ContactDao = mock()
    private val messageRouter: MessageRouter = mock()

    private val testPeerKey = "peer_pubkey_reconnect_test"
    private val testContact = ContactEntity(
        signingPublicKey = testPeerKey,
        encryptionPublicKey = "enc_pubkey_reconnect_test",
        name = "Suresh",
        endpointId = "ep_reconnect",
        onionAddress = "reconnect.onion",
        isConnected = true
    )

    private class ReconnectingEngine(
        var stateStore: CallStateStore? = null,
        var callAudioManager: CallAudioManager? = null
    ) : CallEngine {
        val iceRestartAttempts = AtomicInteger(0)
        val iceRestartInProgress = AtomicBoolean(false)
        val endCalled = AtomicBoolean(false)
        var activeCallId: String? = null

        override val capabilities = CallEngineCapabilities(
            type = CallEngineType.WEBRTC,
            supportsLiveAudio = true,
            supportsLiveVideo = false,
            supportsAsyncSegments = false,
            supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
        )

        override fun isAvailable(context: CallRouteContext): Boolean = true

        override suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult {
            activeCallId = callId
            return CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
        }

        override suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult {
            activeCallId = callId
            return CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
        }

        fun simulateConnected() {
            iceRestartInProgress.set(false)
            val callId = activeCallId ?: return
            stateStore?.update(
                CallUiState.Connected(
                    callId = callId,
                    peerKey = "peer_key",
                    peerName = "Suresh",
                    mode = CallMode.AUDIO,
                    isMuted = callAudioManager?.isMuted ?: false,
                    isSpeaker = callAudioManager?.isSpeaker ?: false
                )
            )
        }

        fun simulateDisconnect() {
            val callId = activeCallId ?: return
            stateStore?.update(
                CallUiState.Reconnecting(
                    callId = callId,
                    peerKey = "peer_key",
                    peerName = "Suresh",
                    mode = CallMode.AUDIO,
                    isMuted = callAudioManager?.isMuted ?: false,
                    isSpeaker = callAudioManager?.isSpeaker ?: false
                )
            )
        }

        fun triggerIceRestart(): Boolean {
            if (iceRestartInProgress.compareAndSet(false, true)) {
                iceRestartAttempts.incrementAndGet()
                return true
            }
            return false
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
    }

    @After
    fun tearDown() {
        callScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun testSingleFlightIceRestart_coalescesConcurrentAttempts() {
        val engine = ReconnectingEngine()

        // First attempt succeeds
        assertTrue(engine.triggerIceRestart())
        assertEquals(1, engine.iceRestartAttempts.get())

        // Simultaneous second and third attempts are coalesced/blocked while in progress
        assertFalse(engine.triggerIceRestart())
        assertFalse(engine.triggerIceRestart())
        assertEquals(1, engine.iceRestartAttempts.get())

        // Once connected again, lock is released
        engine.simulateConnected()
        assertTrue(engine.triggerIceRestart())
        assertEquals(2, engine.iceRestartAttempts.get())
    }

    @Test
    fun testReconnection_preservesMuteAndSpeakerState() = runTest {
        val engine = ReconnectingEngine()
        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(engine),
            scopeOverride = callScope
        )
        engine.stateStore = callManager.stateStore
        engine.callAudioManager = callManager.callAudioManager

        // 1. Start call & reach Connected
        callManager.startAudioCall(testPeerKey)
        testScheduler.runCurrent()
        engine.simulateConnected()
        testScheduler.runCurrent()

        // 2. User mutes and turns on speaker
        callManager.toggleMute()
        callManager.toggleSpeaker()
        testScheduler.runCurrent()

        val connectedState = callManager.stateStore.state.value as CallUiState.Connected
        assertTrue(connectedState.isMuted)
        assertTrue(connectedState.isSpeaker)

        // 3. Network disconnects -> Reconnecting
        engine.simulateDisconnect()
        testScheduler.runCurrent()

        val reconnectingState = callManager.stateStore.state.value as CallUiState.Reconnecting
        assertTrue("Mute must be preserved in Reconnecting state", reconnectingState.isMuted)
        assertTrue("Speaker must be preserved in Reconnecting state", reconnectingState.isSpeaker)

        // 4. Recovery succeeds -> Connected
        engine.simulateConnected()
        testScheduler.runCurrent()

        val recoveredState = callManager.stateStore.state.value as CallUiState.Connected
        assertTrue("Mute must be preserved after recovery", recoveredState.isMuted)
        assertTrue("Speaker must be preserved after recovery", recoveredState.isSpeaker)
    }
}
