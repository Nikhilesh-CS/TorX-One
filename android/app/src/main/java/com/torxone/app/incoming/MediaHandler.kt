package com.torxone.app.incoming

import android.util.Log
import com.torxone.app.connection.Connection
import com.torxone.app.media.MediaProtocolCodec
import com.torxone.app.media.MediaService
import com.torxone.app.notifications.TorXNotificationManager
import com.torxone.app.protocol.SecureEnvelope

/**
 * Incoming dispatcher handler for media descriptors and chunk transfers.
 */
class MediaHandler(
    private val mediaService: MediaService,
    private val notificationManager: TorXNotificationManager? = null
) {
    companion object {
        private const val TAG = "MediaHandler"
    }

    /**
     * Handles incoming media descriptors (IMAGE, VIDEO, AUDIO, FILE, VOICE_NOTE).
     * Creates MessageEntity, MediaEntity, and MediaTransferEntity, and raises notification.
     */
    suspend fun handleMediaDescriptor(
        connection: Connection,
        envelope: SecureEnvelope
    ): Boolean {
        Log.i(TAG, "[RX] Handling media descriptor msg=${envelope.logicalMessageId.take(8)}")
        val success = mediaService.handleIncomingDescriptor(connection, envelope)

        if (success) {
            val descriptor = try {
                MediaProtocolCodec.decodeDescriptor(envelope.payload)
            } catch (_: Exception) { null }

            val preview = when (descriptor?.type) {
                com.torxone.app.media.MediaType.IMAGE -> "sent a photo"
                com.torxone.app.media.MediaType.VIDEO -> "sent a video"
                com.torxone.app.media.MediaType.VOICE_NOTE -> "sent a voice message"
                com.torxone.app.media.MediaType.AUDIO -> "sent an audio file"
                com.torxone.app.media.MediaType.DOCUMENT -> "sent a document: ${descriptor.fileName}"
                null -> "sent an attachment"
            }

            notificationManager?.handleIncomingTextMessage(
                conversationId = envelope.conversationId,
                messageId = envelope.logicalMessageId,
                senderId = envelope.senderIdentity,
                text = preview,
                timestamp = envelope.timestamp
            )
        }

        return success
    }

    /**
     * Handles incoming media chunks (FILE_PROGRESS).
     */
    suspend fun handleMediaChunk(
        connection: Connection,
        envelope: SecureEnvelope
    ): Boolean {
        return mediaService.handleIncomingChunk(connection, envelope)
    }

    /**
     * Handles media transfer cancellation (FILE_CANCEL).
     */
    suspend fun handleMediaCancel(envelope: SecureEnvelope): Boolean {
        val cancel = try {
            MediaProtocolCodec.decodeCancel(envelope.payload)
        } catch (_: Exception) { return false }
        mediaService.cancelTransfer(cancel.mediaId)
        return true
    }
}
