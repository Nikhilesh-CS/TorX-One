package com.torxone.app.call

import java.util.regex.Pattern

/**
 * Defines privacy policy for call signaling, media establishment, and UI notifications.
 *
 * Three built-in presets:
 *
 * | Mode     | Host candidates | STUN            | Direct P2P | TURN         |
 * |----------|-----------------|-----------------|------------|--------------|
 * | NORMAL   | ✅               | Configured only | ✅          | Optional     |
 * | PRIVACY  | ❌ local/private | Privacy-only    | Limited    | Preferred    |
 * | STRICT   | ❌               | ❌               | ❌          | Relay only   |
 */
data class CallPrivacyPolicy(
    /**
     * When true, strips private RFC1918 / RFC4193 / link-local IP addresses
     * (e.g. 192.168.x.x, 10.x.x.x, 172.16-31.x.x, fe80::) from signaled ICE host candidates.
     * Prevents exposing the caller's local LAN topology to the remote peer.
     */
    val stripLocalIps: Boolean = true,

    /**
     * Controls whether direct P2P host candidates are permitted.
     * Host candidates advertise the device's actual network interface addresses.
     */
    val allowDirectP2P: Boolean = true,

    /**
     * Controls whether server-reflexive (srflx) candidates are permitted.
     * Srflx candidates are the public mapped address obtained from a STUN server.
     * When false, no STUN binding requests are made and srflx candidates are filtered out.
     */
    val allowSrflxCandidates: Boolean = true,

    /**
     * Controls whether relay (TURN) candidates are permitted.
     * Relay candidates route media through a TURN server so that peers never see each other's IP.
     * Should always be true when allowDirectP2P and allowSrflxCandidates are both false.
     */
    val allowRelayCandidates: Boolean = true,

    /**
     * When true, notifications do not reveal caller/contact names on the lockscreen or system status bar.
     * Displays generic "Incoming TorX call" / "In call".
     */
    val maskNotificationIdentities: Boolean = false
) {
    companion object {
        /** Normal: Host + srflx + relay all allowed. Local LAN host candidates allowed for same-network P2P. */
        val NORMAL = CallPrivacyPolicy(
            stripLocalIps = false,
            allowDirectP2P = true,
            allowSrflxCandidates = true,
            allowRelayCandidates = true,
            maskNotificationIdentities = false
        )

        /** Privacy: No private host candidates, srflx limited, relay preferred. */
        val PRIVACY = CallPrivacyPolicy(
            stripLocalIps = true,
            allowDirectP2P = false,
            allowSrflxCandidates = true,
            allowRelayCandidates = true,
            maskNotificationIdentities = true
        )

        /** Strict: Relay only. No host, no srflx, no direct P2P. Maximum anonymity. */
        val STRICT = CallPrivacyPolicy(
            stripLocalIps = true,
            allowDirectP2P = false,
            allowSrflxCandidates = false,
            allowRelayCandidates = true,
            maskNotificationIdentities = true
        )

        val DEFAULT = NORMAL

        // Matches private IPv4: 10.x.x.x, 192.168.x.x, 172.16-31.x.x, 127.x.x.x, 169.254.x.x
        private val PRIVATE_IPV4_REGEX = Pattern.compile(
            """(^|\s)(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2[0-9]|3[0-1])\.\d{1,3}\.\d{1,3}|127\.\d{1,3}\.\d{1,3}\.\d{1,3}|169\.254\.\d{1,3}\.\d{1,3})($|\s)"""
        )

        // Matches private/link-local IPv6: fe80::, fc00::, fd00::, ::1
        private val PRIVATE_IPV6_REGEX = Pattern.compile(
            """(^|\s)(fe80:[0-9a-fA-F:]*|fc[0-9a-fA-F]{2}:[0-9a-fA-F:]*|fd[0-9a-fA-F]{2}:[0-9a-fA-F:]*|::1)($|\s)"""
        )

        /**
         * Checks if an ICE candidate SDP contains a private/local network address.
         */
        fun isPrivateNetworkCandidate(candidateSdp: String): Boolean {
            if (candidateSdp.contains("typ host", ignoreCase = true)) {
                if (PRIVATE_IPV4_REGEX.matcher(candidateSdp).find()) return true
                if (PRIVATE_IPV6_REGEX.matcher(candidateSdp).find()) return true
            }
            return false
        }

        /**
         * Returns the ICE candidate type from the SDP candidate string.
         * Possible values: "host", "srflx", "prflx", "relay", or null if unknown.
         */
        fun getCandidateType(candidateSdp: String): String? {
            val match = Regex("""typ\s+(host|srflx|prflx|relay)""").find(candidateSdp)
            return match?.groupValues?.getOrNull(1)
        }
    }

    /**
     * Checks whether a given ICE candidate should be signaled to the remote peer
     * based on the current privacy policy.
     */
    fun shouldSignalCandidate(candidateSdp: String): Boolean {
        val type = getCandidateType(candidateSdp) ?: return false

        return when (type) {
            "host" -> {
                if (!allowDirectP2P) return false
                if (stripLocalIps && isPrivateNetworkCandidate(candidateSdp)) return false
                true
            }
            "srflx", "prflx" -> allowSrflxCandidates
            "relay" -> allowRelayCandidates
            else -> false
        }
    }
}
