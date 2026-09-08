package com.torxone.app.group

import com.torxone.app.data.GroupKeyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GroupCryptoManagerTest {
    private fun key(groupId: String = "group-a", version: Int = 1) =
        GroupKeyEntity(groupId, version, GroupCryptoManager.newKeyBase64(), 1L)

    @Test fun encryptDecryptSuccess() {
        val key = key()
        val encrypted = GroupCryptoManager.encrypt(key, "{\"messageId\":\"one\",\"senderKey\":\"alice\"}")
        assertEquals("{\"messageId\":\"one\",\"senderKey\":\"alice\"}", GroupCryptoManager.decrypt(key, encrypted.ciphertextBase64, encrypted.ivBase64))
    }

    @Test fun decryptWithWrongKeyFails() {
        val encrypted = GroupCryptoManager.encrypt(key(), "secret")
        try {
            GroupCryptoManager.decrypt(key(), encrypted.ciphertextBase64, encrypted.ivBase64)
            fail("A different group key must not decrypt ciphertext")
        } catch (_: Exception) { }
    }

    @Test fun decryptWithTamperedIvFails() {
        val key = key()
        val encrypted = GroupCryptoManager.encrypt(key, "secret")
        val tamperedIv = encrypted.ivBase64.dropLast(1) + if (encrypted.ivBase64.last() == 'A') "B" else "A"
        try {
            GroupCryptoManager.decrypt(key, encrypted.ciphertextBase64, tamperedIv)
            fail("Tampered IV must fail AES-GCM authentication")
        } catch (_: Exception) { }
    }
}
