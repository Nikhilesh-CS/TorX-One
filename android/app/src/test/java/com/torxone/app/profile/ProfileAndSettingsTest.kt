package com.torxone.app.profile

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/**
 * Tests for the Landing + Profile + Founder Account + Settings milestone.
 *
 * Tests UserProfile model, FounderIdentity logic, and SettingsUiState defaults.
 * DataStore tests require Android instrumentation context, so we test the pure
 * logic layer here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileAndSettingsTest {

    // ═══════════════════════════════════════════════════════════════
    //  UserProfile Model
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `UserProfile has correct defaults`() {
        val profile = UserProfile(
            identityId = "test-id-123",
            displayName = "Alice"
        )

        assertEquals("test-id-123", profile.identityId)
        assertEquals("Alice", profile.displayName)
        assertEquals("", profile.about)
        assertNull(profile.avatarUri)
        assertEquals(1, profile.profileVersion)
        assertTrue(profile.updatedAt > 0)
    }

    @Test
    fun `UserProfile allows setting about and avatarUri`() {
        val profile = UserProfile(
            identityId = "id",
            displayName = "Bob",
            about = "Building the future",
            avatarUri = "content://media/photo.jpg",
            profileVersion = 3
        )

        assertEquals("Building the future", profile.about)
        assertEquals("content://media/photo.jpg", profile.avatarUri)
        assertEquals(3, profile.profileVersion)
    }

    @Test
    fun `UserProfile copy increments version correctly`() {
        val v1 = UserProfile(identityId = "id", displayName = "A")
        val v2 = v1.copy(displayName = "B", profileVersion = v1.profileVersion + 1)

        assertEquals(1, v1.profileVersion)
        assertEquals(2, v2.profileVersion)
        assertEquals("A", v1.displayName)
        assertEquals("B", v2.displayName)
    }

    // ═══════════════════════════════════════════════════════════════
    //  FounderIdentity
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `founder key is 32 bytes after decode`() {
        // Placeholder key is all zeros
        val key = FounderIdentity.founderPublicKey
        assertEquals(32, key.size)
    }

    @Test
    fun `isFounder returns true for matching key`() {
        // The placeholder founder key is 32 zero bytes
        val founderKey = ByteArray(32) { 0 }
        assertTrue(FounderIdentity.isFounder(founderKey))
    }

    @Test
    fun `isFounder returns false for non-matching key`() {
        val randomKey = ByteArray(32) { (it + 1).toByte() }
        assertFalse(FounderIdentity.isFounder(randomKey))
    }

    @Test
    fun `isFounderIdentity returns false for null`() {
        assertFalse(FounderIdentity.isFounderIdentity(null))
    }

    @Test
    fun `isFounderIdentity returns false for wrong-size key`() {
        assertFalse(FounderIdentity.isFounderIdentity(ByteArray(16) { 0 }))
    }

    // ═══════════════════════════════════════════════════════════════
    //  SettingsUiState Defaults
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `SettingsUiState has secure defaults`() {
        val state = SettingsUiState()

        // Privacy defaults — visible by default (like WhatsApp)
        assertTrue(state.lastSeenVisible)
        assertTrue(state.onlineVisible)
        assertTrue(state.readReceiptsEnabled)

        // Notification defaults — enabled
        assertTrue(state.notificationsEnabled)
        assertTrue(state.soundEnabled)
        assertTrue(state.vibrationEnabled)
        assertEquals("FULL", state.notificationPreviewMode)

        // Security defaults — not locked by default
        assertFalse(state.appLockEnabled)
        assertFalse(state.screenSecurityEnabled)

        // Connection defaults
        assertTrue(state.autoConnectNearby)
        assertFalse(state.lowBandwidthMode)

        // Appearance defaults
        assertEquals("SYSTEM", state.themeMode)
        assertTrue(state.dynamicColorsEnabled)

        // Data defaults
        assertTrue(state.autoDownloadMedia)

        // Profile defaults — empty until set
        assertEquals("", state.displayName)
        assertEquals("", state.about)
    }

    @Test
    fun `SettingsUiState copy correctly updates fields`() {
        val initial = SettingsUiState()
        val updated = initial.copy(
            displayName = "Charlie",
            about = "Status message",
            readReceiptsEnabled = false,
            appLockEnabled = true,
            themeMode = "DARK",
            notificationPreviewMode = "HIDDEN"
        )

        assertEquals("Charlie", updated.displayName)
        assertEquals("Status message", updated.about)
        assertFalse(updated.readReceiptsEnabled)
        assertTrue(updated.appLockEnabled)
        assertEquals("DARK", updated.themeMode)
        assertEquals("HIDDEN", updated.notificationPreviewMode)

        // Unmodified fields should remain as defaults
        assertTrue(updated.lastSeenVisible)
        assertTrue(updated.notificationsEnabled)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Screen Navigation Model
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Screen sealed class has all expected types`() {
        // Verify all screen types exist and can be instantiated
        val screens = listOf(
            com.torxone.app.Screen.Landing,
            com.torxone.app.Screen.ConversationList,
            com.torxone.app.Screen.ArchivedList,
            com.torxone.app.Screen.Chat("conv-1", "Alice"),
            com.torxone.app.Screen.ContactInfo("conv-1", "Alice"),
            com.torxone.app.Screen.Settings,
            com.torxone.app.Screen.Profile
        )

        assertEquals(7, screens.size)
    }

    @Test
    fun `Screen Chat equality works`() {
        val s1 = com.torxone.app.Screen.Chat("c1", "Alice")
        val s2 = com.torxone.app.Screen.Chat("c1", "Alice")
        val s3 = com.torxone.app.Screen.Chat("c2", "Bob")

        assertEquals(s1, s2)
        assertNotEquals(s1, s3)
    }

    @Test
    fun `Screen Settings and Profile are singletons`() {
        assertSame(com.torxone.app.Screen.Settings, com.torxone.app.Screen.Settings)
        assertSame(com.torxone.app.Screen.Profile, com.torxone.app.Screen.Profile)
        assertSame(com.torxone.app.Screen.Landing, com.torxone.app.Screen.Landing)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Profile Version Monotonicity
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `profile version should increase with each update`() {
        var profile = UserProfile(identityId = "id", displayName = "V1")
        val versions = mutableListOf(profile.profileVersion)

        repeat(5) { i ->
            profile = profile.copy(
                displayName = "V${i + 2}",
                profileVersion = profile.profileVersion + 1
            )
            versions.add(profile.profileVersion)
        }

        // Verify strictly increasing
        for (i in 1 until versions.size) {
            assertTrue(
                "Version ${versions[i]} should be > ${versions[i - 1]}",
                versions[i] > versions[i - 1]
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Founder Key Pinning Integrity
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `founder key is deterministic across multiple reads`() {
        val key1 = FounderIdentity.founderPublicKey
        val key2 = FounderIdentity.founderPublicKey
        assertSame(key1, key2) // lazy val should return same instance
    }

    @Test
    fun `founder badge is not grantable via mutation`() {
        // Ensure that copying a non-founder key and modifying it
        // doesn't accidentally match the founder key
        val fakeKey = FounderIdentity.founderPublicKey.clone()
        fakeKey[0] = 0xFF.toByte()

        assertFalse(FounderIdentity.isFounder(fakeKey))
    }
}
