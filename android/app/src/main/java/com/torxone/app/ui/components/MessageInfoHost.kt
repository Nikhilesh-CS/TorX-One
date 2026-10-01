package com.torxone.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.MessageEntity
import com.torxone.app.data.entity.OutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Primitive fingerprints prevent legacy entity equality from suppressing updated receipts. */
data class MessageInfoLiveState(
    val message: MessageEntity? = null,
    val pending: List<OutboxEntity> = emptyList(),
    val fingerprint: List<String> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
) {
    companion object {
        fun ready(message: MessageEntity, pending: List<OutboxEntity>): MessageInfoLiveState {
            val own = pending.filter { it.logicalMessageId == message.logicalMessageId }
            val fingerprint = listOf(message.logicalMessageId, message.body.orEmpty(), message.type,
                message.direction.name, message.status, message.createdAt.toString(), message.receivedAt.toString(),
                message.deliveredAt.toString(), message.readAt.toString(), message.deletedAt.toString(),
                message.editedAt.toString(), message.editVersion.toString()) +
                own.sortedBy { it.deliveryId }.map { delivery ->
                    listOf(delivery.deliveryId, delivery.status, delivery.createdAt, delivery.updatedAt,
                        delivery.attemptCount, delivery.nextAttemptAt, delivery.ciphertext.isNotEmpty()).joinToString("|")
                }
            return MessageInfoLiveState(message, own, fingerprint, loading = false)
        }
    }
}

@Composable
fun MessageInfoHost(
    db: TorXDatabase,
    messageId: String,
    conversationId: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val state by produceState(MessageInfoLiveState(), db, messageId, conversationId) {
        while (currentCoroutineContext().isActive) {
            value = try {
                withContext(Dispatchers.IO) {
                    val message = db.messageDao().getById(messageId)
                    when {
                        message == null || message.conversationId != conversationId ->
                            MessageInfoLiveState(loading = false, error = "Message is no longer available in this chat.")
                        else -> MessageInfoLiveState.ready(message, db.outboxDao().getPending())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { MessageInfoLiveState(loading = false, error = "Could not read message info. Please reopen it to try again.") }
            delay(2_000)
        }
    }
    val message = state.message
    if (message != null && state.error == null) {
        MessageInfoDialog(message, state.pending, onDismiss, onRetry)
    } else {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Message info") },
            text = { Text(if (state.loading) "Loading recorded message state…" else state.error ?: "Message unavailable") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    }
}
