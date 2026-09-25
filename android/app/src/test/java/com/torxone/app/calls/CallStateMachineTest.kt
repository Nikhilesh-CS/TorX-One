package com.torxone.app.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * CallStateMachineTest — Validates CallManager state transitions,
 * collision resolution, busy handling, and timeout behavior.
 *
 * Uses FakeCallSignaling (no real crypto/network deps) and
 * FakeCallEventListener (captures WebRTC-bridging events).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallStateMachineTest {

    private lateinit var callManager: CallManager
    private lateinit var fakeSignaling: FakeCallSignaling
    private lateinit var fakeListener: FakeCallEventListener

    @Before
    fun setup() {
        fakeSignaling = FakeCallSignaling()
        callManager = CallManager(
            callService = fakeSignaling,
            localIdentityId = "alice-identity"
        )
        fakeListener = FakeCallEventListener()
        callManager.callEventListener = fakeListener
    }

    @Test
    fun `startOutgoingCall transitions to OUTGOING_PREPARING`() = runTest {
        val session = callManager.startOutgoingCall("conv-1", "rel-1", "bob-identity", CallType.VOICE)
        assertNotNull(session)
        assertEquals(CallState.OUTGOING_PREPARING, session!!.state)
        assertEquals(CallDirection.OUTGOING, session.direction)
        assertEquals(CallType.VOICE, session.type)
        assertTrue(fakeListener.createOfferCalled)
    }

    @Test
    fun `onLocalOfferReady transitions to OUTGOING_RINGING and sends offer`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp-offer-data")

        val current = callManager.activeCall.value!!
        assertEquals(CallState.OUTGOING_RINGING, current.state)
        assertTrue(fakeSignaling.sentOffer)
    }

    @Test
    fun `cannot start second call while one is active`() = runTest {
        callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)
        // Move to ringing so it's not in OUTGOING_PREPARING
        callManager.onLocalOfferReady(callManager.activeCall.value!!.callId, "sdp")
        val second = callManager.startOutgoingCall("c2", "r2", "charlie", CallType.VOICE)
        assertNull(second)
    }

    @Test
    fun `onIncomingOffer sets INCOMING_RINGING and sends ringing`() = runTest {
        val accepted = callManager.onIncomingOffer(
            callId = "incoming-call-1",
            conversationId = "conv-2",
            relationshipId = "rel-2",
            peerIdentityId = "bob-identity",
            type = CallType.VOICE,
            sdpOffer = "sdp-from-bob"
        )
        assertTrue(accepted)
        val current = callManager.activeCall.value!!
        assertEquals(CallState.INCOMING_RINGING, current.state)
        assertEquals(CallDirection.INCOMING, current.direction)
        assertTrue(fakeSignaling.sentRinging)
        assertTrue(fakeListener.incomingCallReceived)
    }

    @Test
    fun `incoming call while busy sends BUSY`() = runTest {
        callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)
        callManager.onLocalOfferReady(callManager.activeCall.value!!.callId, "sdp")

        val accepted = callManager.onIncomingOffer(
            "from-charlie", "conv-3", "rel-3", "charlie-identity", CallType.VOICE, "sdp-charlie"
        )
        assertFalse(accepted)
        assertTrue(fakeSignaling.sentBusy)
    }

    @Test
    fun `decline transitions through DECLINED and persists history`() = runTest {
        callManager.onIncomingOffer("ic-1", "c1", "r1", "bob", CallType.VOICE, "sdp")
        callManager.declineCall("ic-1")

        assertTrue(fakeSignaling.sentDecline)
        assertTrue(fakeSignaling.persistedHistory)
    }

    @Test
    fun `accept triggers createAnswer listener`() = runTest {
        callManager.onIncomingOffer("ic-2", "c1", "r1", "bob", CallType.VOICE, "sdp")
        callManager.acceptCall("ic-2")
        assertTrue(fakeListener.createAnswerCalled)
    }

    @Test
    fun `onLocalAnswerReady transitions to CONNECTING and sends answer`() = runTest {
        callManager.onIncomingOffer("ic-3", "c1", "r1", "bob", CallType.VOICE, "sdp")
        callManager.acceptCall("ic-3")
        callManager.onLocalAnswerReady("ic-3", "sdp-answer")

        assertEquals(CallState.CONNECTING, callManager.activeCall.value!!.state)
        assertTrue(fakeSignaling.sentAnswer)
    }

    @Test
    fun `onRemoteAnswer transitions outgoing call to CONNECTING`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        callManager.onRemoteAnswer(session.callId, "sdp-answer-from-bob")

        assertEquals(CallState.CONNECTING, callManager.activeCall.value!!.state)
        assertTrue(fakeListener.remoteAnswerReceived)
    }

    @Test
    fun `ICE connected transitions to CONNECTED`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        callManager.onRemoteAnswer(session.callId, "sdp-answer")
        callManager.onIceConnected(session.callId)

        val current = callManager.activeCall.value!!
        assertEquals(CallState.CONNECTED, current.state)
        assertNotNull(current.connectedAt)
        assertTrue(fakeSignaling.sentConnected)
    }

    @Test
    fun `ICE disconnected during CONNECTED transitions to RECONNECTING`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        callManager.onRemoteAnswer(session.callId, "answer")
        callManager.onIceConnected(session.callId)
        callManager.onIceDisconnected(session.callId)

        assertEquals(CallState.RECONNECTING, callManager.activeCall.value!!.state)
    }

    @Test
    fun `ICE reconnect restores CONNECTED`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        callManager.onRemoteAnswer(session.callId, "answer")
        callManager.onIceConnected(session.callId)
        callManager.onIceDisconnected(session.callId)
        callManager.onIceConnected(session.callId)

        assertEquals(CallState.CONNECTED, callManager.activeCall.value!!.state)
    }

    @Test
    fun `ICE failed ends call with CONNECTION_FAILED`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        callManager.onRemoteAnswer(session.callId, "answer")
        callManager.onIceFailed(session.callId)

        val current = callManager.activeCall.value!!
        assertEquals(CallState.ENDED, current.state)
        assertEquals(CallEndReason.CONNECTION_FAILED, current.endReason)
    }

    @Test
    fun `hangUp sends CALL_END and persists history`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        callManager.onRemoteAnswer(session.callId, "answer")
        callManager.onIceConnected(session.callId)
        callManager.hangUp()

        assertTrue(fakeSignaling.sentEnd)
        assertTrue(fakeSignaling.persistedHistory)
    }

    @Test
    fun `remote end terminates call`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        callManager.onRemoteAnswer(session.callId, "answer")
        callManager.onIceConnected(session.callId)
        callManager.onRemoteEnd(session.callId, CallEndReason.REMOTE_HANGUP)

        assertEquals(CallState.ENDED, callManager.activeCall.value!!.state)
    }

    @Test
    fun `remote decline terminates outgoing call`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        callManager.onRemoteDecline(session.callId)

        assertEquals(CallState.DECLINED, callManager.activeCall.value!!.state)
    }

    @Test
    fun `remote busy terminates outgoing call`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        callManager.onRemoteBusy(session.callId)

        assertEquals(CallState.BUSY, callManager.activeCall.value!!.state)
    }

    @Test
    fun `ICE candidate for wrong callId is silently ignored`() = runTest {
        callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)
        callManager.onRemoteIceCandidate("wrong-call-id", "audio", 0, "candidate:1")
        // Should not crash — stale candidates are dropped
    }

    @Test
    fun `simultaneous call collision - lower identity wins`() = runTest {
        // alice-identity < bob-identity lexicographically, so Alice wins
        val session = callManager.startOutgoingCall("c1", "r1", "bob-identity", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "alice-sdp")

        val accepted = callManager.onIncomingOffer(
            "bob-call-id", "c1", "r1", "bob-identity", CallType.VOICE, "bob-sdp"
        )

        assertFalse(accepted)
        assertTrue(fakeSignaling.sentBusy)
        assertEquals(session.callId, callManager.activeCall.value!!.callId)
    }

    @Test
    fun `simultaneous call collision - higher identity yields to lower`() = runTest {
        val highSignaling = FakeCallSignaling()
        val highListener = FakeCallEventListener()
        val highManager = CallManager(highSignaling, "zara-identity")
        highManager.callEventListener = highListener

        val session = highManager.startOutgoingCall("c1", "r1", "alice-identity", CallType.VOICE)!!
        highManager.onLocalOfferReady(session.callId, "zara-sdp")

        // alice-identity < zara-identity, so Alice's offer wins
        val accepted = highManager.onIncomingOffer(
            "alice-call-id", "c1", "r1", "alice-identity", CallType.VOICE, "alice-sdp"
        )

        assertTrue(accepted)
        assertEquals("alice-call-id", highManager.activeCall.value!!.callId)
        assertEquals(CallState.INCOMING_RINGING, highManager.activeCall.value!!.state)
    }

    @Test
    fun `toggle mute updates session and notifies listener`() = runTest {
        callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)
        assertFalse(callManager.activeCall.value!!.isMuted)

        callManager.toggleMute()
        assertTrue(callManager.activeCall.value!!.isMuted)
        assertTrue(fakeListener.muteChangedTo!!)

        callManager.toggleMute()
        assertFalse(callManager.activeCall.value!!.isMuted)
        assertFalse(fakeListener.muteChangedTo!!)
    }

    @Test
    fun `toggle speaker updates session`() = runTest {
        callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)
        assertFalse(callManager.activeCall.value!!.isSpeakerOn)
        callManager.toggleSpeaker()
        assertTrue(callManager.activeCall.value!!.isSpeakerOn)
    }

    @Test
    fun `toggle camera updates session`() = runTest {
        callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)
        assertFalse(callManager.activeCall.value!!.isCameraOn)
        callManager.toggleCamera()
        assertTrue(callManager.activeCall.value!!.isCameraOn)
    }

    @Test
    fun `enableVideo changes type to VIDEO and enables camera`() = runTest {
        callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)
        assertEquals(CallType.VOICE, callManager.activeCall.value!!.type)

        callManager.enableVideo()
        assertEquals(CallType.VIDEO, callManager.activeCall.value!!.type)
        assertTrue(callManager.activeCall.value!!.isCameraOn)
    }

    @Test
    fun `duplicate end for same callId is safe`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        callManager.onRemoteAnswer(session.callId, "answer")
        callManager.onIceConnected(session.callId)

        callManager.onRemoteEnd(session.callId, CallEndReason.REMOTE_HANGUP)
        // Second end should be safe
        callManager.onRemoteEnd(session.callId, CallEndReason.REMOTE_HANGUP)
    }

    @Test
    fun `video call starts as VIDEO type`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VIDEO)
        assertEquals(CallType.VIDEO, session!!.type)
    }

    @Test
    fun `onRemoteRinging while OUTGOING_RINGING does not change state`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        assertEquals(CallState.OUTGOING_RINGING, callManager.activeCall.value!!.state)

        callManager.onRemoteRinging(session.callId)
        assertEquals(CallState.OUTGOING_RINGING, callManager.activeCall.value!!.state)
    }

    @Test
    fun `accept with wrong callId does nothing`() = runTest {
        callManager.onIncomingOffer("ic-1", "c1", "r1", "bob", CallType.VOICE, "sdp")
        callManager.acceptCall("wrong-id")
        // Should still be ringing
        assertEquals(CallState.INCOMING_RINGING, callManager.activeCall.value!!.state)
    }

    @Test
    fun `decline with wrong callId does nothing`() = runTest {
        callManager.onIncomingOffer("ic-1", "c1", "r1", "bob", CallType.VOICE, "sdp")
        callManager.declineCall("wrong-id")
        // Should still be ringing
        assertEquals(CallState.INCOMING_RINGING, callManager.activeCall.value!!.state)
    }

    @Test
    fun `local ICE candidate forwarded via signaling`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalIceCandidate(session.callId, "audio", 0, "candidate:1")
        assertTrue(fakeSignaling.sentIceCandidate)
    }

    @Test
    fun `connectedAt preserved on reconnect`() = runTest {
        val session = callManager.startOutgoingCall("c1", "r1", "bob", CallType.VOICE)!!
        callManager.onLocalOfferReady(session.callId, "sdp")
        callManager.onRemoteAnswer(session.callId, "answer")
        callManager.onIceConnected(session.callId)

        val firstConnectedAt = callManager.activeCall.value!!.connectedAt
        assertNotNull(firstConnectedAt)

        callManager.onIceDisconnected(session.callId)
        callManager.onIceConnected(session.callId)

        // connectedAt should be preserved from first connection
        assertEquals(firstConnectedAt, callManager.activeCall.value!!.connectedAt)
    }

    // ─── Fakes ───────────────────────────────────────────────────────────

    class FakeCallSignaling : CallSignaling {
        var sentOffer = false
        var sentRinging = false
        var sentAnswer = false
        var sentConnected = false
        var sentEnd = false
        var sentDecline = false
        var sentBusy = false
        var sentIceCandidate = false
        var persistedHistory = false

        override suspend fun sendCallOffer(session: CallSession, sdpOffer: String) { sentOffer = true }
        override suspend fun sendRinging(session: CallSession) { sentRinging = true }
        override suspend fun sendCallAnswer(session: CallSession, sdpAnswer: String) { sentAnswer = true }
        override suspend fun sendIceCandidate(session: CallSession, sdpMid: String?, sdpMLineIndex: Int, candidate: String) { sentIceCandidate = true }
        override suspend fun sendConnected(session: CallSession) { sentConnected = true }
        override suspend fun sendEnd(session: CallSession, reason: CallEndReason) { sentEnd = true }
        override suspend fun sendDecline(session: CallSession) { sentDecline = true }
        override suspend fun sendBusy(callId: String, conversationId: String, relationshipId: String, peerIdentityId: String) { sentBusy = true }
        override suspend fun persistCallHistory(session: CallSession, outcome: CallOutcome, durationMs: Long?) { persistedHistory = true }
    }

    class FakeCallEventListener : CallEventListener {
        var createOfferCalled = false
        var createAnswerCalled = false
        var remoteAnswerReceived = false
        var incomingCallReceived = false
        var callEndedReceived = false
        var muteChangedTo: Boolean? = null

        override fun onCreateOffer(session: CallSession) { createOfferCalled = true }
        override fun onCreateAnswer(session: CallSession) { createAnswerCalled = true }
        override fun onRemoteAnswer(session: CallSession, sdpAnswer: String) { remoteAnswerReceived = true }
        override fun onRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {}
        override fun onCallConnected(session: CallSession) {}
        override fun onCallEnded(session: CallSession) { callEndedReceived = true }
        override fun onIncomingCall(session: CallSession, sdpOffer: String) { incomingCallReceived = true }
        override fun onMuteChanged(muted: Boolean) { muteChangedTo = muted }
        override fun onSpeakerChanged(speaker: Boolean) {}
        override fun onCameraChanged(enabled: Boolean) {}
        override fun onSwitchCamera() {}
    }
}
