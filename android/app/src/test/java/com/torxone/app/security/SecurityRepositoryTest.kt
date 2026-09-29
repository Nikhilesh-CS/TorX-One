package com.torxone.app.security

import com.torxone.app.crypto.AesGcmKeyProtector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecurityRepositoryTest {
    private fun repository() = VersionedSecurityRepository(AesGcmKeyProtector(ByteArray(32) { 9 }))

    @Test
    fun `V3 protects and reveals secret for its declared purpose`() {
        val repository = repository()
        val secret = ByteArray(32) { it.toByte() }
        val protected = repository.protect(secret, SecretPurpose.RELATIONSHIP_ROOT)

        assertEquals(3, protected.cryptoFormatVersion)
        assertFalse(secret.contentEquals(protected.bytes))
        assertArrayEquals(secret, repository.reveal(protected, SecretPurpose.RELATIONSHIP_ROOT))
    }

    @Test(expected = SecurityException::class)
    fun `V3 rejects use under a different purpose`() {
        val repository = repository()
        val protected = repository.protect(ByteArray(32) { 4 }, SecretPurpose.QUEUE_SEND_AUTH)
        repository.reveal(protected, SecretPurpose.QUEUE_RECEIVE_AUTH)
    }

    @Test(expected = SecurityException::class)
    fun `V3 rejects a modified purpose header`() {
        val repository = repository()
        val protected = repository.protect(ByteArray(32) { 5 }, SecretPurpose.SESSION_STATE)
        val changed = protected.bytes.copyOf().also { it[4] = SecretPurpose.IDENTITY_SIGNING.ordinal.toByte() }
        repository.reveal(ProtectedSecret(3, changed), SecretPurpose.IDENTITY_SIGNING)
    }

    @Test
    fun `V2 keystore payload migrates to V3`() {
        val legacyProtector = AesGcmKeyProtector(ByteArray(32) { 7 })
        val repository = VersionedSecurityRepository(legacyProtector)
        val secret = ByteArray(32) { 6 }

        val migrated = repository.migrate(
            legacyProtector.wrap(secret),
            VersionedSecurityRepository.FORMAT_V2_KEYSTORE_WRAPPED,
            SecretPurpose.PENDING_INVITE
        )

        assertEquals(3, migrated.cryptoFormatVersion)
        assertArrayEquals(secret, repository.reveal(migrated, SecretPurpose.PENDING_INVITE))
    }
}
