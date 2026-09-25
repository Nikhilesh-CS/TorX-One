package com.torxone.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.chat.ChatUiState
import com.torxone.app.chat.ChatViewModel
import com.torxone.app.chat.MessageUiModel
import com.torxone.app.chat.PresenceFormatter
import com.torxone.app.chat.PresenceStatus
import com.torxone.app.data.entity.MessageDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * ChatScreen with ChatViewModel state binding.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    ChatScreen(
        contactName = uiState.title,
        messages = uiState.messages,
        composerText = uiState.composerText,
        presence = uiState.presence,
        lastSeenAt = uiState.lastSeenAt,
        isTyping = uiState.isTyping,
        replyingTo = uiState.replyingTo,
        onComposerTextChange = viewModel::onComposerTextChanged,
        onSendMessage = viewModel::sendText,
        onReply = viewModel::onReply,
        onCancelReply = viewModel::cancelReply,
        onBackClick = onBackClick,
        modifier = modifier
    )
}

/**
 * ChatScreen — The 1:1 conversation view.
 *
 * Highlights:
 * - Header priority: typing… > online > last seen today/yesterday > offline
 * - Swipe-to-reply on message bubbles with animated offset
 * - Quoted reply preview in composer with dismiss button
 * - In-bubble quoted reply card with click-to-scroll & temporary highlight
 * - Status tick icons (Clock, 1 Tick, 2 Ticks, Blue/Color 2 Ticks)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactName: String,
    messages: List<MessageUiModel>,
    composerText: String,
    presence: PresenceStatus = PresenceStatus.UNKNOWN,
    lastSeenAt: Long? = null,
    isTyping: Boolean = false,
    replyingTo: MessageUiModel? = null,
    onComposerTextChange: (String) -> Unit = {},
    onSendMessage: () -> Unit = {},
    onReply: (MessageUiModel) -> Unit = {},
    onCancelReply: () -> Unit = {},
    onBackClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var selectedMessageForMenu by remember { mutableStateOf<MessageUiModel?>(null) }

    // Auto-scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Header presence priority
    val headerSubtitle = when {
        isTyping -> "typing…"
        presence == PresenceStatus.ONLINE -> "online"
        lastSeenAt != null && lastSeenAt > 0L -> PresenceFormatter.formatLastSeen(lastSeenAt)
        else -> "End-to-end encrypted"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = contactName.take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Column {
                            Text(
                                text = contactName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = headerSubtitle,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isTyping) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (isTyping) FontStyle.Italic else FontStyle.Normal,
                                color = if (isTyping || presence == PresenceStatus.ONLINE)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Message Timeline
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(messages, key = { _, it -> it.logicalMessageId }) { _, message ->
                    MessageBubble(
                        message = message,
                        contactName = contactName,
                        isHighlighted = message.logicalMessageId == highlightedMessageId,
                        onReply = { onReply(message) },
                        onLongClick = { selectedMessageForMenu = message },
                        onQuoteClick = { targetMsgId ->
                            val targetIndex = messages.indexOfFirst { it.logicalMessageId == targetMsgId }
                            if (targetIndex >= 0) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(targetIndex)
                                    highlightedMessageId = targetMsgId
                                    delay(1200L)
                                    if (highlightedMessageId == targetMsgId) {
                                        highlightedMessageId = null
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Composer with Reply preview banner
            MessageComposer(
                text = composerText,
                replyingTo = replyingTo,
                contactName = contactName,
                onTextChange = onComposerTextChange,
                onCancelReply = onCancelReply,
                onSend = onSendMessage
            )
        }
    }

    // Long press action menu
    if (selectedMessageForMenu != null) {
        val selected = selectedMessageForMenu!!
        AlertDialog(
            onDismissRequest = { selectedMessageForMenu = null },
            title = { Text("Message Options") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = selected.body ?: "",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReply(selected)
                        selectedMessageForMenu = null
                    }
                ) {
                    Text("Reply")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedMessageForMenu = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageUiModel,
    contactName: String,
    isHighlighted: Boolean,
    onReply: () -> Unit,
    onLongClick: () -> Unit,
    onQuoteClick: (String) -> Unit
) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    var dragOffsetX by remember { mutableFloatStateOf(0f) }

    val baseBubbleColor = if (isOutgoing)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant

    val targetColor = if (isHighlighted)
        MaterialTheme.colorScheme.tertiaryContainer
    else
        baseBubbleColor

    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 300),
        label = "highlightAnimation"
    )

    // Swipe-to-reply gesture
    val draggableState = rememberDraggableState { delta ->
        val newOffset = (dragOffsetX + delta).coerceIn(0f, 150f)
        dragOffsetX = newOffset
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(dragOffsetX.roundToInt(), 0) }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    if (dragOffsetX > 80f) {
                        onReply()
                    }
                    dragOffsetX = 0f
                }
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongClick
                    ),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isOutgoing) 16.dp else 4.dp,
                    bottomEnd = if (isOutgoing) 4.dp else 16.dp
                ),
                color = animatedColor,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Quoted message bubble snippet (if replying)
                    if (message.quotedMessage != null) {
                        QuotedBubbleView(
                            quoted = message.quotedMessage,
                            isOutgoingBubble = isOutgoing,
                            onClick = { onQuoteClick(message.quotedMessage.messageId) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Message body
                    Text(
                        text = message.body ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isOutgoing)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Timestamp and delivery tick icons
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatMessageTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOutgoing)
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        if (isOutgoing) {
                            DeliveryStatusIcon(
                                status = message.status,
                                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Quoted preview block inside message bubble.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuotedBubbleView(
    quoted: com.torxone.app.chat.QuotedMessageUiModel,
    isOutgoingBubble: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick),
        color = if (isOutgoingBubble)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else
            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Row(modifier = Modifier.padding(6.dp)) {
            // Left vertical accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .background(
                        if (isOutgoingBubble) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = quoted.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isOutgoingBubble) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = quoted.previewText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontStyle = if (quoted.isUnavailable) FontStyle.Italic else FontStyle.Normal,
                    color = if (isOutgoingBubble)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * Message composer with integrated reply preview banner.
 */
@Composable
private fun MessageComposer(
    text: String,
    replyingTo: MessageUiModel?,
    contactName: String,
    onTextChange: (String) -> Unit,
    onCancelReply: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 4.dp
    ) {
        Column {
            // Reply Preview Banner
            if (replyingTo != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(36.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to ${if (replyingTo.direction == MessageDirection.OUTGOING) "You" else contactName}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = replyingTo.body ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onCancelReply) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel reply", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Input Bar
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message...") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                FilledIconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send"
                    )
                }
            }
        }
    }
}

@Composable
private fun DeliveryStatusIcon(
    status: DeliveryStatus,
    tint: Color
) {
    val isRead = status == DeliveryStatus.READ
    val icon = when (status) {
        DeliveryStatus.CREATED, DeliveryStatus.ENCRYPTED, DeliveryStatus.QUEUED -> Icons.Default.Schedule
        DeliveryStatus.TRANSMITTING, DeliveryStatus.TRANSPORT_ACCEPTED -> Icons.Default.Done
        DeliveryStatus.DEVICE_RECEIVED, DeliveryStatus.DELIVERED -> Icons.Default.DoneAll
        DeliveryStatus.READ -> Icons.Default.DoneAll
        DeliveryStatus.FAILED -> Icons.Default.ErrorOutline
        else -> Icons.Default.Schedule
    }

    Icon(
        imageVector = icon,
        contentDescription = status.name,
        modifier = Modifier.size(14.dp),
        tint = if (isRead)
            Color(0xFF34B7F1) // WhatsApp-style signature blue double check
        else if (status == DeliveryStatus.FAILED)
            MaterialTheme.colorScheme.error
        else
            tint
    )
}

private fun formatMessageTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
