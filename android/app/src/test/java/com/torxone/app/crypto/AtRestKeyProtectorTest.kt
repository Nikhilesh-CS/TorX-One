package com.torxone.app.crypto

import com.torxone.app.data.dao.SessionDao
import com.torxone.app.data.dao.SkippedKeyDao
import com.torxone.app.data.entity.SessionDbEntity
import com.torxone.app.data.entity.SkippedKeyEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class AtRestKeyProtectorTest {

    private val masterKey = ByteArray(32) { (it + 1).toByte() }
    private val secureRandom = SecureRandom()

    private fun randomBytes(size: Int): ByteArray {
        val b = ByteArray(size)
        secureRandom.nextBytes(b)
        return b
    }

    @Test
    fun testWrappingAndUnwrappingRoundTripWithAesGcmKeyProtector() {
        val protector = AesGcmKeyProtector(masterKey)
        val secret = "super-secret-dh-private-key-32-b".toByteArray(Charsets.UTF_8)

        val wrapped = protector.wrap(secret)
        assertNotNull(wrapped)
        assertTrue("Wrapped secret must be longer than original due to IV and GCM tag", wrapped.size > secret.size)
        assertEquals("Wrapped secret must begin with magic byte 0x54", AesGcmKeyProtector.MAGIC, wrapped[0])
        assertFalse("Wrapped bytes must not equal plaintext secret", secret.contentEquals(wrapped))

        val unwrapped = protector.unwrap(wrapped)
        assertArrayEquals("Unwrapped secret must match original plaintext", secret, unwrapped)
    }

    @Test
    fun testTamperedPayloadFailsClosedWithSecurityException() {
        val protector = AesGcmKeyProtector(masterKey)
        val secret = randomBytes(32)
        val wrapped = protector.wrap(secret)

        // 1. Corrupt magic byte
        val badMagic = wrapped.clone()
        badMagic[0] = 0x00
        try {
            protector.unwrap(badMagic)
            fail("Expected SecurityException on invalid magic byte")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Invalid wrapped key payload") == true)
        }

        // 2. Truncated payload
        val truncated = wrapped.copyOf(15) // shorter than header + IV + tag
        try {
            protector.unwrap(truncated)
            fail("Expected SecurityException on truncated payload")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Invalid wrapped key payload") == true)
        }

        // 3. Tampered ciphertext (flip 1 bit)
        val tamperedCiphertext = wrapped.clone()
        tamperedCiphertext[1 + AesGcmKeyProtector.GCM_IV_LENGTH + 2] = (tamperedCiphertext[1 + AesGcmKeyProtector.GCM_IV_LENGTH + 2].toInt() xor 0x01).toByte()
        try {
            protector.unwrap(tamperedCiphertext)
            fail("Expected SecurityException on tampered ciphertext")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Cryptographic unwrapping failed") == true)
        }

        // 4. Tampered auth tag (flip last byte)
        val tamperedTag = wrapped.clone()
        tamperedTag[tamperedTag.size - 1] = (tamperedTag[tamperedTag.size - 1].toInt() xor 0xFF).toByte()
        try {
            protector.unwrap(tamperedTag)
            fail("Expected SecurityException on tampered auth tag")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Cryptographic unwrapping failed") == true)
        }

        // 5. Tampered IV
        val tamperedIv = wrapped.clone()
        tamperedIv[2] = (tamperedIv[2].toInt() xor 0xAA).toByte()
        try {
            protector.unwrap(tamperedIv)
            fail("Expected SecurityException on tampered IV")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Cryptographic unwrapping failed") == true)
        }
    }

    @Test
    fun testAndroidKeystoreKeyProtectorWithFallbackMasterKey() {
        val protector = AndroidKeystoreKeyProtector(
            keyAlias = "test_alias",
            fallbackMasterKey = masterKey
        )
        val secret = randomBytes(32)
        val wrapped = protector.wrap(secret)

        assertEquals(AndroidKeystoreKeyProtector.MAGIC, wrapped[0])
        val unwrapped = protector.unwrap(wrapped)
        assertArrayEquals(secret, unwrapped)

        // Tamper test
        val tampered = wrapped.clone()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        try {
            protector.unwrap(tampered)
            fail("Expected SecurityException for AndroidKeystoreKeyProtector on tampered payload")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Cryptographic unwrapping failed") == true)
        }
    }

    @Test
    fun testRoomSessionStoreWrapsAndUnwrapsKeys() = runBlocking {
        val sessionDao = FakeSessionDao()
        val skippedDao = FakeSkippedKeyDao()
        val protector = AesGcmKeyProtector(masterKey)

        val roomStore = RoomSessionStore(
            sessionDao = sessionDao,
            skippedKeyDao = skippedDao,
            keyProtector = protector
        )

        assertSame(protector, roomStore.keyProtector)

        val originalRootKey = randomBytes(32)
        val originalPrivKey = randomBytes(32)
        val originalPubKey = randomBytes(32)
        val originalSendChain = randomBytes(32)
        val originalRecvChain = randomBytes(32)

        val state = SessionState(
            sessionId = "session-123",
            relationshipId = "rel-123",
            rootKey = originalRootKey,
            localRatchetPrivateKey = originalPrivKey,
            localRatchetPublicKey = originalPubKey,
            remoteRatchetPublicKey = randomBytes(32),
            sendChainKey = originalSendChain,
            recvChainKey = originalRecvChain,
            sendMessageNumber = 5,
            receiveMessageNumber = 3,
            previousSendCount = 2,
            skippedKeys = mutableMapOf()
        )

        // Save session through RoomSessionStore
        roomStore.saveSession(state)

        // Verify entity stored in DAO has wrapped (encrypted) fields with magic byte
        val entity = sessionDao.getByRelationshipId("rel-123")
        assertNotNull(entity)
        assertEquals(AesGcmKeyProtector.MAGIC, entity!!.rootKey[0])
        assertEquals(AesGcmKeyProtector.MAGIC, entity.localDhPrivateKey[0])
        assertFalse(entity.rootKey.contentEquals(originalRootKey))
        assertFalse(entity.localDhPrivateKey.contentEquals(originalPrivKey))

        // Load session and verify all secrets are restored identically
        val loaded = roomStore.loadSession("rel-123")
        assertNotNull(loaded)
        assertArrayEquals(originalRootKey, loaded!!.rootKey)
        assertArrayEquals(originalPrivKey, loaded.localRatchetPrivateKey)
        assertArrayEquals(originalPubKey, loaded.localRatchetPublicKey)
        assertArrayEquals(originalSendChain, loaded.sendChainKey)
        assertArrayEquals(originalRecvChain, loaded.recvChainKey)
        assertEquals(5, loaded.sendMessageNumber)

        // Tamper rootKey in database
        val tamperedEntity = entity.copy(rootKey = entity.rootKey.clone().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        sessionDao.upsert(tamperedEntity)

        try {
            roomStore.loadSession("rel-123")
            fail("Expected SecurityException when loading tampered rootKey from database")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Cryptographic unwrapping failed") == true)
        }
    }

    class FakeSessionDao : SessionDao {
        val sessions = ConcurrentHashMap<String, SessionDbEntity>()
        override suspend fun getByRelationshipId(relationshipId: String): SessionDbEntity? = sessions[relationshipId]
        override suspend fun upsert(session: SessionDbEntity) { sessions[session.relationshipId] = session }
        override suspend fun deleteByRelationshipId(relationshipId: String) { sessions.remove(relationshipId) }
    }

    class FakeSkippedKeyDao : SkippedKeyDao {
        val keys = ConcurrentHashMap<String, MutableList<SkippedKeyEntity>>()
        override suspend fun getKeysForSession(sessionId: String): List<SkippedKeyEntity> = keys[sessionId] ?: emptyList()
        override suspend fun insert(key: SkippedKeyEntity) {
            keys.computeIfAbsent(key.sessionId) { mutableListOf() }.add(key)
        }
        override suspend fun insertAll(keys: List<SkippedKeyEntity>) {
            for (k in keys) {
                this.keys.computeIfAbsent(k.sessionId) { mutableListOf() }.add(k)
            }
        }
        override suspend fun deleteKey(sessionId: String, ratchetPublicKeyHex: String, counter: Int) {
            keys[sessionId]?.removeIf { it.ratchetPublicKeyHex == ratchetPublicKeyHex && it.counter == counter }
        }
        override suspend fun deleteKeysForSession(sessionId: String) { keys.remove(sessionId) }
    }
}
