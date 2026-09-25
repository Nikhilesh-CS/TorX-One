package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.chat.PresenceService
import com.torxone.app.connection.Connection
import com.torxone.app.protocol.MessageType
import com.torxone.app.protocol.SecureEnvelope

/**
 * Handles incoming TYPING_START and TYPING_STOP protocol events.
 */
class TypingHandler(
    private val presenceService: PresenceService
) {
    companion object {
        private const val TAG = "TypingHandler"
    }

    fun handleTypingEvent(connection: Connection, envelope: SecureEnvelope) {
        when (envelope.messageType) {
            MessageType.TYPING_START -> {
                Log.d(TAG, "[TYPING] Received TYPING_START from ${connection.relationshipId.take(8)}")
                presenceService.onTypingStartReceived(connection.relationshipId)
            }
            MessageType.TYPING_STOP -> {
                Log.d(TAG, "[TYPING] Received TYPING_STOP from ${connection.relationshipId.take(8)}")
                presenceService.onTypingStopReceived(connection.relationshipId)
            }
            else -> {}
        }
    }
}
