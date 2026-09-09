package com.torxone.app.call

import android.Manifest
import android.content.Context
import com.torxone.app.data.AppDatabase
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
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallManagerDuplicateOfferTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var context: Context
    private val db: AppDatabase = mock()
    private val contactDao: com.torxone.app.data.ContactDao = mock()
    private val messageRouter: MessageRouter = mock()
    private val peerKey = "peer_pubkey_duplicate"
    private val contact = ContactEntity(
        signingPublicKey = peerKey,
        encryptionPublicKey = "enc",
        name = "Peer",
        endpointId = "ep1",
        onionAddress = "peer.onion",
        isConnected = true
    )

    private class CountingRingtoneManager(context: Context) : CallRingtoneManager(context) {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)

        override fun start() {
            starts.incrementAndGet()
            isRinging = true
        }

        override fun stop() {
            stops.incrementAndGet()
            isRinging = false
        }
    }

    private class FakeCallEngine : CallEngine {
        override val capabilities = CallEngineCapabilities(
            type = CallEngineType.WEBRTC,
            supportsLiveAudio = true,
            supportsLiveVideo = false,
            supportsAsyncSegments = false,
            supportedTransports = setOf(Transport.NEARBY_DIRECT, Transport.TOR)
        )

        override fun isAvailable(context: CallRouteContext): Boolean = true
        override suspend fun startOutgoing(callId: String, contact: ContactEntity, context: CallRouteContext): CallStartResult =
            CallStartResult.Started(CallMode.AUDIO)
        override suspend fun acceptIncoming(callId: String, contact: ContactEntity, offer: AstraSessionDescription): CallStartResult =
            CallStartResult.Started(CallMode.AUDIO)
        override fun handleRemoteDescription(description: AstraSessionDescription) = Unit
        override suspend fun handleRenegotiationOffer(offer: AstraSessionDescription, peerKey: String, callId: String) = Unit
        override fun handleIceCandidate(candidate: AstraIceCandidate) = Unit
        override fun end() = Unit
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        whenever(db.contactDao()).thenReturn(contactDao)
        whenever(contactDao.getContact(peerKey)).thenReturn(contact)
        whenever(messageRouter.getBestTransport(any())).thenReturn(Transport.NEARBY_DIRECT)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun manager(ringtone: CountingRingtoneManager): CallManager = CallManager(
        context = context,
        db = db,
        messageRouter = messageRouter,
        enginesOverride = listOf(FakeCallEngine()),
        scopeOverride = scope,
        ringtoneManagerOverride = ringtone
    )

    private fun offer(callId: String, sdp: String = "v=0...initial") = """
        {"callId":"$callId","mode":"AUDIO","sdp":"$sdp"}
    """.trimIndent()

    @Test
    fun testDuplicateOffer_whileRinging_ignoredAndRingtoneNotRestarted() = runTest {
        val ringtone = CountingRingtoneManager(context)
        val manager = manager(ringtone)

        manager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offer("dup-ring"), peerKey)
        testScheduler.runCurrent()
        manager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offer("dup-ring"), peerKey)
        testScheduler.runCurrent()

        assertEquals(1, ringtone.starts.get())
        assertTrue(manager.stateStore.state.value is CallUiState.Ringing)
        assertFalse(ringtone.stops.get() > 0)
    }

    @Test
    fun testDuplicateOffer_duringAcceptanceRace_ignoredAndStateRemainsAccepted() = runTest {
        val ringtone = CountingRingtoneManager(context)
        val manager = manager(ringtone)

        manager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offer("dup-accept"), peerKey)
        testScheduler.runCurrent()
        manager.acceptIncomingCall()
        testScheduler.runCurrent()
        assertTrue(manager.stateStore.state.value is CallUiState.Accepted || manager.stateStore.state.value is CallUiState.Negotiating)

        manager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offer("dup-accept"), peerKey)
        testScheduler.runCurrent()

        assertEquals(1, ringtone.starts.get())
        assertEquals(1, ringtone.stops.get())
        assertFalse(manager.stateStore.state.value is CallUiState.Ringing)
    }

    @Test
    fun testDuplicateOffer_whenConnected_ignoredAndRingtoneDoesNotStart() = runTest {
        val ringtone = CountingRingtoneManager(context)
        val manager = manager(ringtone)

        manager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offer("dup-connected", "initial"), peerKey)
        testScheduler.runCurrent()
        manager.stateStore.update(
            CallUiState.Connected(
                callId = "dup-connected",
                peerKey = peerKey,
                peerName = "Peer",
                mode = CallMode.AUDIO
            )
        )

        manager.handleSignal(MeshProtocol.TYPE_CALL_OFFER, offer("dup-connected", "initial"), peerKey)
        testScheduler.runCurrent()

        assertEquals(1, ringtone.starts.get())
        assertTrue(manager.stateStore.state.value is CallUiState.Connected)
    }
}
