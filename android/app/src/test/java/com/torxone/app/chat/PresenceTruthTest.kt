package com.torxone.app.chat

import com.torxone.app.agent.*
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.*
import com.torxone.app.protocol.PresenceUpdate
import com.torxone.app.protocol.PresenceState
import com.torxone.app.transport.TransportRouter
import com.torxone.app.transport.nearby.DirectRouteTable
import com.torxone.app.transport.nearby.RouteState
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PresenceTruthTest {
    private fun create(scope: kotlinx.coroutines.CoroutineScope, load: suspend (String) -> Long? = { null },
                       save: suspend (String, Long?) -> Unit = { _, _ -> },
                       relationships: suspend () -> List<String> = { emptyList() },
                       sender: (suspend (String, PresenceState) -> Unit)? = null) = PresenceService(
        ConnectionManager(keyProtector = NoOpKeyProtector()),
        DoubleRatchetSessionCrypto(EndToEndPipelineTest.InMemorySessionStore()),
        TorXAgent(TransportRouter(), EndToEndPipelineTest.InMemoryOutboxStore()), DirectRouteTable(),
        { null }, coroutineScope = scope, loadLastSeen = load, saveLastSeen = save,
        relationshipsProvider = relationships, presenceSender = sender)

    @Test fun observingOrChangingRoutesDoesNotFabricateLastSeen() = runTest {
        val service = create(backgroundScope)
        val flow = service.observePresence("peer")
        assertNull(flow.value.lastSeenAt)
        service.handleRouteStateChanged("peer", RouteState.READY, 123)
        assertEquals(PresenceStatus.UNKNOWN, flow.value.status)
        service.handleRouteStateChanged("peer", RouteState.DISCONNECTED, 123)
        assertNull(flow.value.lastSeenAt)
    }

    @Test fun confirmedTimestampSurvivesNewServiceAndRouteLoss() = runTest {
        var stored: Long? = null
        val timestamp = System.currentTimeMillis() - 30_000
        val first = create(backgroundScope, { stored }, { _, value -> stored = value })
        first.onPresenceUpdateReceived("peer", PresenceUpdate(PresenceState.OFFLINE, timestamp))
        runCurrent()
        val second = create(backgroundScope, { stored })
        second.observePresence("peer")
        runCurrent()
        second.handleRouteStateChanged("peer", RouteState.DISCONNECTED, System.currentTimeMillis())
        assertEquals(timestamp, second.getPresence("peer").lastSeenAt)
    }

    @Test fun staleOrFutureRemoteEventsCannotReplaceConfirmedActivity() = runTest {
        val service = create(backgroundScope)
        val now = System.currentTimeMillis()
        service.onPresenceUpdateReceived("peer", PresenceUpdate(PresenceState.OFFLINE, now - 1000))
        service.onPresenceUpdateReceived("peer", PresenceUpdate(PresenceState.ONLINE, now - 2000))
        service.onPresenceUpdateReceived("peer", PresenceUpdate(PresenceState.ONLINE, now + 120_000))
        assertEquals(PresenceStatus.OFFLINE, service.getPresence("peer").status)
        assertEquals(now - 1000, service.getPresence("peer").lastSeenAt)
    }

    @Test fun onlineExpiresWithoutInventingANewTimestamp() = runTest {
        val service = create(backgroundScope)
        val timestamp = System.currentTimeMillis() - 1000
        service.onPresenceUpdateReceived("peer", PresenceUpdate(PresenceState.ONLINE, timestamp))
        runCurrent()
        advanceTimeBy(PresenceService.PRESENCE_TTL_MS + 1)
        runCurrent()
        assertEquals(PresenceStatus.OFFLINE, service.getPresence("peer").status)
        assertEquals(timestamp, service.getPresence("peer").lastSeenAt)
    }

    @Test fun peerPrivacyClearsBothVisibleAndPersistedLastSeen() = runTest {
        var stored: Long? = 1
        val service = create(backgroundScope, save = { _, value -> stored = value })
        service.onPresenceUpdateReceived("peer", PresenceUpdate(PresenceState.OFFLINE,
            System.currentTimeMillis(), lastSeenVisible = false))
        runCurrent()
        assertNull(service.getPresence("peer").lastSeenAt)
        assertNull(stored)
    }

    @Test fun privacyFlagRoundTripsAndOldPacketsStillDecode() {
        val update = PresenceUpdate(PresenceState.OFFLINE, 100, false)
        assertEquals(update, PresenceUpdate.fromByteArray(update.toByteArray()))
        val old = update.toByteArray().dropLast(1).toByteArray()
        assertTrue(PresenceUpdate.fromByteArray(old).lastSeenVisible)
    }

    @Test fun unreachableContactDoesNotDelayHealthyPresenceHeartbeats() = runTest {
        val sent = mutableListOf<String>()
        val service = create(backgroundScope, relationships = { listOf("slow", "healthy") },
            sender = { relationship, state ->
                if (state == PresenceState.ONLINE) {
                    sent += relationship
                    if (relationship == "slow") kotlinx.coroutines.delay(60_000)
                }
            })
        service.setForeground(true); runCurrent()
        advanceTimeBy(20_001); runCurrent()
        assertEquals(2, sent.count { it == "healthy" })
        assertEquals(1, sent.count { it == "slow" })
        service.setForeground(false); runCurrent()
    }
}
