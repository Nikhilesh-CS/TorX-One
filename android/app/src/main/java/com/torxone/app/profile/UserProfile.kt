package com.torxone.app.profile

/**
 * User-facing profile model.
 *
 * Strictly separated from TorXIdentity:
 *   - TorXIdentity holds long-lived cryptographic material.
 *   - UserProfile holds mutable display metadata.
 *
 * Profile changes increment [profileVersion] so peers can detect stale data.
 */
data class UserProfile(
    /** Links to TorXIdentity.identityId */
    val identityId: String,

    /** Display name shown to contacts */
    val displayName: String,

    /** Optional "about" / status text */
    val about: String = "",

    /** Profile photo URI (local file path or content URI), null = no photo */
    val avatarUri: String? = null,

    /** Monotonically increasing version for sync */
    val profileVersion: Int = 1,

    /** When the profile was last updated */
    val updatedAt: Long = System.currentTimeMillis()
)
