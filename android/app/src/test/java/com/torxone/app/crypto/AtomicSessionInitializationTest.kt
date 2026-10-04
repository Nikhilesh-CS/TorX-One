package com.torxone.app.crypto

import com.torxone.app.agent.EndToEndPipelineTest
import com.torxone.app.identity.IdentityCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AtomicSessionInitializationTest {
    @Test fun failedPairingCommitDoesNotPublishSessionAndCanRetrySameKeys() = runBlocking {
        val store = EndToEndPipelineTest.InMemorySessionStore()
        val crypto = DoubleRatchetSessionCrypto(store)
        val local = IdentityCrypto.generateX25519KeyPair()
        val remote = IdentityCrypto.generateX25519KeyPair()
        try {
            try {
                crypto.initializeAndCommit("pair", ByteArray(32) { 7 }, true, remote.publicKey,
                    local.privateKey, local.publicKey) { initial ->
                    assertNull(store.loadSession("pair"))
                    assertArrayEquals(local.publicKey, initial.localRatchetPublicKey)
                    throw java.io.IOException("Pairing transaction rejected")
                }
                fail("Commit failure must propagate")
            } catch (_: java.io.IOException) { }
            assertFalse(crypto.hasSession("pair"))
            val initial = crypto.initializeAndCommit("pair", ByteArray(32) { 7 }, true,
                remote.publicKey, local.privateKey, local.publicKey) { store.saveSession(it) }
            assertEquals(initial.sessionId, store.loadSession("pair")!!.sessionId)
            assertArrayEquals(local.privateKey, store.loadSession("pair")!!.localRatchetPrivateKey)
        } finally { crypto.closeSession("pair") }
    }

    @Test fun freshPairingCannotOverwriteExistingAdvancedRatchet() = runBlocking {
        val store = EndToEndPipelineTest.InMemorySessionStore()
        val crypto = DoubleRatchetSessionCrypto(store)
        val local = IdentityCrypto.generateX25519KeyPair()
        val remote = IdentityCrypto.generateX25519KeyPair()
        try {
            crypto.initializeSession("pair", ByteArray(32) { 7 }, true, remote.publicKey,
                local.privateKey, local.publicKey)
            crypto.encrypt("pair", byteArrayOf(1), byteArrayOf(2))
            val before = store.loadSession("pair")!!.copyState()
            var committed = false
            try {
                crypto.initializeAndCommit("pair", ByteArray(32) { 8 }, true, remote.publicKey,
                    local.privateKey, local.publicKey) { committed = true; store.saveSession(it) }
                fail("Existing ratchet must never be initialized again by pairing")
            } catch (_: IllegalStateException) { }
            assertFalse(committed)
            val after = store.loadSession("pair")!!
            assertEquals(before.sessionId, after.sessionId)
            assertEquals(before.sendMessageNumber, after.sendMessageNumber)
            assertArrayEquals(before.rootKey, after.rootKey)
            assertArrayEquals(before.sendChainKey, after.sendChainKey)
        } finally { crypto.closeSession("pair") }
    }

    @Test fun uncommittedPairingHoldsOnlyItsOwnActorAndPublishesAfterCommit() = runBlocking {
        val store = EndToEndPipelineTest.InMemorySessionStore()
        val crypto = DoubleRatchetSessionCrypto(store)
        val local = IdentityCrypto.generateX25519KeyPair()
        val remote = IdentityCrypto.generateX25519KeyPair()
        val entered = CompletableDeferred<Unit>()
        val commitAllowed = CompletableDeferred<Unit>()
        try {
            val blocked = async {
                crypto.initializeAndCommit("peer-a", ByteArray(32) { 7 }, true, remote.publicKey,
                    local.privateKey, local.publicKey) {
                    entered.complete(Unit)
                    commitAllowed.await()
                    store.saveSession(it)
                }
            }
            entered.await()
            assertNull(store.loadSession("peer-a"))
            withTimeout(2_000) {
                crypto.initializeAndCommit("peer-b", ByteArray(32) { 8 }, true, remote.publicKey,
                    local.privateKey, local.publicKey) { store.saveSession(it) }
            }
            assertFalse(blocked.isCompleted)
            assertNotNull(store.loadSession("peer-b"))
            commitAllowed.complete(Unit)
            assertEquals(blocked.await().sessionId, store.loadSession("peer-a")!!.sessionId)
        } finally {
            commitAllowed.complete(Unit)
            crypto.closeSession("peer-a")
            crypto.closeSession("peer-b")
        }
    }
}
