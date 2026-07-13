package com.torxone.app.debug

import android.content.Context
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import java.io.File

object CrashRecoveryEngine {
    
    suspend fun triggerProcessDeath(delayMs: Long) {
        delay(delayMs)
        exitProcess(0)
    }

    fun triggerOOM() {
        val list = mutableListOf<ByteArray>()
        while (true) {
            list.add(ByteArray(10 * 1024 * 1024)) // allocate 10MB indefinitely
        }
    }

    fun simulateDatabaseCorruption(context: Context) {
        val dbPath = context.getDatabasePath("astra_mesh.db")
        if (dbPath.exists()) {
            dbPath.writeText("corrupted_data_injection")
        }
    }
}
