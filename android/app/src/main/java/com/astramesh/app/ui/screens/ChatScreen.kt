package com.astramesh.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.engine.MessagePayload
import com.astramesh.app.engine.TransportType
import com.astramesh.app.network.MessageRouter
import com.astramesh.app.network.NearbyConnectionManager
import com.astramesh.app.transfer.MediaTransferManager
import com.astramesh.app.ui.components.*
import com.astramesh.app.ui.screens.chat.ChatViewModel
import com.astramesh.app.ui.screens.chat.SmartScrollEngine
import com.astramesh.app.ui.theme.AstraTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactKey: String,
    navController: NavController,
    db: AppDatabase,
    nearbyManager: NearbyConnectionManager,
    messageRouter: MessageRouter,
    mediaTransferManager: MediaTransferManager
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Instantiate ViewModel at Compose level for simplicity. Normally we'd use ViewModelProvider.
    val viewModel = remember(contactKey) { ChatViewModel(contactKey, db, messageRouter) }
    
    val contactName by viewModel.contactName.collectAsState()
    val contactEndpoint by viewModel.contactEndpoint.collectAsState()
    val contactOnion by viewModel.contactOnion.collectAsState()
    
    val connectedEndpoints by nearbyManager.connectedEndpoints.collectAsState()
    val isNearbyOnline = connectedEndpoints.contains(contactEndpoint)
    val isOnline = isNearbyOnline || contactOnion.isNotBlank()
    
    val messages by viewModel.conversationEngine.messages.collectAsState()
    
    val listState = rememberLazyListState()
    val smartScrollEngine = remember(listState) { SmartScrollEngine(listState, coroutineScope) }
    
    var messageText by remember { mutableStateOf("") }
    var replyToMessage by remember { mutableStateOf<MessagePayload?>(null) }
    var selectedMessages by remember { mutableStateOf(setOf<String>()) }
    val inSelectionMode = selectedMessages.isNotEmpty()
    
    var inSearchMode by remember { mutableStateOf(false) }
    val searchQuery by viewModel.searchEngine.searchQuery.collectAsState()
    val searchResults by viewModel.searchEngine.searchResults.collectAsState()
    val currentResultIndex by viewModel.searchEngine.currentResultIndex.collectAsState()
    
    // Jump to search result
    LaunchedEffect(currentResultIndex) {
        if (currentResultIndex >= 0 && searchResults.isNotEmpty()) {
            val targetId = searchResults[currentResultIndex]
            val indexInList = messages.asReversed().indexOfFirst { it.id == targetId }
            if (indexInList != -1) {
                listState.animateScrollToItem(indexInList)
            }
        }
    }
    
    // Scroll handling when new messages arrive
    LaunchedEffect(messages.size) {
        val lastMessage = messages.lastOrNull()
        if (lastMessage != null && !inSearchMode) {
            smartScrollEngine.onNewMessageArrived(lastMessage.senderId == "me")
        }
    }

    Scaffold(
        containerColor = AstraTheme.colors.background,
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = AstraTheme.colors.surface, shadowElevation = AstraTheme.spacing.tiny) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AstraTheme.spacing.small, vertical = AstraTheme.spacing.medium),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { 
                        if (inSearchMode) {
                            inSearchMode = false
                            viewModel.searchEngine.clearSearch()
                        } else {
                            navController.popBackStack() 
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = AstraTheme.colors.onSurface)
                    }
            androidx.compose.animation.Crossfade(targetState = if (inSelectionMode) 2 else if (inSearchMode) 1 else 0) { mode ->
                when (mode) {
                    2 -> {
                        com.astramesh.app.ui.adaptive.AstraTopAppBar(
                            title = { Text("${selectedMessages.size}") },
                            onNavigationIconClick = { selectedMessages = emptySet() },
                            navigationIcon = {
                                Icon(Icons.Rounded.Close, "Clear Selection")
                            },
                            actions = {
                                IconButton(onClick = { 
                                    val textToCopy = selectedMessages.mapNotNull { id -> messages.find { it.id == id }?.text }.joinToString("\n")
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("AstraMesh Messages", textToCopy))
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                    selectedMessages = emptySet()
                                }) {
                                    Icon(Icons.Rounded.ContentCopy, "Copy")
                                }
                                if (selectedMessages.size == 1) {
                                    IconButton(onClick = { 
                                        showMessageInfoFor = messages.find { it.id == selectedMessages.first() }
                                    }) {
                                        Icon(androidx.compose.material.icons.Icons.Rounded.Info, "Info")
                                    }
                                    IconButton(onClick = { 
                                        replyToMessage = messages.find { it.id == selectedMessages.first() }
                                        selectedMessages = emptySet()
                                    }) {
                                        Icon(Icons.AutoMirrored.Rounded.Reply, "Reply")
                                    }
                                }
                                IconButton(onClick = { 
                                    selectedMessages.forEach { id ->
                                        viewModel.conversationEngine.updateMessageState(id, com.astramesh.app.engine.MessageLifecycleState.CANCELLED)
                                        // Note: Actually delete from DB could go here if desired.
                                    }
                                    selectedMessages = emptySet()
                                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Rounded.Delete, "Delete")
                                }
                            }
                        )
                    }
                    1 -> {
                        // Search Mode
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.searchEngine.updateQuery(it) },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Search... (has:image)") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                )
                            )
                            if (searchResults.isNotEmpty()) {
                                Text("${currentResultIndex + 1}/${searchResults.size}", style = AstraTheme.typography.labelSmall)
                                IconButton(onClick = { viewModel.searchEngine.previousResult() }) {
                                    Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp, "Previous")
                                }
                                IconButton(onClick = { viewModel.searchEngine.nextResult() }) {
                                    Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, "Next")
                                }
                            }
                        }
                    }
                    0 -> {
                        com.astramesh.app.ui.adaptive.AstraTopAppBar(
                            title = { Text(contactName) },
                            onNavigationIconClick = { navController.popBackStack() },
                            actions = {
                                IconButton(onClick = { inSearchMode = true }) {
                                    Icon(androidx.compose.material.icons.Icons.Rounded.Search, "Search")
                                }
                                ConnectionStatusPill(
                                    transportType = when {
                                        isNearbyOnline -> com.astramesh.app.ui.components.TransportType.BLUETOOTH
                                        contactOnion.isNotBlank() -> com.astramesh.app.ui.components.TransportType.TOR
                                        else -> com.astramesh.app.ui.components.TransportType.OFFLINE
                                    },
                                    details = if (isNearbyOnline) contactEndpoint else if (contactOnion.isNotBlank()) "Connected" else "",
                                    modifier = Modifier.clickable { showConnectionVisualizer = true }
                                )
                            }
                        )
                    }
                }
            }
            } // Close Row
            } // Close Surface
        },
        bottomBar = {
            Surface(color = AstraTheme.colors.background, shadowElevation = AstraTheme.spacing.standard) {
                Column {
                    if (replyToMessage != null) {
                        ReplyPreviewBanner(
                            message = replyToMessage!!,
                            onCancel = { replyToMessage = null }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AstraTheme.spacing.medium, vertical = 10.dp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        IconButton(
                            onClick = {  },
                            modifier = Modifier.padding(bottom = AstraTheme.spacing.tiny)
                        ) {
                            Icon(Icons.Rounded.AttachFile, "Attach", tint = AstraTheme.colors.onSurfaceVariant)
                        }
                        
                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message...", color = AstraTheme.colors.onSurfaceVariant) },
                            shape = RoundedCornerShape(AstraTheme.spacing.extraLarge),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AstraTheme.colors.primary,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = AstraTheme.colors.onSurface,
                                unfocusedTextColor = AstraTheme.colors.onSurface,
                                cursorColor = AstraTheme.colors.primary,
                                focusedContainerColor = AstraTheme.colors.surface,
                                unfocusedContainerColor = AstraTheme.colors.surface
                            ),
                            maxLines = 4
                        )
                        Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
                        
                        val isSendEnabled = messageText.isNotBlank()
                        val sendScale by animateFloatAsState(targetValue = if (isSendEnabled) 1f else 0.8f, label = "sendBtn")
                        
                        IconButton(
                            onClick = {
                                if (!isSendEnabled) return@IconButton
                                viewModel.sendMessage(messageText, replyToMessage?.id)
                                messageText = ""
                                replyToMessage = null
                            },
                            enabled = isSendEnabled,
                            modifier = Modifier
                                .padding(bottom = AstraTheme.spacing.tiny)
                                .size(AstraTheme.spacing.massive3)
                                .scale(sendScale)
                                .clip(CircleShape)
                                .background(
                                    if (isSendEnabled) AstraTheme.colors.primary else AstraTheme.colors.surfaceVariant
                                )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = AstraTheme.colors.onPrimary, modifier = Modifier.size(AstraTheme.spacing.large))
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (messages.isEmpty()) {
                AstraEmptyState(
                    title = "No messages yet",
                    message = "Say hello to $contactName!"
                )
            } else {
                val reversedMessages = remember(messages) { messages.asReversed() }
                val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
                var highlightedMessageId by remember { mutableStateOf<String?>(null) }
                
                LaunchedEffect(highlightedMessageId) {
                    if (highlightedMessageId != null) {
                        kotlinx.coroutines.delay(1000)
                        highlightedMessageId = null
                    }
                }

                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = AstraTheme.spacing.standard, vertical = AstraTheme.spacing.medium)
                ) {
                    items(reversedMessages.size) { index ->
                        val message = reversedMessages[index]
                        val nextMessage = reversedMessages.getOrNull(index + 1)
                        val prevMessage = reversedMessages.getOrNull(index - 1)
                        
                        val dateStr = dateFormat.format(Date(message.timestamp))
                        val nextDateStr = nextMessage?.let { dateFormat.format(Date(it.timestamp)) }
                        
                        // Because reverseLayout = true, prevMessage is visually BELOW (newer in time)
                        // nextMessage is visually ABOVE (older in time)
                        // A tail should be shown if the message below is from a DIFFERENT sender, or there is no message below.
                        val showTail = prevMessage == null || prevMessage.senderId != message.senderId
                        
                        val isSelected = selectedMessages.contains(message.id)
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) AstraTheme.colors.primary.copy(alpha = 0.2f) else Color.Transparent)
                        ) {
                            MessageBubbleProxy(
                                message = message,
                                showTail = showTail,
                                isSelected = isSelected,
                                isHighlighted = highlightedMessageId == message.id,
                                onClick = {
                                    if (inSelectionMode) {
                                        if (isSelected) selectedMessages -= message.id else selectedMessages += message.id
                                    }
                                },
                                onLongClick = {
                                    if (!inSelectionMode) {
                                        selectedMessages = setOf(message.id)
                                    } else {
                                        if (isSelected) selectedMessages -= message.id else selectedMessages += message.id
                                    }
                                },
                                onSwipeReply = { replyToMessage = message },
                                onReplyClick = { replyId -> 
                                    val targetIdx = reversedMessages.indexOfFirst { it.id == replyId }
                                    if (targetIdx != -1) {
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(targetIdx)
                                            highlightedMessageId = replyId
                                        }
                                    }
                                }
                            )
                        }
                        
                        if (dateStr != nextDateStr) {
                            DateSeparator(dateStr)
                        }
                    }
                }

                // Jump to Bottom FAB
                androidx.compose.animation.AnimatedVisibility(
                    visible = listState.firstVisibleItemIndex > 5,
                    enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(AstraTheme.spacing.medium)
                ) {
                    FloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(0)
                            }
                        },
                        containerColor = AstraTheme.colors.surface,
                        contentColor = AstraTheme.colors.primary,
                        shape = CircleShape
                    ) {
                        Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, "Jump to bottom")
                    }
                }

                // Floating Date Indicator and Pagination
                val isScrollInProgress = listState.isScrollInProgress
                var showFloatingDate by remember { mutableStateOf(false) }

                LaunchedEffect(isScrollInProgress) {
                    if (isScrollInProgress) {
                        showFloatingDate = true
                    } else {
                        kotlinx.coroutines.delay(1500)
                        showFloatingDate = false
                    }
                }
                
                // Pagination trigger
                val shouldLoadMore = remember {
                    derivedStateOf {
                        val totalItems = listState.layoutInfo.totalItemsCount
                        val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        totalItems > 0 && lastVisibleItem >= totalItems - 10
                    }
                }
                
                LaunchedEffect(shouldLoadMore.value) {
                    if (shouldLoadMore.value) {
                        viewModel.loadMoreMessages()
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showFloatingDate && reversedMessages.isNotEmpty(),
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = AstraTheme.spacing.small)
                ) {
                    val firstVisibleIndex = listState.firstVisibleItemIndex
                    if (firstVisibleIndex in reversedMessages.indices) {
                        val msg = reversedMessages[firstVisibleIndex]
                        val dateStr = dateFormat.format(Date(msg.timestamp))
                        DateSeparator(dateStr)
                    }
                }
            }
        }
    }
    
    // Bottom Sheets
    var showMessageInfoFor by remember { mutableStateOf<MessagePayload?>(null) }
    if (showMessageInfoFor != null) {
        com.astramesh.app.ui.components.MessageInfoSheet(
            message = showMessageInfoFor!!,
            onDismiss = { showMessageInfoFor = null }
        )
    }
    
    var showConnectionVisualizer by remember { mutableStateOf(false) }
    if (showConnectionVisualizer) {
        com.astramesh.app.ui.components.ConnectionVisualizerSheet(
            transportType = when {
                isNearbyOnline -> com.astramesh.app.ui.components.TransportType.BLUETOOTH
                contactOnion.isNotBlank() -> com.astramesh.app.ui.components.TransportType.TOR
                else -> com.astramesh.app.ui.components.TransportType.OFFLINE
            },
            peerName = contactName,
            onDismiss = { showConnectionVisualizer = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubbleProxy(
    message: MessagePayload, 
    showTail: Boolean, 
    isSelected: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit, 
    onSwipeReply: () -> Unit,
    onReplyClick: (String) -> Unit
) {
    val isSent = message.senderId == "me"
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val accentColor = if (isSent) AstraTheme.colors.onPrimary else AstraTheme.colors.primary

    // Highlight flash animation
    val highlightAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isHighlighted) 0.5f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 300)
    )

    com.astramesh.app.ui.adaptive.AdaptiveChatBubble(
        isMine = isSent,
        timestamp = timeFormat.format(Date(message.timestamp)),
        lifecycleState = message.lifecycleState,
        isEncrypted = message.isEncrypted,
        showTail = showTail,
        modifier = Modifier
            .padding(vertical = AstraTheme.spacing.tiny)
            .background(AstraTheme.colors.primary.copy(alpha = highlightAlpha))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        replyContent = if (message.replyToId != null) {
            {
                com.astramesh.app.ui.adaptive.ReplyPreview(
                    senderName = "Replied",
                    messageText = "Original message text...",
                    accentColor = accentColor,
                    modifier = Modifier.clickable { onReplyClick(message.replyToId) }
                )
            }
        } else null
    ) {
        Column {
            if (message.hasAttachments) {
                com.astramesh.app.ui.components.MediaAttachmentCard(
                    message = message, 
                    onClick = {  }
                )
            }
            if (message.text.isNotBlank()) {
                val isEmoji = com.astramesh.app.ui.utils.TextUtils.isEmojiOnly(message.text)
                val annotatedText = com.astramesh.app.ui.utils.TextUtils.parseMarkdown(message.text, codeColor = AstraTheme.colors.onSurfaceVariant.copy(alpha = 0.2f))
                
                Text(
                    text = annotatedText,
                    color = if (isSent) AstraTheme.colors.onPrimary else AstraTheme.colors.onSurfaceVariant,
                    style = AstraTheme.typography.bodyMedium,
                    fontSize = if (isEmoji) 48.sp else AstraTheme.typography.bodyMedium.fontSize,
                    lineHeight = if (isEmoji) 56.sp else AstraTheme.typography.bodyMedium.lineHeight
                )
            }
        }
    }
}

@Composable
fun DateSeparator(dateStr: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = AstraTheme.spacing.medium), contentAlignment = Alignment.Center) {
        Text(
            text = dateStr,
            fontSize = AstraTheme.typography.labelMedium.fontSize,
            color = AstraTheme.colors.onSurfaceVariant,
            modifier = Modifier.background(AstraTheme.colors.surface, RoundedCornerShape(AstraTheme.spacing.medium)).padding(horizontal = 10.dp, vertical = AstraTheme.spacing.tiny)
        )
    }
}

@Composable
fun ReplyPreviewBanner(message: MessagePayload, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AstraTheme.colors.surface)
            .padding(horizontal = AstraTheme.spacing.standard, vertical = AstraTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(AstraTheme.spacing.tiny).height(AstraTheme.spacing.massive1).background(AstraTheme.colors.primary, RoundedCornerShape(2.dp)))
        Spacer(modifier = Modifier.width(AstraTheme.spacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (message.senderId == "me") "You" else "Them", fontSize = AstraTheme.typography.bodySmall.fontSize, color = AstraTheme.colors.primary, fontWeight = FontWeight.Bold)
            Text(message.text, fontSize = AstraTheme.typography.bodySmall.fontSize, color = AstraTheme.colors.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, "Cancel", tint = AstraTheme.colors.onSurfaceVariant)
        }
    }
}
