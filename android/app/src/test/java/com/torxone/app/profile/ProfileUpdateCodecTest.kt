package com.torxone.app.profile

import com.torxone.app.data.entity.ContactEntity
import org.junit.Assert.*
import org.junit.Test

class ProfileUpdateCodecTest {
    @Test fun photoAndMultilineAboutRoundTrip() {
        val bytes = ByteArray(ProfileUpdateCodec.MAX_AVATAR_BYTES) { it.toByte() }
        val update = ProfileUpdate("Alice", "Hello\nWorld", 123, bytes, true)
        val encoded = ProfileUpdateCodec.encode(update)
        assertTrue(encoded.size < 36 * 1024)
        val decoded = ProfileUpdateCodec.decode(encoded)
        assertEquals(update.name, decoded.name); assertEquals(update.about, decoded.about)
        assertEquals(update.version, decoded.version); assertArrayEquals(bytes, decoded.avatar)
        assertTrue(decoded.hasAvatarUpdate)
        assertEquals("Alice", encoded.toString(Charsets.UTF_8).substringBefore('\n'))
    }
    @Test fun photoRemovalIsExplicitAndSurvivesEncoding() {
        val decoded = ProfileUpdateCodec.decode(ProfileUpdateCodec.encode(ProfileUpdate("Alice", "", 12, null, true)))
        assertTrue(decoded.hasAvatarUpdate); assertNull(decoded.avatar)
    }
    @Test fun legacyNameAndAboutRemainSupportedWithoutClearingPhoto() {
        val decoded = ProfileUpdateCodec.decode("Alice\nAbout".toByteArray())
        assertEquals("About", decoded.about); assertFalse(decoded.hasAvatarUpdate)
    }
    @Test fun oversizedPhotoRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUpdateCodec.encode(ProfileUpdate("Alice", "", 1, ByteArray(24577), true))
        }
    }
    @Test fun malformedExtensionRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUpdateCodec.decode("Alice\nAbout\nTXPROFILE2:!invalid!".toByteArray())
        }
    }
    @Test fun hashingStableAndContentAddressed() {
        assertEquals(ProfileUpdateCodec.hash(byteArrayOf(1, 2)), ProfileUpdateCodec.hash(byteArrayOf(1, 2)))
        assertNotEquals(ProfileUpdateCodec.hash(byteArrayOf(1)), ProfileUpdateCodec.hash(byteArrayOf(2)))
    }
    @Test fun contactMetadataChangeInvalidatesComposeFlowEquality() {
        val contact = ContactEntity("id", "rel", "Alice", signingPublicKey = ByteArray(32), conversationId = "chat")
        assertEquals(contact, contact.copy(signingPublicKey = ByteArray(32)))
        assertNotEquals(contact, contact.copy(displayName = "New name"))
        assertNotEquals(contact, contact.copy(about = "New about"))
        assertNotEquals(contact, contact.copy(avatarHash = "hash"))
    }
}
