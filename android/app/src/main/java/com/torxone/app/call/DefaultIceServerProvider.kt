package com.torxone.app.call

import android.util.Log
import org.webrtc.PeerConnection

/**
 * Provides ICE servers for WebRTC peer connections.
 *
 * Phase 5 Security Change:
 * Public STUN is used only when the selected privacy policy permits srflx
 * candidates. No shared public TURN credential is shipped in production code.
 * - ICE server list is now controlled by CallPrivacyPolicy:
 *   - NORMAL/PRIVACY: policy-controlled host/srflx candidates.
 *   - STRICT: requires a separately provisioned authenticated TURN service.
 *
 * TODO (Phase 6): Fetch temporary TURN credentials from an authenticated TorX backend.
 */
class DefaultIceServerProvider(
    private val privacyPolicy: CallPrivacyPolicy = CallPrivacyPolicy.DEFAULT
) : IceServerProvider {

    companion object {
        private const val TAG = "IceServerProvider"
    }

    override suspend fun getIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()

        // TURN must use short-lived credentials provisioned by a trusted TorX
        // backend. Shipping public demo credentials silently relays call metadata
        // through an uncontrolled third party and is intentionally forbidden.

        // STUN configuration based on privacy policy:
        // Documented trade-off: in NORMAL & PRIVACY modes, public STUN servers provide
        // reliable NAT mapping (srflx candidates) for direct cross-network P2P connections.
        if (privacyPolicy.allowSrflxCandidates) {
            servers.add(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )
            servers.add(
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302")
                    .createIceServer()
            )
        }

        runCatching {
            Log.d(TAG, "ICE servers configured: ${servers.size} (relay=${privacyPolicy.allowRelayCandidates}, srflx=${privacyPolicy.allowSrflxCandidates}, directP2P=${privacyPolicy.allowDirectP2P})")
        }
        return servers
    }
}
