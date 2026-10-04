package com.torxone.app.ui.connection

import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.entity.OutboxEntity
import com.torxone.app.ui.components.MessageInfoLiveState
import org.junit.Assert.*
import org.junit.Test

class ConnectionUxTest {
    @Test fun terminalSequenceExposesBlockedRepairAndCannotOfferRetryOrAnAttemptTime() {
        for (status in listOf("FAILED", "EXPIRED")) {
            val terminal = outbox(status).copy(applicationSequence = 1)
            val state = requireNotNull(ConnectionUxPresentation.terminalOrderedHead(status, 1))
            assertEquals("Delivery blocked · repair required", state.first)
            assertTrue(state.second.contains(status.lowercase()))
            assertTrue(state.second.contains("does not reset"))
            // Even a mismatched restored message status must not offer a retry
            // which cannot resume its terminal ordered delivery.
            val info = MessageInfoPresentation.from(message(), listOf(terminal))
            assertFalse(info.canRetry)
            assertTrue(info.rows.first { it.label == "Delivery stage" }.value!!.contains("blocks later messages"))
            assertNull(info.rows.first { it.label == "Next scheduled attempt" }.timestamp)
            assertNull(ConnectionUxPresentation.terminalOrderedHead(status, null))
        }
        assertNull(ConnectionUxPresentation.terminalOrderedHead("WAITING_FOR_PEER", 1))
    }

    @Test fun waitingPeerExposesRepairAndDoesNotClaimAnAutomaticRetryTime() {
        val item = OutboxEntity("waiting", "message", "conversation", "connection", "queue",
            byteArrayOf(1), ByteArray(32), "WAITING_FOR_PEER", attemptCount = 8, nextAttemptAt = 400)
        val info = MessageInfoPresentation.from(MessageEntity("message", "conversation", "sender", "TEXT", "fixture",
            MessageDirection.OUTGOING, "WAITING_FOR_PEER"), listOf(item))
        assertTrue(info.canRetry)
        assertTrue(info.rows.first { it.label == "Delivery stage" }.value!!.contains("rescan"))
        assertNull(info.rows.first { it.label == "Next scheduled attempt" }.timestamp)
        assertNull(info.rows.first { it.label == "Delivered (peer ACK)" }.timestamp)
    }

    private fun message(direction: MessageDirection = MessageDirection.OUTGOING, status: String = "QUEUED") =
        MessageEntity("message", "conversation", "sender", "TEXT", "hello", direction, status, createdAt = 100)
    private fun outbox(status: String = "QUEUED", messageId: String = "message") = OutboxEntity(
        "delivery", messageId, "conversation", "connection", "queue", byteArrayOf(1), byteArrayOf(2), status,
        attemptCount = 2, nextAttemptAt = 400, createdAt = 200, updatedAt = 300)

    @Test fun deliveredMessageDoesNotInventRemovedOutboxHistory() {
        val info = MessageInfoPresentation.from(message(status = "DELIVERED").copy(deliveredAt = 500), emptyList())
        assertNull(info.rows.first { it.label == "Queued" }.timestamp)
        assertNull(info.rows.first { it.label == "Latest transport acceptance" }.timestamp)
        assertEquals(500L, info.rows.first { it.label == "Delivered (peer ACK)" }.timestamp)
        assertEquals("Historical transport not recorded", info.rows.first { it.label == "Transport used" }.value)
        assertFalse(info.canRetry)
    }

    @Test fun pendingCiphertextHasRealQueueTimeAndSafeRetry() {
        val info = MessageInfoPresentation.from(message(), listOf(outbox(), outbox(messageId = "unrelated")))
        assertEquals(200L, info.rows.first { it.label == "Queued" }.timestamp)
        assertEquals("1", info.rows.first { it.label == "Pending deliveries" }.value)
        assertEquals("Ciphertext persisted in outbox", info.rows.first { it.label == "Encryption" }.value)
        assertTrue(info.canRetry)
    }

    @Test fun transportAcceptanceIsNotPeerDelivery() {
        val info = MessageInfoPresentation.from(message(status = "TRANSPORT_ACCEPTED"), listOf(outbox("TRANSPORT_ACCEPTED")))
        assertEquals(300L, info.rows.first { it.label == "Latest transport acceptance" }.timestamp)
        assertNull(info.rows.first { it.label == "Delivered (peer ACK)" }.timestamp)
        assertEquals("No ACK time recorded", info.rows.first { it.label == "Delivered (peer ACK)" }.value)
    }

    @Test fun incomingReadTimestampIsClearlyLocal() {
        val info = MessageInfoPresentation.from(message(MessageDirection.INCOMING, "READ").copy(receivedAt = 500, readAt = 600), emptyList())
        assertEquals(600L, info.rows.first { it.label == "Opened locally" }.timestamp)
        assertTrue(info.rows.none { it.label == "Read (peer receipt)" })
        assertFalse(info.canRetry)
    }

    @Test fun deletedPendingMessageCannotRetry() {
        assertFalse(MessageInfoPresentation.from(message().copy(deletedAt = 900), listOf(outbox())).canRetry)
    }

    @Test fun staleOutboxCannotRetryAlreadyDeliveredMessage() {
        assertFalse(MessageInfoPresentation.from(message().copy(deliveredAt = 900), listOf(outbox())).canRetry)
    }

    @Test fun liveSnapshotTracksReceiptAndOutboxStatusChanges() {
        val queued = MessageInfoLiveState.ready(message(), listOf(outbox()))
        val delivered = MessageInfoLiveState.ready(message(status = "DELIVERED").copy(deliveredAt = 900), emptyList())
        assertNotEquals(queued, delivered)
        val accepted = MessageInfoLiveState.ready(message(), listOf(outbox("TRANSPORT_ACCEPTED")))
        assertNotEquals(queued, accepted)
        assertNotEquals(queued, MessageInfoLiveState.ready(message().copy(body = "edited"), listOf(outbox())))
    }

    @Test fun locallyReadyTorWithoutPeerRouteDoesNotClaimConnected() {
        val state = ConnectionUxPresentation.create("Wi-Fi", true,
            listOf(ConnectionPathInfo("Tor", "Ready locally; no route", false)), false, true, 1, false)
        assertEquals("Waiting for a connection", state.headline)
        assertEquals("Identity not marked verified", state.identity)
        assertTrue(state.detail.contains("retry automatically"))
    }

    @Test fun offlineNearbyRequiresExactReadyPeerRoute() {
        val state = ConnectionUxPresentation.create("No Internet", false,
            listOf(ConnectionPathInfo("Nearby", "Ready for this peer", true)), false, true, 2, false)
        assertEquals("Connected directly via Nearby", state.headline)
        assertEquals("Identity not marked verified", state.identity)
        assertTrue(state.detail.contains("peer ACK"))
    }

    @Test fun offlineSearchingIsNotAConnection() {
        val state = ConnectionUxPresentation.create("No Internet", false, emptyList(), null, null, 2, true)
        assertEquals("Internet unavailable · searching nearby", state.headline)
        assertEquals("No direct-peer verification record", state.identity)
        assertEquals("Session information unavailable", state.session)
    }

    @Test fun verifiedIdentityDoesNotDependOnTransportReadiness() {
        val state = ConnectionUxPresentation.create("Wi-Fi", true,
            listOf(ConnectionPathInfo("Tor", "Ready locally; peer route configured", true)), true, true, 0, false)
        assertEquals("Tor connected · peer unconfirmed", state.headline)
        assertEquals("Identity marked verified locally; manual safety-number comparison not recorded", state.identity)
        assertTrue(state.detail.contains("peer route is configured"))
    }

    @Test fun torWithBacklogShowsWaitingForPeerNotDelivered() {
        val state = ConnectionUxPresentation.create("Wi-Fi", true,
            listOf(ConnectionPathInfo("Tor", "Configured", true)), true, true, 3, false)
        assertEquals("Waiting for peer delivery", state.headline)
    }

    @Test fun pausedQueueIsAnExplicitUserControlledState() {
        val state = ConnectionUxPresentation.create("Wi-Fi", true,
            listOf(ConnectionPathInfo("Tor", "Configured", true)), true, true, 3, false,
            sendingPaused = true, controls = 7)
        assertEquals("Sending paused", state.headline)
        assertTrue(state.sendingPaused)
        assertEquals(3, state.pendingDeliveries)
        assertEquals(7, state.pendingControlDeliveries)
    }

    @Test fun torRecoveryIsAvailableWithAnEmptyPausedQueue() {
        val state = ConnectionUxPresentation.create("Wi-Fi", true,
            listOf(ConnectionPathInfo("Tor", "Unavailable: restart Tor to retry")), false, false,
            pending = 0, nearbySearching = false, sendingPaused = true, torRetryAvailable = true)
        assertTrue(state.torRetryAvailable)
        assertTrue(state.sendingPaused)
        assertEquals(0, state.pendingDeliveries)
        assertEquals("Sending paused", state.headline)
    }
}
