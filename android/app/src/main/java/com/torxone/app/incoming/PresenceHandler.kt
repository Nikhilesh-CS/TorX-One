package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.chat.PresenceService
import com.torxone.app.connection.Connection
import com.torxone.app.protocol.PresenceUpdate
import com.torxone.app.protocol.SecureEnvelope

/**
 * Handles incoming PRESENCE_UPDATE protocol events.
 */
class PresenceHandler(
    private val presenceService: PresenceService
) {
    companion object {
        private const val TAG = "PresenceHandler"
    }

    fun handlePresenceUpdate(connection: Connection, envelope: SecureEnvelope) {
        try {
            val update = PresenceUpdate.fromByteArray(envelope.payload)
            Log.d(TAG, "[PRESENCE] Authenticated update received")
            presenceService.onPresenceUpdateReceived(connection.relationshipId, update)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse presence update")
        }
    }
}
