package com.torxone.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.torxone.app.ui.theme.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.conversations.ConversationListViewModel
import com.torxone.app.conversations.ConversationUiModel
import java.text.SimpleDateFormat
import java.util.*

import com.torxone.app.data.entity.ConversationType

@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    onConversationClick: (String) -> Unit,
    onArchivedClick: () -> Unit,
    onScanQrClick: () -> Unit,
    onNewGroupClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    ConversationListScreen(
        conversations = uiState.conversations,
        archivedCount = uiState.archivedCount,
        searchQuery = uiState.searchQuery,
        isSearching = uiState.isSearching,
        onSearchQueryChange = viewModel::onSearchQueryChanged,
        onToggleSearch = viewModel::toggleSearch,
        onConversationClick = onConversationClick,
        onArchivedClick = onArchivedClick,
        onScanQrClick = onScanQrClick,
        onNewGroupClick = onNewGroupClick,
        onSettingsClick = onSettingsClick,
        onPinClick = viewModel::togglePin,
        onArchiveClick = viewModel::toggleArchive,
        onMuteClick = { conv, duration -> viewModel.setMuteDuration(conv.conversationId, duration) },
        onUnmuteClick = { conv -> viewModel.unmute(conv.conversationId) },
        onMarkReadClick = { conv -> viewModel.markAsRead(conv.conversationId) },
        onMarkUnreadClick = { conv -> viewModel.markAsUnread(conv.conversationId) },
        onDeleteChatClick = { conv -> viewModel.confirmDeleteChat(conv.conversationId) },
        modifier = modifier
    )
}

/**
 * Conversation list screen with Search, Pinning, Archiving, Muting, and Action Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    conversations: List<ConversationUiModel>,
    archivedCount: Int = 0,
    searchQuery: String = "",
    isSearching: Boolean = false,
    onSearchQueryChange: (String) -> Unit = {},
    onToggleSearch: (Boolean) -> Unit = {},
    onConversationClick: (String) -> Unit,
    onArchivedClick: () -> Unit = {},
    onScanQrClick: () -> Unit,
    onNewGroupClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onPinClick: (ConversationUiModel) -> Unit = {},
    onArchiveClick: (ConversationUiModel) -> Unit = {},
    onMuteClick: (ConversationUiModel, Long?) -> Unit = { _, _ -> },
    onUnmuteClick: (ConversationUiModel) -> Unit = {},
    onMarkReadClick: (ConversationUiModel) -> Unit = {},
    onMarkUnreadClick: (ConversationUiModel) -> Unit = {},
    onDeleteChatClick: (ConversationUiModel) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedForActionSheet by remember { mutableStateOf<ConversationUiModel?>(null) }
    var conversationPendingMute by remember { mutableStateOf<ConversationUiModel?>(null) }
    var conversationPendingDelete by remember { mutableStateOf<ConversationUiModel?>(null) }
    var showNewChatSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (isSearching) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { onToggleSearch(false) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit search")
                        }
                    },
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text("Search chats or messages...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "TorX One",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Private mesh messenger",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { onToggleSearch(true) }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = onScanQrClick) {
                            Icon(Icons.Default.QrCode, contentDescription = "Scan QR")
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChatSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "New chat")
            }
        },
        modifier = modifier
    ) { padding ->
        if (conversations.isEmpty() && archivedCount == 0 && searchQuery.isBlank()) {
            EmptyConversationsView(
                onScanQrClick = onScanQrClick,
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                // Archived Row Banner (WhatsApp-style)
                if (archivedCount > 0 && !isSearching) {
                    item(key = "archived_header") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onArchivedClick)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            color = Color.Transparent
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = "Archived",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = "Archived",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = archivedCount.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }

                // Conversations List
                items(conversations, key = { it.conversationId }) { conversation ->
                    ConversationItem(
                        conversation = conversation,
                        onClick = { onConversationClick(conversation.conversationId) },
                        onLongClick = { selectedForActionSheet = conversation }
                    )
                }
            }
        }
    }

    // Long-Press Action Sheet
    if (selectedForActionSheet != null) {
        val target = selectedForActionSheet!!
        ModalBottomSheet(
            onDismissRequest = { selectedForActionSheet = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = target.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

                // 1. Pin / Unpin
                ListItem(
                    headlineContent = { Text(if (target.isPinned) "Unpin chat" else "Pin chat") },
                    leadingContent = {
                        Text(text = "📌", fontSize = 20.sp)
                    },
                    modifier = Modifier.clickable {
                        onPinClick(target)
                        selectedForActionSheet = null
                    }
                )

                // 2. Mute / Unmute
                ListItem(
                    headlineContent = { Text(if (target.isMuted) "Unmute notifications" else "Mute notifications") },
                    leadingContent = {
                        Icon(
                            if (target.isMuted) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier.clickable {
                        if (target.isMuted) {
                            onUnmuteClick(target)
                            selectedForActionSheet = null
                        } else {
                            conversationPendingMute = target
                            selectedForActionSheet = null
                        }
                    }
                )

                // 3. Archive / Unarchive
                ListItem(
                    headlineContent = { Text(if (target.isArchived) "Unarchive chat" else "Archive chat") },
                    leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                    modifier = Modifier.clickable {
                        onArchiveClick(target)
                        selectedForActionSheet = null
                    }
                )

                // 4. Mark as read / unread
                val isUnread = target.unreadCount > 0 || target.manuallyUnread
                ListItem(
                    headlineContent = { Text(if (isUnread) "Mark as read" else "Mark as unread") },
                    leadingContent = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                    modifier = Modifier.clickable {
                        if (isUnread) {
                            onMarkReadClick(target)
                        } else {
                            onMarkUnreadClick(target)
                        }
                        selectedForActionSheet = null
                    }
                )

                // 5. Delete chat (Local only)
                ListItem(
                    headlineContent = { Text("Delete chat", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        conversationPendingDelete = target
                        selectedForActionSheet = null
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Mute Duration Dialog
    if (conversationPendingMute != null) {
        val target = conversationPendingMute!!
        AlertDialog(
            onDismissRequest = { conversationPendingMute = null },
            title = { Text("Mute notifications for...") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onMuteClick(target, 8 * 3600_000L)
                            conversationPendingMute = null
                        }
                    ) {
                        Text("8 hours", modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onMuteClick(target, 7 * 24 * 3600_000L)
                            conversationPendingMute = null
                        }
                    ) {
                        Text("1 week", modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onMuteClick(target, Long.MAX_VALUE)
                            conversationPendingMute = null
                        }
                    ) {
                        Text("Always", modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { conversationPendingMute = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // New Chat Options Bottom Sheet (Groups vs Direct Contact)
    if (showNewChatSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewChatSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Start chatting",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                ListItem(
                    headlineContent = { Text("New group") },
                    supportingContent = { Text("Create an end-to-end encrypted group chat") },
                    leadingContent = {
                        Icon(Icons.Default.GroupAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        showNewChatSheet = false
                        onNewGroupClick()
                    }
                )
                ListItem(
                    headlineContent = { Text("Scan QR / Add contact") },
                    supportingContent = { Text("Direct peer connection via mesh") },
                    leadingContent = {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        showNewChatSheet = false
                        onScanQrClick()
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Delete Chat Dialog (Local only)
    if (conversationPendingDelete != null) {
        val target = conversationPendingDelete!!
        val explanation = if (target.type == ConversationType.GROUP) {
            "Messages will be deleted from this device only. You will remain a member of the group until you leave it."
        } else {
            "Messages will be deleted from this device only. The contact and secure key exchange will remain saved."
        }
        AlertDialog(
            onDismissRequest = { conversationPendingDelete = null },
            title = { Text("Delete this chat?") },
            text = { Text(explanation) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteChatClick(target)
                        conversationPendingDelete = null
                    }
                ) {
                    Text("Delete chat", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { conversationPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: ConversationUiModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (conversation.type == ConversationType.GROUP)
                            MaterialTheme.colorScheme.secondaryContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (conversation.type == ConversationType.GROUP) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = "Group",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Text(
                        text = conversation.title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Title + Preview
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (conversation.type == ConversationType.GROUP) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = "Group",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (conversation.isPinned) {
                        Text(text = "📌", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Outgoing message tick icon (if applicable)
                    if (conversation.isLastMessageOutgoing && conversation.lastMessageStatus != null) {
                        DeliveryStatusMiniIcon(status = conversation.lastMessageStatus)
                    }

                    val previewText = conversation.preview ?: ""
                    val isDeleted = previewText == "This message was deleted"

                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontStyle = if (isDeleted) FontStyle.Italic else FontStyle.Normal
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Timestamp + Mute Icon + Unread Badge
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (conversation.timestamp != null) {
                    Text(
                        text = formatTime(conversation.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conversation.unreadCount > 0 || conversation.manuallyUnread)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (conversation.isMuted) {
                        Icon(
                            imageVector = Icons.Default.NotificationsOff,
                            contentDescription = "Muted",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    if (conversation.unreadCount > 0) {
                        Badge {
                            Text(
                                text = if (conversation.unreadCount > 99) "99+"
                                else conversation.unreadCount.toString()
                            )
                        }
                    } else if (conversation.manuallyUnread) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryStatusMiniIcon(status: DeliveryStatus) {
    val isRead = status == DeliveryStatus.READ
    val icon = when (status) {
        DeliveryStatus.CREATED, DeliveryStatus.ENCRYPTED, DeliveryStatus.QUEUED -> Icons.Default.Schedule
        DeliveryStatus.TRANSMITTING, DeliveryStatus.TRANSPORT_ACCEPTED -> Icons.Default.Done
        DeliveryStatus.DEVICE_RECEIVED, DeliveryStatus.DELIVERED, DeliveryStatus.READ -> Icons.Default.DoneAll
        else -> Icons.Default.Schedule
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(13.dp),
        tint = if (isRead) MaterialTheme.colorScheme.readReceipt else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyConversationsView(
    onScanQrClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No conversations yet",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Scan a contact's QR code to start chatting",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        FilledTonalButton(onClick = onScanQrClick) {
            Icon(Icons.Default.QrCode, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scan QR Code")
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Now"
        diff < 3_600_000 -> "${diff / 60_000}m"
        diff < 86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        diff < 604_800_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(timestamp))
    }
}
