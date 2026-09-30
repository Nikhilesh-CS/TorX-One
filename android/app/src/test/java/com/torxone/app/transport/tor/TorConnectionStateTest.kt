package com.torxone.app.transport.tor

import org.junit.Assert.assertEquals
import org.junit.Test

class TorConnectionStateTest {
    private val onion = "a".repeat(56) + ".onion"

    @Test fun readyRequiresPublishedV3Onion() {
        assertEquals(onion, TorConnectionState.Ready(9050, onion).onionAddress)
    }

    @Test(expected = IllegalArgumentException::class)
    fun readyRejectsMissingSocksListener() {
        TorConnectionState.Ready(0, onion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun readyRejectsUnpublishedOnion() {
        TorConnectionState.Ready(9050, "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun readyRejectsLegacyOnion() {
        TorConnectionState.Ready(9050, "a".repeat(16) + ".onion")
    }

    @Test(expected = IllegalArgumentException::class)
    fun readyRejectsInvalidBase32Onion() {
        TorConnectionState.Ready(9050, "0".repeat(56) + ".onion")
    }
}
