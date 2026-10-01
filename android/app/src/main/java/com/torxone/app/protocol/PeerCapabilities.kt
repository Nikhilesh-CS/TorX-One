package com.torxone.app.protocol

import java.util.Base64

/** Advertise only features with a registered receive path. Absence means unsupported. */
enum class PeerFeature {
    UNKNOWN_MESSAGE_V1, PROFILE_INLINE_V2, PROFILE_AVATAR_V1, DISAPPEARING_V1,
    VIEW_ONCE_V1, POLL_V1, EVENT_V1, PIN_MESSAGE_V1, MENTION_V1, GROUP_PERMISSIONS_V1
}

object PeerCapabilitiesCodec {
    private const val MARKER = "TXCAPS1:"
    val supported = setOf(PeerFeature.UNKNOWN_MESSAGE_V1, PeerFeature.PROFILE_INLINE_V2, PeerFeature.DISAPPEARING_V1)

    // PROFILE_UPDATE is understood by old peers. Its first two lines stay unchanged.
    fun encode(name: String, about: String, features: Set<PeerFeature> = supported): ByteArray {
        require(name.isNotBlank() && name.length <= 40 && '\n' !in name)
        require(about.length <= 140)
        val data = features.map { it.name }.sorted().joinToString(",")
        return "$name\n${about.replace('\n', ' ')}\n$MARKER${Base64.getEncoder().encodeToString(data.toByteArray())}".toByteArray()
    }

    fun decode(payload: ByteArray): Set<String>? {
        require(payload.size <= 36 * 1024)
        val extension = payload.toString(Charsets.UTF_8).split('\n', limit = 3).getOrNull(2) ?: return null
        if (!extension.startsWith(MARKER)) return null
        require(extension.length <= 4096)
        val text = Base64.getDecoder().decode(extension.removePrefix(MARKER)).toString(Charsets.UTF_8)
        if (text.isEmpty()) return emptySet()
        val features = text.split(',')
        require(features.size <= 64 && features.all { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) })
        return features.toSet()
    }
}
