package com.torxone.app.media

import com.torxone.app.calls.*
import com.torxone.app.calls.CallStateMachineTest.FakeCallEventListener
import com.torxone.app.calls.CallStateMachineTest.FakeCallSignaling
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileRtcConnectionDeadlineTest {
    @Test fun missingLocalSdpCallbackEndsOptionalLaneWithinDeadline() = runTest {
        val signaling = FakeCallSignaling()
        val manager = CallManager(signaling, scope = backgroundScope)
        val listener = FakeCallEventListener()
        manager.callEventListener = listener
        val deadline = FileRtcConnectionDeadline(manager, backgroundScope, timeoutMs = 100)
        val session = manager.startOutgoingCall("conversation", "relationship", "peer", CallType.DATA)!!
        deadline.start(session.callId)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(CallState.FAILED, manager.activeCall.value!!.state)
        assertTrue(listener.callEndedReceived)
        assertTrue(signaling.persistedHistory)
    }

    @Test fun incomingIceStallAlsoEndsOptionalLaneWithoutWaitingForAnotherSend() = runTest {
        val signaling = FakeCallSignaling()
        val manager = CallManager(signaling, scope = backgroundScope)
        val listener = FakeCallEventListener()
        manager.callEventListener = listener
        manager.onIncomingOffer("call", "conversation", "relationship", "peer", CallType.DATA, "offer")
        manager.onLocalAnswerReady("call", "answer")
        val deadline = FileRtcConnectionDeadline(manager, backgroundScope, timeoutMs = 100)
        deadline.start("call")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(CallState.FAILED, manager.activeCall.value!!.state)
        assertTrue(listener.callEndedReceived)
    }

    @Test fun successfulConnectionIsNotTornDownByOldSetupDeadline() = runTest {
        val signaling = FakeCallSignaling()
        val manager = CallManager(signaling, scope = backgroundScope)
        val session = manager.startOutgoingCall("conversation", "relationship", "peer", CallType.DATA)!!
        val deadline = FileRtcConnectionDeadline(manager, backgroundScope, timeoutMs = 100)
        deadline.start(session.callId)
        manager.onLocalOfferReady(session.callId, "offer")
        manager.onRemoteAnswer(session.callId, "answer")
        manager.onIceConnected(session.callId)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(CallState.CONNECTED, manager.activeCall.value!!.state)
        assertFalse(signaling.persistedHistory)
        deadline.cancel()
    }
}
