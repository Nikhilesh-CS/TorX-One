package com.torxone.app.calls

/**
 * Interface for call signaling operations.
 * Extracted for testability — FakeCallSignaling can be used in tests
 * without needing real SessionCrypto, ConnectionManager, etc.
 */
interface CallSignaling {
    suspend fun sendCallOffer(session: CallSession, sdpOffer: String)
    suspend fun sendRinging(session: CallSession)
    suspend fun sendCallAnswer(session: CallSession, sdpAnswer: String)
    suspend fun sendIceCandidate(session: CallSession, sdpMid: String?, sdpMLineIndex: Int, candidate: String)
    suspend fun sendConnected(session: CallSession)
    suspend fun sendEnd(session: CallSession, reason: CallEndReason)
    suspend fun sendDecline(session: CallSession)
    suspend fun sendBusy(callId: String, conversationId: String, relationshipId: String, peerIdentityId: String)
    suspend fun persistCallHistory(session: CallSession, outcome: CallOutcome, durationMs: Long?)
}
