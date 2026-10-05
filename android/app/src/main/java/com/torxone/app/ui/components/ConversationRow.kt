package com.torxone.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.conversations.ConversationUiModel
import com.torxone.app.data.entity.ConversationType
import com.torxone.app.ui.theme.TorXSpacing
import com.torxone.app.ui.theme.readReceipt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Continuous list content; optional actions allow archived rows to share the same hierarchy. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(
    conversation: ConversationUiModel,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailingActions: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 76.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Chat actions" else null)
            .padding(horizontal = TorXSpacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TorXSpacing.sm)
    ) {
        ProfileAvatar(conversation.title, conversation.avatarHash, Modifier.size(50.dp), previewOnClick = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (conversation.type == ConversationType.GROUP) Icon(Icons.Default.Groups, "Group", Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(conversation.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (conversation.isPinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val draft = conversation.draftText?.takeIf { it.isNotBlank() }
                if (draft != null) Text("Draft:", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                else if (conversation.isLastMessageOutgoing) conversation.lastMessageStatus?.let { ConversationDeliveryIcon(it) }
                val text = draft ?: conversation.preview.orEmpty()
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    fontStyle = if (draft == null && text == "This message was deleted") FontStyle.Italic else FontStyle.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            conversation.timestamp?.let { Text(conversationTime(it), style = MaterialTheme.typography.labelSmall,
                color = if (conversation.unreadCount > 0 || conversation.manuallyUnread) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (conversation.isMuted) Icon(Icons.Default.NotificationsOff, "Muted", Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (conversation.unreadCount > 0) Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { contentDescription = "${conversation.unreadCount} unread messages" }
                ) { Text(if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString()) }
                else if (conversation.manuallyUnread) Box(Modifier.size(10.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary).semantics { contentDescription = "Marked unread" })
            }
        }
        trailingActions?.invoke()
    }
}

@Composable
private fun ConversationDeliveryIcon(status: DeliveryStatus) {
    val presentation = DeliveryPresentation.from(status)
    val icon = when (presentation.glyph) {
        DeliveryGlyph.WAITING -> Icons.Default.Schedule
        DeliveryGlyph.SENT -> Icons.Default.Done
        DeliveryGlyph.RECEIVED -> Icons.Default.DoneAll
        DeliveryGlyph.ERROR -> Icons.Default.ErrorOutline
        DeliveryGlyph.EXPIRED -> Icons.Default.TimerOff
    }
    Icon(icon, presentation.label, Modifier.size(16.dp), tint = when {
        presentation.read -> MaterialTheme.colorScheme.readReceipt
        presentation.glyph == DeliveryGlyph.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    })
}

private fun conversationTime(timestamp: Long): String {
    val difference = (System.currentTimeMillis() - timestamp).coerceAtLeast(0)
    return when {
        difference < 60_000 -> "Now"
        difference < 3_600_000 -> "${difference / 60_000}m"
        difference < 86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        difference < 604_800_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(timestamp))
    }
}
