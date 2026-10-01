package com.torxone.app.security

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.torxone.app.crypto.AndroidKeystoreKeyProtector
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.media.MediaStorage
import com.torxone.app.protocol.OpaqueTransportEnvelope
import com.torxone.app.protocol.ProtocolCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID

/** Security probes use isolated fixture keys/databases, never a user's live session. */
@RunWith(AndroidJUnit4::class)
class SecurityProbeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun keystoreKeyIsNonexportableAndTamperedOrWrongKeyCiphertextFailsClosed() {
        val first = "torx_probe_${UUID.randomUUID()}"
        val second = "torx_probe_${UUID.randomUUID()}"
        try {
            val protector = AndroidKeystoreKeyProtector(first)
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val wrapped = protector.wrap(secret)
            assertFalse(wrapped.contentEquals(secret))
            assertArrayEquals(secret, protector.unwrap(wrapped))
            val damaged = wrapped.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            assertTrue(runCatching { protector.unwrap(damaged) }.isFailure)
            val wrongKey = AndroidKeystoreKeyProtector(second)
            wrongKey.wrap(ByteArray(32)) // Create only the isolated second fixture key.
            assertTrue(runCatching { wrongKey.unwrap(wrapped) }.isFailure)
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertNull(store.getKey(first, null).encoded)
        } finally {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(first); store.deleteEntry(second)
        }
    }

    @Test fun sqlcipherDatabaseRejectsWrongPassphraseAndReopensWithOriginalKey() = runBlocking {
        System.loadLibrary("sqlcipher")
        val name = "security-probe-${UUID.randomUUID()}.db"
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        fun open(key: ByteArray) = Room.databaseBuilder(context, TorXDatabase::class.java, name)
            .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(key.copyOf())).build()
        var db = open(passphrase)
        try {
            db.conversationDao().upsert(ConversationEntity("fixture", title = "Synthetic security probe"))
            db.messageDao().insertIfAbsent(MessageEntity("fixture-message", "fixture", "fixture-peer", "TEXT",
                "synthetic private fixture", MessageDirection.INCOMING, "DELIVERED"))
            db.close()
            val header = context.getDatabasePath(name).inputStream().use { it.readNBytes(16) }
            assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
            val wrong = passphrase.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            db = open(wrong)
            assertTrue(runCatching { db.messageDao().getById("fixture-message") }.isFailure)
            db.close()
            db = open(passphrase)
            assertEquals("synthetic private fixture", db.messageDao().getById("fixture-message")?.body)
        } finally { db.close(); context.deleteDatabase(name); passphrase.fill(0) }
    }

    @Test fun malformedTransportFramesFailOnRealAndroidRuntime() {
        val valid = ProtocolCodec.encodeTransportEnvelope(OpaqueTransportEnvelope(version = 1,
            envelopeId = "fixture", queueAddress = "fixture-queue", opaqueCiphertext = byteArrayOf(1, 2, 3),
            queueAuthenticator = ByteArray(32)))
        for (size in 0 until minOf(32, valid.size)) {
            assertTrue("Truncated frame accepted at $size", runCatching { ProtocolCodec.decodeTransportEnvelope(valid.copyOf(size)) }.isFailure)
        }
        val wrongMagic = valid.copyOf().apply { this[0] = 0 }
        assertTrue(runCatching { ProtocolCodec.decodeTransportEnvelope(wrongMagic) }.isFailure)
        assertTrue(runCatching { ProtocolCodec.decodeTransportEnvelope(valid + byteArrayOf(0)) }.isFailure)
    }

    @Test fun maliciousMediaPathsCannotEscapeOwnedStorage() {
        val base = File(context.cacheDir, "security-probe-${UUID.randomUUID()}")
        val storage = MediaStorage(customBaseDir = base)
        try {
            val saved = storage.saveIncomingFile(UUID.randomUUID().toString(), "../../outside.txt", byteArrayOf(1))
            assertTrue(saved.canonicalPath.startsWith(base.canonicalPath + File.separator))
            assertTrue(runCatching { storage.getTempEncryptedFile("../../outside") }.isFailure)
            val outside = File(context.cacheDir, "security-probe-outside-${UUID.randomUUID()}").apply { writeText("fixture") }
            try {
                assertFalse(storage.deleteOwnedFileConfirmed(outside.absolutePath))
                assertTrue(outside.exists())
            } finally { outside.delete() }
        } finally { base.deleteRecursively() }
    }
}
