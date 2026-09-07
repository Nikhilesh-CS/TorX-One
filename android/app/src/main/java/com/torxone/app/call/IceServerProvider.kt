package com.torxone.app.call

import org.webrtc.PeerConnection

interface IceServerProvider {
    /**
     * Fetches a list of ICE servers (STUN/TURN).
     * This function is suspended to allow for authenticated network requests
     * (e.g., fetching short-lived TURN credentials from the TorX backend).
     */
    suspend fun getIceServers(): List<PeerConnection.IceServer>
}
