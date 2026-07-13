package com.torxone.app.identity.profile

object FounderProfile {
    const val role = "Founder"
    const val launchDate = "July 12, 2026"
    const val bio = "Privacy isn't a feature. It's a right. Building the future of secure communication."
    const val statusMessage = "Your Network • Your Privacy • Your Freedom"
    const val tooltip = "Creator of TorX One"
    const val founderSigningPublicKeyHex = "b061d023f949e002f9fe3d46133048fe6c273b3b7b9f1894eec8bf2a78d45661"

    fun isFounderSigningKey(signingPublicKeyHex: String?): Boolean {
        return signingPublicKeyHex.equals(founderSigningPublicKeyHex, ignoreCase = true)
    }

    fun isFounderProfile(signingPublicKeyHex: String?): Boolean {
        return isFounderSigningKey(signingPublicKeyHex)
    }
}
