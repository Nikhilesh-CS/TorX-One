package com.torxone.app.call

import org.webrtc.PeerConnection

class DefaultIceServerProvider : IceServerProvider {
    override suspend fun getIceServers(): List<PeerConnection.IceServer> {
        // TODO (Phase 6): Make an authenticated backend request here to fetch temporary TURN credentials.
        // For now, using a free public TURN server (openrelay.metered.ca) for testing internet calling.
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
    }
}
