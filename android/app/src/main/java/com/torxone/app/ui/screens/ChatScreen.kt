package com.torxone.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.chat.*
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
    onHeaderClick: () -> Unit = {},
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
        editingMessage = uiState.editingMessage,
        onComposerTextChange = viewModel::onComposerTextChanged,
        onSendMessage = viewModel::sendText,
        onReply = viewModel::onReply,
        onCancelReply = viewModel::cancelReply,
        onStartEdit = viewModel::startEditing,
        onCancelEdit = viewModel::cancelEditing,
        onToggleReaction = viewModel::toggleReaction,
        onDeleteForMe = viewModel::deleteForMe,
        onDeleteForEveryone = viewModel::deleteForEveryone,
        onHeaderClick = onHeaderClick,
        onBackClick = onBackClick,
        modifier = modifier
    )
}

/**
 * ChatScreen — The 1:1 conversation view.
 *
 * Highlights:
 * - Header priority: typing… > online > last seen today/yesterday > offline
 * - Message actions on long press: Reaction quick bar, Reply, Copy, Edit, Delete
 * - WhatsApp-style emoji reaction pills below message bubbles
 * - Tombstone for deleted messages ("This message was deleted")
 * - "(edited)" suffix indicator on edited messages
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
    editingMessage: MessageUiModel? = null,
    onComposerTextChange: (String) -> Unit = {},
    onSendMessage: () -> Unit = {},
    onReply: (MessageUiModel) -> Unit = {},
    onCancelReply: () -> Unit = {},
    onStartEdit: (MessageUiModel) -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onToggleReaction: (String, String) -> Unit = { _, _ -> },
    onDeleteForMe: (String) -> Unit = {},
    onDeleteForEveryone: (String) -> Unit = {},
    onHeaderClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var selectedMessageForMenu by remember { mutableStateOf<MessageUiModel?>(null) }
    var messagePendingDelete by remember { mutableStateOf<MessageUiModel?>(null) }
    val clipboardManager = LocalClipboardManager.current

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
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onHeaderClick() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
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
                        },
                        onToggleReaction = { emoji ->
                            onToggleReaction(message.logicalMessageId, emoji)
                        }
                    )
                }
            }

            // Composer with Reply / Edit preview banner
            MessageComposer(
                text = composerText,
                replyingTo = replyingTo,
                editingMessage = editingMessage,
                contactName = contactName,
                onTextChange = onComposerTextChange,
                onCancelReply = onCancelReply,
                onCancelEdit = onCancelEdit,
                onSend = onSendMessage
            )
        }
    }

    // Message Action Sheet on Long-Press
    if (selectedMessageForMenu != null) {
        val selected = selectedMessageForMenu!!
        ModalBottomSheet(
            onDismissRequest = { selectedMessageForMenu = null },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Emoji Reaction Bar (👍 ❤️ 😂 😮 😢 🙏)
                if (!selected.isDeleted) {
                    val quickEmojis = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(32.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (emoji in quickEmojis) {
                            val userReacted = selected.reactions.any { it.emoji == emoji && it.userReacted }
                            Surface(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        onToggleReaction(selected.logicalMessageId, emoji)
                                        selectedMessageForMenu = null
                                    },
                                color = if (userReacted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            ) {
                                Text(
                                    text = emoji,
                                    fontSize = 24.sp,
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }

                // Action List
                // 1. Reply
                if (!selected.isDeleted) {
                    ListItem(
                        headlineContent = { Text("Reply") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply") },
                        modifier = Modifier.clickable {
                            onReply(selected)
                            selectedMessageForMenu = null
                        }
                    )
                }

                // 2. Copy
                if (!selected.isDeleted && !selected.body.isNullOrBlank()) {
                    ListItem(
                        headlineContent = { Text("Copy") },
                        leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") },
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(selected.body))
                            selectedMessageForMenu = null
                        }
                    )
                }

                // 3. Edit (own outgoing messages only)
                if (selected.direction == MessageDirection.OUTGOING && !selected.isDeleted) {
                    ListItem(
                        headlineContent = { Text("Edit") },
                        leadingContent = { Icon(Icons.Default.Edit, contentDescription = "Edit") },
                        modifier = Modifier.clickable {
                            onStartEdit(selected)
                            selectedMessageForMenu = null
                        }
                    )
                }

                // 4. Delete
                ListItem(
                    headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        messagePendingDelete = selected
                        selectedMessageForMenu = null
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Delete Confirmation Dialog with Delete for Me / Delete for Everyone / Cancel
    if (messagePendingDelete != null) {
        val target = messagePendingDelete!!
        val canDeleteForEveryone = target.direction == MessageDirection.OUTGOING && !target.isDeleted

        AlertDialog(
            onDismissRequest = { messagePendingDelete = null },
            title = { Text("Delete message?") },
            text = {
                Text(
                    if (canDeleteForEveryone)
                        "You can delete this message just for yourself, or for everyone in this chat."
                    else
                        "Delete this message for yourself? Other participants will still be able to see it."
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (canDeleteForEveryone) {
                        TextButton(
                            onClick = {
                                onDeleteForEveryone(target.logicalMessageId)
                                messagePendingDelete = null
                            }
                        ) {
                            Text("Delete for everyone", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = {
                            onDeleteForMe(target.logicalMessageId)
                            messagePendingDelete = null
                        }
                    ) {
                        Text("Delete for me", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = { messagePendingDelete = null }
                    ) {
                        Text("Cancel")
                    }
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
    onQuoteClick: (String) -> Unit,
    onToggleReaction: (String) -> Unit
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
                    if (dragOffsetX > 80f && !message.isDeleted) {
                        onReply()
                    }
                    dragOffsetX = 0f
                }
            )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
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
                    if (message.quotedMessage != null && !message.isDeleted) {
                        QuotedBubbleView(
                            quoted = message.quotedMessage,
                            isOutgoingBubble = isOutgoing,
                            onClick = { onQuoteClick(message.quotedMessage.messageId) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Message body or Tombstone
                    if (message.isDeleted) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Block,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isOutgoing)
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "This message was deleted",
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = if (isOutgoing)
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        Text(
                            text = message.body ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isOutgoing)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Timestamp, (edited) suffix, and delivery tick icons
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.isEdited && !message.isDeleted) {
                            Text(
                                text = "(edited)",
                                style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                                color = if (isOutgoing)
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        Text(
                            text = formatMessageTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOutgoing)
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )

                        if (isOutgoing && !message.isDeleted) {
                            DeliveryStatusIcon(
                                status = message.status,
                                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Reaction Pills below the bubble
            if (message.reactions.isNotEmpty() && !message.isDeleted) {
                Row(
                    modifier = Modifier
                        .padding(top = 2.dp, start = if (isOutgoing) 0.dp else 6.dp, end = if (isOutgoing) 6.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (rx in message.reactions) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (rx.userReacted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 1.dp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                onToggleReaction(rx.emoji)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(text = rx.emoji, fontSize = 12.sp)
                                if (rx.count > 1) {
                                    Text(
                                        text = rx.count.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (rx.userReacted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
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
                        RoundedCornerShape(2.dp)
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = quoted.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isOutgoingBubble)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.primary
                )
                Text(
                    text = quoted.previewText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOutgoingBubble)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Bottom composer with integrated Reply and Edit preview banners.
 */
@Composable
private fun MessageComposer(
    text: String,
    replyingTo: MessageUiModel?,
    editingMessage: MessageUiModel?,
    contactName: String,
    onTextChange: (String) -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
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

            // Edit Preview Banner
            if (editingMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Editing",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Edit message",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = editingMessage.body ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        IconButton(onClick = onCancelEdit) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel edit", modifier = Modifier.size(18.dp))
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
                    placeholder = {
                        Text(if (editingMessage != null) "Edit message..." else "Message...")
                    },
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
                        if (editingMessage != null) Icons.Default.Check else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (editingMessage != null) "Confirm edit" else "Send"
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
