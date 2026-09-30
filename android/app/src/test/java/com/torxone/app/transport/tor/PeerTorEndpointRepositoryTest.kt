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
}
