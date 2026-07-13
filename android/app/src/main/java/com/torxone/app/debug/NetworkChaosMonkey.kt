package com.torxone.app.debug

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

object NetworkChaosMonkey {
    val dropConnections = MutableStateFlow(false)
    val highLatencyMode = MutableStateFlow(false)
    val packetLossRate = MutableStateFlow(0.0f)

    suspend fun simulateNetworkDelay() {
        if (highLatencyMode.value) {
            delay((2000L..5000L).random())
        }
    }

    fun shouldDropPacket(): Boolean {
        if (dropConnections.value) return true
        return Math.random() < packetLossRate.value
    }
}
