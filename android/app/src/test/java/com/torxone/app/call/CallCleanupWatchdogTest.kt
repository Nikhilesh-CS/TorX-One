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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallCleanupWatchdogTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var callScope: CoroutineScope
    private lateinit var context: Context
    private val db: AppDatabase = mock()
    private val contactDao: ContactDao = mock()
    private val messageRouter: MessageRouter = mock()

    private val testPeerKey = "peer_pubkey_cleanup_test"
    private val testContact = ContactEntity(
        signingPublicKey = testPeerKey,
        encryptionPublicKey = "enc_pubkey_cleanup_test",
        name = "Cleanup Test User",
        endpointId = "ep_cleanup",
        onionAddress = "cleanup.onion",
        isConnected = true
    )

    private class StubEngine : CallEngine {
        val endCalled = AtomicBoolean(false)
        override val capabilities = CallEngineCapabilities(
            type = CallEngineType.WEBRTC,
            supportsLiveAudio = true,
            supportsLiveVideo = false,
            supportsAsyncSegments = false,
            supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
        )
        override fun isAvailable(context: CallRouteContext): Boolean = true
        override suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult {
            return CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
        }
        override suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult {
            return CallStartResult.Started(callId, CallMode.AUDIO, CallEngineType.WEBRTC)
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
    fun testInitialState_cleanupWatchdogReportsClean() {
        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            scopeOverride = callScope
        )

        // When Idle, verifyCleanup should return true
        assertTrue(callManager.verifyCleanup())
    }

    @Test
    fun testCallEnd_releasesAllResourcesAndWatchdogReportsClean() = runTest {
        val engine = StubEngine()
        val callManager = CallManager(
            context = context,
            db = db,
            messageRouter = messageRouter,
            enginesOverride = listOf(engine),
            scopeOverride = callScope
        )

        // Start call
        callManager.startAudioCall(testPeerKey)
        testScheduler.runCurrent()

        // During active call, cleanup verification should report false (resources in use)
        assertFalse(callManager.verifyCleanup())

        // End call
        callManager.endCall("User ended")
        testScheduler.runCurrent()

        // After end, all resources released
        assertTrue(engine.endCalled.get())
        assertTrue(callManager.verifyCleanup())
    }
}
