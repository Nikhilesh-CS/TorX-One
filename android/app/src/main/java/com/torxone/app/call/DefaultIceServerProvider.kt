package com.torxone.app.call

import android.util.Log
import org.webrtc.PeerConnection

/**
 * Provides ICE servers for WebRTC peer connections.
 *
 * Phase 5 Security Change:
 * - Removed all Google STUN servers (stun.l.google.com) which leaked the user's
 *   public IP address, ISP, and call timing to Google on every call attempt.
 * - ICE server list is now controlled by CallPrivacyPolicy:
 *   - NORMAL: TURN relay available for NAT traversal.
 *   - PRIVACY: TURN relay preferred, direct P2P limited.
 *   - STRICT: Relay-only mode, no host/srflx candidates.
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

        // Only add TURN relay servers when relay candidates are permitted
        if (privacyPolicy.allowRelayCandidates) {
            servers.add(
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer()
            )
            servers.add(
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer()
            )
        }

        // No Google STUN servers. No third-party STUN telemetry.
        // srflx candidates (if allowed by policy) are derived from TURN server's
        // allocate response, which also provides a server-reflexive address.

        runCatching {
            Log.d(TAG, "ICE servers configured: ${servers.size} (relay=${privacyPolicy.allowRelayCandidates}, srflx=${privacyPolicy.allowSrflxCandidates}, directP2P=${privacyPolicy.allowDirectP2P})")
        }
        return servers
    }
}
