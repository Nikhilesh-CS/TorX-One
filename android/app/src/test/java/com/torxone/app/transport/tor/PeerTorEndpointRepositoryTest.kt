package com.torxone.app.transport.tor

import com.torxone.app.agent.EndToEndPipelineTest.InMemoryConnectionDao
import com.torxone.app.data.dao.PeerTorEndpointDao
import com.torxone.app.data.entity.ConnectionDbEntity
import com.torxone.app.data.entity.PeerTorEndpointEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PeerTorEndpointRepositoryTest {
    class MemoryEndpoints : PeerTorEndpointDao {
        val rows = mutableMapOf<String, PeerTorEndpointEntity>()
        override suspend fun getAll() = rows.values.toList()
        override suspend fun getByRelationshipId(relationshipId: String) = rows[relationshipId]
        override suspend fun upsert(endpoint: PeerTorEndpointEntity) { rows[endpoint.relationshipId] = endpoint }
        override suspend fun delete(relationshipId: String) { rows.remove(relationshipId) }
    }
    private fun connections() = InMemoryConnectionDao().apply {
        connections["conn"] = ConnectionDbEntity("conn", "rel", 1, "send", "recv", ByteArray(32), ByteArray(32))
    }
    @Test fun signedEndpointSurvivesRestartWithoutPreferences() = runBlocking {
        val dao = MemoryEndpoints()
        val route = TorRoute("a".repeat(56) + ".onion")
        PeerTorEndpointRepository(dao).bindVerified("rel", route)
        val restarted = PeerTorEndpointRepository(dao)
        restarted.restore(connections(), TorRouteManager())
        assertEquals(route, restarted.resolve("rel"))
        assertNull(restarted.resolve("unknown"))
    }
    @Test fun legacyImportIsOneTimeAndSignedEndpointWins() = runBlocking {
        val dao = MemoryEndpoints()
        val legacy = TorRouteManager()
        val route = TorRoute("b".repeat(56) + ".onion")
        legacy.bind("send", route)
        val repo = PeerTorEndpointRepository(dao)
        repo.restore(connections(), legacy)
        assertEquals(route, repo.resolve("rel"))
        assertEquals("LEGACY_ROUTE", dao.rows["rel"]?.source)
        assertNull(legacy.resolve("send"))
        val signed = TorRoute("c".repeat(56) + ".onion")
        repo.bindVerified("rel", signed)
        legacy.bind("send", route)
        repo.restore(connections(), legacy)
        assertEquals(signed, repo.resolve("rel"))
        assertEquals("SIGNED_PAIRING", dao.rows["rel"]?.source)
    }
    @Test fun cacheIsUpdatedOnlyAfterDurableWriteSucceeds() = runBlocking {
        val dao = object : PeerTorEndpointDao by MemoryEndpoints() {
            override suspend fun upsert(endpoint: PeerTorEndpointEntity) { throw java.io.IOException("database failure") }
        }
        val repo = PeerTorEndpointRepository(dao)
        try { repo.bindVerified("rel", TorRoute("a".repeat(56) + ".onion")); fail("Expected write failure") }
        catch (_: java.io.IOException) {}
        assertNull(repo.resolve("rel"))
    }

    @Test fun cacheMissReloadsOnlyAuthoritativeRelationshipRowAndNotifiesAfterCommit() = runBlocking {
        val dao = MemoryEndpoints()
        val signed = TorRoute("a".repeat(56) + ".onion")
        dao.upsert(PeerTorEndpointRepository.entity("rel", signed))
        val repo = PeerTorEndpointRepository(dao)
        val changed = mutableListOf<String>()
        repo.onVerifiedEndpointChanged = { id ->
            assertEquals(dao.rows[id]?.onionAddress, repo.resolve(id)?.onionHost)
            changed += id
        }
        assertNull(repo.resolve("rel"))
        assertEquals(signed, repo.resolvePersisted("rel"))
        assertEquals(listOf("rel"), changed)
        assertNull(repo.resolvePersisted("other"))
        repo.bindVerified("rel", signed)
        assertEquals(listOf("rel", "rel"), changed)
        val fresh = TorRoute("c".repeat(56) + ".onion")
        repo.bindVerified("rel", fresh)
        assertEquals(fresh, repo.resolvePersisted("rel"))
        dao.delete("rel")
        repo.refresh("rel")
        assertNull(repo.resolve("rel"))
    }

    @Test fun reviewedRouteChangeCommitsOnlyTheReviewedRelationship() = runBlocking {
        val dao = MemoryEndpoints()
        val repo = PeerTorEndpointRepository(dao)
        val old = TorRoute("a".repeat(56) + ".onion")
        val fresh = TorRoute("b".repeat(56) + ".onion")
        repo.bindVerified("rel", old)
        repo.bindVerified("other", old)
        assertTrue(repo.bindVerifiedIfUnchanged("rel", fresh, old))
        assertEquals(fresh, repo.resolve("rel"))
        assertEquals(fresh.onionHost, dao.rows["rel"]?.onionAddress)
        assertEquals(old, repo.resolve("other"))
        assertEquals(old.onionHost, dao.rows["other"]?.onionAddress)
    }

    @Test fun staleReviewedRouteCannotReplaceAnInterveningVerifiedUpdate() = runBlocking {
        val dao = MemoryEndpoints()
        val repo = PeerTorEndpointRepository(dao)
        val old = TorRoute("a".repeat(56) + ".onion")
        val reviewed = TorRoute("b".repeat(56) + ".onion")
        val intervening = TorRoute("c".repeat(56) + ".onion")
        repo.bindVerified("rel", old)
        repo.bindVerified("rel", intervening)
        assertFalse(repo.bindVerifiedIfUnchanged("rel", reviewed, old))
        assertEquals(intervening, repo.resolve("rel"))
        assertEquals(intervening.onionHost, dao.rows["rel"]?.onionAddress)
    }

    @Test fun reviewedRouteWriteFailurePreservesPreviouslyPublishedEndpoint() = runBlocking {
        val backing = MemoryEndpoints()
        var failWrite = false
        val dao = object : PeerTorEndpointDao by backing {
            override suspend fun upsert(endpoint: PeerTorEndpointEntity) {
                if (failWrite) throw java.io.IOException("write rejected")
                backing.upsert(endpoint)
            }
        }
        val repo = PeerTorEndpointRepository(dao)
        val old = TorRoute("a".repeat(56) + ".onion")
        repo.bindVerified("rel", old)
        failWrite = true
        try {
            repo.bindVerifiedIfUnchanged("rel", TorRoute("b".repeat(56) + ".onion"), old)
            fail("Expected durable write failure")
        } catch (_: java.io.IOException) { }
        assertEquals(old, repo.resolve("rel"))
        assertEquals(old.onionHost, backing.rows["rel"]?.onionAddress)
    }

    @Test fun bootstrapRetryOnlyFillsMissingRouteAndCannotRollBackVerifiedRefresh() = runBlocking {
        val dao = MemoryEndpoints()
        val old = TorRoute("a".repeat(56) + ".onion")
        val fresh = TorRoute("b".repeat(56) + ".onion")
        val repo = PeerTorEndpointRepository(dao)
        repo.bindBootstrapIfMissing("rel", old)
        assertEquals(old, repo.resolve("rel"))
        repo.bindVerified("rel", fresh)
        val restarted = PeerTorEndpointRepository(dao)
        val changes = mutableListOf<String>()
        restarted.onVerifiedEndpointChanged = { changes += it }
        restarted.bindBootstrapIfMissing("rel", old)
        assertEquals(fresh, restarted.resolve("rel"))
        assertEquals(fresh.onionHost, dao.rows["rel"]?.onionAddress)
        assertEquals(listOf("rel"), changes)
        restarted.bindBootstrapIfMissing("rel", old)
        assertEquals(listOf("rel"), changes)
    }
}
