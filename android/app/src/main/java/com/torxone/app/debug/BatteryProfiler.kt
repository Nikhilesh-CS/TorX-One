package com.torxone.app.debug

import android.util.Log

object BatteryProfiler {
    private const val TAG = "BatteryProfiler"
    private var estimatedDrainMAh = 0.0

    fun logRadioWakeup(transport: String) {
        val drain = when (transport) {
            "BLUETOOTH" -> 0.05
            "WIFI_DIRECT" -> 0.15
            "TOR" -> 0.20
            else -> 0.01
        }
        estimatedDrainMAh += drain
        Log.d(TAG, "Radio Wakeup [$transport]. Estimated total drain: $estimatedDrainMAh mAh")
    }

    fun logTorCircuitRebuild() {
        estimatedDrainMAh += 0.50 // Tor crypto is heavy
        Log.d(TAG, "Tor Circuit Rebuild. Estimated total drain: $estimatedDrainMAh mAh")
    }
    
    fun getEstimatedDrain(): Double = estimatedDrainMAh
}
