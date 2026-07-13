package com.torxone.app.identity.backup

import androidx.annotation.Keep

@Keep
data class IdentityBackupDto(
    // Versioning Metadata
    val backupVersion: Int,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAt: String,
    val identityVersion: Int,
    
    // Core Identity
    val identityName: String,
    val creationTimestamp: Long,
    
    // IdentityManager core fields
    val encPubHex: String,
    val encSecHex: String,
    val sigPubHex: String,
    val sigSecHex: String,
    val onionAddress: String,
    
    // Tor hidden service files (encoded as Base64 strings for raw binary files, or plain text for hostname)
    val torHsEd25519PublicKeyB64: String,
    val torHsEd25519SecretKeyB64: String,
    
    // Profile
    val bio: String = "",
    val statusMessage: String = "",
    val avatarHash: String? = null,
    val profileHash: String = "",
    val profileVersion: Int = 1,
    val lastUpdatedAt: Long = 0L,
    val avatarWebPB64: String? = null
)
