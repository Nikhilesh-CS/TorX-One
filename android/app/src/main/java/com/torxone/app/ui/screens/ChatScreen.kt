package com.torxone.app.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.chat.*
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.media.MediaStatus
import com.torxone.app.media.MediaType
import com.torxone.app.media.VoiceNoteHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * ChatScreen with GroupChatViewModel state binding.
 */
@Composable
fun ChatScreen(
    viewModel: com.torxone.app.groups.GroupChatViewModel,
    onBackClick: () -> Unit,
    onHeaderClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var infoMessageId by remember { mutableStateOf<String?>(null) }
    var deliverySummary by remember { mutableStateOf<com.torxone.app.groups.GroupMessageDeliverySummary?>(null) }

    if (infoMessageId != null) {
        LaunchedEffect(infoMessageId) {
            deliverySummary = viewModel.getDeliverySummary(infoMessageId!!)
        }
        GroupMessageInfoDialog(
            summary = deliverySummary,
            onDismiss = {
                infoMessageId = null
                deliverySummary = null
            }
        )
    }

    ChatScreen(
        contactName = uiState.title,
        subtitleOverride = uiState.subtitle,
        isGroup = true,
        isParticipantActive = uiState.isParticipantActive,
        messages = uiState.messages,
        composerText = uiState.composerText,
        presence = uiState.presence,
        lastSeenAt = uiState.lastSeenAt,
        isTyping = uiState.isTyping,
        replyingTo = uiState.replyingTo,
        editingMessage = uiState.editingMessage,
        voiceRecording = uiState.voiceRecording,
        onComposerTextChange = viewModel::onComposerTextChanged,
        onSendMessage = viewModel::sendText,
        onReply = viewModel::onReply,
        onCancelReply = viewModel::cancelReply,
        onStartEdit = viewModel::startEditing,
        onCancelEdit = viewModel::cancelEditing,
        onToggleReaction = viewModel::toggleReaction,
        onDeleteForMe = viewModel::deleteForMe,
        onDeleteForEveryone = viewModel::deleteForEveryone,
        onSendImage = { name, bytes -> viewModel.sendImage(name, bytes) },
        onSendDocument = { name, bytes -> viewModel.sendDocument(name, bytes) },
        onRequestMessageInfo = { msg ->
            infoMessageId = msg.logicalMessageId
        },
        onHeaderClick = onHeaderClick,
        onBackClick = onBackClick,
        modifier = modifier
    )
}

/**
 * ChatScreen with ChatViewModel state binding.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onHeaderClick: () -> Unit = {},
    onStartVoiceCall: (() -> Unit)? = null,
    onStartVideoCall: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceRecording()
        }
    }

    val callAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartVoiceCall?.invoke()
        }
    }

    val videoCallPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            onStartVideoCall?.invoke()
        }
    }

    ChatScreen(
        contactName = uiState.title,
        subtitleOverride = uiState.subtitle,
        isGroup = uiState.isGroup,
        isParticipantActive = uiState.isParticipantActive,
        messages = uiState.messages,
        composerText = uiState.composerText,
        presence = uiState.presence,
        lastSeenAt = uiState.lastSeenAt,
        isTyping = uiState.isTyping,
        replyingTo = uiState.replyingTo,
        editingMessage = uiState.editingMessage,
        voiceRecording = uiState.voiceRecording,
        onComposerTextChange = viewModel::onComposerTextChanged,
        onSendMessage = viewModel::sendText,
        onReply = viewModel::onReply,
        onCancelReply = viewModel::cancelReply,
        onStartEdit = viewModel::startEditing,
        onCancelEdit = viewModel::cancelEditing,
        onToggleReaction = viewModel::toggleReaction,
        onDeleteForMe = viewModel::deleteForMe,
        onDeleteForEveryone = viewModel::deleteForEveryone,
        onStartVoiceRecording = {
            if (com.torxone.app.ui.permissions.PermissionHelper.isRecordAudioGranted(context)) {
                viewModel.startVoiceRecording()
            } else {
                recordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        },
        onCancelVoiceRecording = viewModel::cancelVoiceRecording,
        onFinishVoiceRecording = viewModel::finishVoiceRecording,
        onSendImage = { name, bytes -> viewModel.sendImage(name, bytes) },
        onSendDocument = { name, bytes -> viewModel.sendDocument(name, bytes) },
        onCancelMediaTransfer = viewModel::cancelMediaTransfer,
        onHeaderClick = onHeaderClick,
        onBackClick = onBackClick,
        onStartVoiceCall = onStartVoiceCall?.let {
            {
                if (com.torxone.app.ui.permissions.PermissionHelper.isRecordAudioGranted(context)) {
                    onStartVoiceCall()
                } else {
                    callAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        onStartVideoCall = onStartVideoCall?.let {
            {
                val perms = arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
                if (com.torxone.app.ui.permissions.PermissionHelper.arePermissionsGranted(context, perms)) {
                    onStartVideoCall()
                } else {
                    videoCallPermissionsLauncher.launch(perms)
                }
            }
        },
        modifier = modifier
    )
}

/**
 * ChatScreen — Unified presentation view for Direct and Group conversations.
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
    subtitleOverride: String? = null,
    isGroup: Boolean = false,
    isParticipantActive: Boolean = true,
    replyingTo: MessageUiModel? = null,
    editingMessage: MessageUiModel? = null,
    voiceRecording: VoiceRecordingState = VoiceRecordingState(),
    onComposerTextChange: (String) -> Unit = {},
    onSendMessage: () -> Unit = {},
    onReply: (MessageUiModel) -> Unit = {},
    onCancelReply: () -> Unit = {},
    onStartEdit: (MessageUiModel) -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onToggleReaction: (String, String) -> Unit = { _, _ -> },
    onDeleteForMe: (String) -> Unit = {},
    onDeleteForEveryone: (String) -> Unit = {},
    onStartVoiceRecording: () -> Unit = {},
    onCancelVoiceRecording: () -> Unit = {},
    onFinishVoiceRecording: () -> Unit = {},
    onSendImage: (String, ByteArray) -> Unit = { _, _ -> },
    onSendDocument: (String, ByteArray) -> Unit = { _, _ -> },
    onCancelMediaTransfer: (String) -> Unit = {},
    onRequestMessageInfo: ((MessageUiModel) -> Unit)? = null,
    onHeaderClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onStartVoiceCall: (() -> Unit)? = null,
    onStartVideoCall: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var selectedMessageForMenu by remember { mutableStateOf<MessageUiModel?>(null) }
    var messagePendingDelete by remember { mutableStateOf<MessageUiModel?>(null) }
    var viewerMedia by remember { mutableStateOf<MediaUiModel?>(null) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    // Auto-scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onHeaderClick() }
                            .padding(vertical = 4.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = contactName.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = contactName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            val subtitleText = subtitleOverride ?: when {
                                isTyping -> "typing…"
                                presence == PresenceStatus.ONLINE -> "online"
                                presence == PresenceStatus.OFFLINE && lastSeenAt != null -> formatLastSeen(lastSeenAt)
                                else -> "offline"
                            }
                            val subtitleColor = when {
                                isTyping -> MaterialTheme.colorScheme.primary
                                presence == PresenceStatus.ONLINE -> Color(0xFF25D366)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }

                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = subtitleColor,
                                fontWeight = if (isTyping || presence == PresenceStatus.ONLINE) FontWeight.Medium else FontWeight.Normal
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isGroup) {
                        if (onStartVoiceCall != null) {
                            IconButton(onClick = onStartVoiceCall) {
                                Icon(
                                    Icons.Default.Call,
                                    contentDescription = "Voice Call",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        if (onStartVideoCall != null) {
                            IconButton(onClick = onStartVideoCall) {
                                Icon(
                                    Icons.Default.Videocam,
                                    contentDescription = "Video Call",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (!isParticipantActive) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "You are no longer a participant in this group.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                MessageComposer(
                    text = composerText,
                    replyingTo = replyingTo,
                    editingMessage = editingMessage,
                    voiceRecording = voiceRecording,
                    contactName = contactName,
                    onTextChange = onComposerTextChange,
                    onCancelReply = onCancelReply,
                    onCancelEdit = onCancelEdit,
                    onSend = onSendMessage,
                    onAttachClick = { showAttachmentMenu = true },
                    onMicClick = onStartVoiceRecording,
                    onCancelRecording = onCancelVoiceRecording,
                    onSendRecording = onFinishVoiceRecording
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 8.dp,
                start = 12.dp,
                end = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            itemsIndexed(
                items = messages,
                key = { _, msg -> msg.logicalMessageId }
            ) { _, message ->
                MessageBubble(
                    message = message,
                    contactName = contactName,
                    isHighlighted = message.logicalMessageId == highlightedMessageId,
                    onReply = { onReply(message) },
                    onLongClick = { selectedMessageForMenu = message },
                    onMediaClick = { media ->
                        if (media.type == MediaType.IMAGE) {
                            viewerMedia = media
                        }
                    },
                    onQuoteClick = { targetMsgId ->
                        coroutineScope.launch {
                            val targetIndex = messages.indexOfFirst { it.logicalMessageId == targetMsgId }
                            if (targetIndex != -1) {
                                listState.animateScrollToItem(targetIndex)
                                highlightedMessageId = targetMsgId
                                delay(1200)
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
    }

    // Attachment Picker Bottom Sheet
    if (showAttachmentMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentMenu = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Share Attachment",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AttachmentOptionItem(
                        icon = Icons.Default.Image,
                        label = "Photo",
                        containerColor = Color(0xFFE91E63)
                    ) {
                        showAttachmentMenu = false
                        val dummyImageBytes = ByteArray(1024) { 0xFF.toByte() }
                        onSendImage("photo_${System.currentTimeMillis()}.jpg", dummyImageBytes)
                    }

                    AttachmentOptionItem(
                        icon = Icons.Default.Videocam,
                        label = "Video",
                        containerColor = Color(0xFF9C27B0)
                    ) {
                        showAttachmentMenu = false
                        val dummyVideoBytes = ByteArray(2048) { 0x55.toByte() }
                        onSendImage("video_${System.currentTimeMillis()}.mp4", dummyVideoBytes)
                    }

                    AttachmentOptionItem(
                        icon = Icons.Default.Description,
                        label = "Document",
                        containerColor = Color(0xFF2196F3)
                    ) {
                        showAttachmentMenu = false
                        val dummyDocBytes = "Sample project document content".toByteArray(Charsets.UTF_8)
                        onSendDocument("project_specs.pdf", dummyDocBytes)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Full-screen Image / Media Viewer Dialog
    viewerMedia?.let { media ->
        Dialog(
            onDismissRequest = { viewerMedia = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(120.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = media.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${media.fileSize / 1024} KB • Encrypted End-to-End",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray
                        )
                    }

                    IconButton(
                        onClick = { viewerMedia = null },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }

    // Long Press Action Sheet
    selectedMessageForMenu?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { selectedMessageForMenu = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                // Emoji Reaction Quick Bar
                if (!target.isDeleted) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val quickEmojis = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
                        for (emoji in quickEmojis) {
                            val userReacted = target.reactions.any { it.emoji == emoji && it.userReacted }
                            Surface(
                                shape = CircleShape,
                                color = if (userReacted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable {
                                        onToggleReaction(target.logicalMessageId, emoji)
                                        selectedMessageForMenu = null
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(text = emoji, fontSize = 22.sp)
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                if (!target.isDeleted) {
                    ListItem(
                        headlineContent = { Text("Reply") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                        modifier = Modifier.clickable {
                            onReply(target)
                            selectedMessageForMenu = null
                        }
                    )
                }

                if (!target.isDeleted && !target.body.isNullOrEmpty()) {
                    ListItem(
                        headlineContent = { Text("Copy text") },
                        leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(target.body))
                            selectedMessageForMenu = null
                        }
                    )
                }

                if (!target.isDeleted && target.direction == MessageDirection.OUTGOING && target.media == null) {
                    ListItem(
                        headlineContent = { Text("Edit") },
                        leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                        modifier = Modifier.clickable {
                            onStartEdit(target)
                            selectedMessageForMenu = null
                        }
                    )
                }

                if (!target.isDeleted && target.direction == MessageDirection.OUTGOING && isGroup && onRequestMessageInfo != null) {
                    ListItem(
                        headlineContent = { Text("Message info") },
                        leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                        modifier = Modifier.clickable {
                            val msg = target
                            selectedMessageForMenu = null
                            onRequestMessageInfo(msg)
                        }
                    )
                }

                ListItem(
                    headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        messagePendingDelete = target
                        selectedMessageForMenu = null
                    }
                )
            }
        }
    }

    // Delete Confirmation Dialog
    messagePendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { messagePendingDelete = null },
            title = { Text("Delete message?") },
            text = { Text("Choose whether to delete this message just for yourself, or for everyone in the conversation.") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (target.direction == MessageDirection.OUTGOING && !target.isDeleted) {
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

@Composable
private fun AttachmentOptionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    containerColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = containerColor,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
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
    onMediaClick: (MediaUiModel) -> Unit,
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
                        onClick = {
                            message.media?.let { onMediaClick(it) }
                        },
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
                    // Sender display name (for group chats)
                    if (!isOutgoing && message.senderDisplayName != null && !message.isDeleted) {
                        Text(
                            text = message.senderDisplayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }

                    // Quoted snippet
                    if (message.quotedMessage != null && !message.isDeleted) {
                        QuotedBubbleView(
                            quoted = message.quotedMessage,
                            isOutgoingBubble = isOutgoing,
                            onClick = { onQuoteClick(message.quotedMessage.messageId) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Deleted message tombstone
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
                                tint = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "This message was deleted",
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        // Media Attachment View (if present)
                        message.media?.let { media ->
                            when (media.type) {
                                MediaType.IMAGE -> ImageBubbleView(media, isOutgoing)
                                MediaType.VOICE_NOTE -> VoiceNoteBubbleView(media, isOutgoing)
                                MediaType.VIDEO -> VideoBubbleView(media, isOutgoing)
                                MediaType.DOCUMENT, MediaType.AUDIO -> DocumentBubbleView(media, isOutgoing)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // Text body (if not purely media or if has text caption)
                        if (message.media == null || (message.body != null && message.body != message.media.fileName)) {
                            Text(
                                text = message.body ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Metadata footer
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.isEdited && !message.isDeleted) {
                            Text(
                                text = "(edited)",
                                style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        Text(
                            text = formatMessageTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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

            // Emoji Reaction Pills
            if (message.reactions.isNotEmpty() && !message.isDeleted) {
                Row(
                    modifier = Modifier
                        .offset(y = (-8).dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (reaction in message.reactions) {
                        ReactionPill(
                            reaction = reaction,
                            onClick = { onToggleReaction(reaction.emoji) }
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Media Bubble Renderers
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ImageBubbleView(media: MediaUiModel, isOutgoing: Boolean) {
    val imageBitmap = remember(media.thumbnailData) {
        media.thumbnailData?.let {
            try {
                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = media.fileName,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${media.fileSize / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (media.status == MediaStatus.UPLOADING || media.status == MediaStatus.DOWNLOADING) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { media.progress },
                        color = Color.White,
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceNoteBubbleView(media: MediaUiModel, isOutgoing: Boolean) {
    var isPlaying by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf("1x") }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        FilledIconButton(
            onClick = { isPlaying = !isPlaying },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                contentColor = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play"
            )
        }

        // Waveform Bars
        val waveformList = remember(media.waveformData) {
            VoiceNoteHelper.normalizeWaveform(media.waveformData)
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (level in waveformList.take(28)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(level.coerceIn(0.15f, 1.0f))
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (isOutgoing)
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                            else
                                MaterialTheme.colorScheme.primary
                        )
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = VoiceNoteHelper.formatDuration(media.durationMs ?: 0L),
                style = MaterialTheme.typography.labelSmall,
                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = (if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha = 0.15f),
                modifier = Modifier.clickable {
                    speed = when (speed) {
                        "1x" -> "1.5x"
                        "1.5x" -> "2x"
                        else -> "1x"
                    }
                }
            ) {
                Text(
                    text = speed,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun VideoBubbleView(media: MediaUiModel, isOutgoing: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }

        // Duration & Size badge in corner
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
        ) {
            Text(
                text = "${VoiceNoteHelper.formatDuration(media.durationMs ?: 0L)} • ${media.fileSize / (1024 * 1024)} MB",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun DocumentBubbleView(media: MediaUiModel, isOutgoing: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${media.fileSize / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                if (media.status == MediaStatus.COMPLETE) Icons.Default.Check else Icons.Default.Download,
                contentDescription = null,
                tint = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ReactionPill(
    reaction: ReactionSummaryUiModel,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (reaction.userReacted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = reaction.emoji, fontSize = 12.sp)
            if (reaction.count > 1) {
                Text(
                    text = reaction.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (reaction.userReacted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuotedBubbleView(
    quoted: QuotedMessageUiModel,
    isOutgoingBubble: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = if (isOutgoingBubble)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else
            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Row(modifier = Modifier.padding(6.dp)) {
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
                    color = if (isOutgoingBubble) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = quoted.previewText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOutgoingBubble) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageComposer(
    text: String,
    replyingTo: MessageUiModel?,
    editingMessage: MessageUiModel?,
    voiceRecording: VoiceRecordingState,
    contactName: String,
    onTextChange: (String) -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    onMicClick: () -> Unit,
    onCancelRecording: () -> Unit,
    onSendRecording: () -> Unit
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

            // Input Bar vs Live Voice Recording Mode
            if (voiceRecording.isRecording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onCancelRecording) {
                        Icon(Icons.Default.Delete, contentDescription = "Cancel recording", tint = MaterialTheme.colorScheme.error)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(shape = CircleShape, color = Color.Red, modifier = Modifier.size(10.dp)) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = VoiceNoteHelper.formatDuration(voiceRecording.elapsedDurationMs),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    FilledIconButton(
                        onClick = onSendRecording,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice note")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = onAttachClick) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach file")
                    }

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

                    if (text.isNotBlank() || editingMessage != null) {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = text.isNotBlank()
                        ) {
                            Icon(
                                if (editingMessage != null) Icons.Default.Check else Icons.AutoMirrored.Filled.Send,
                                contentDescription = if (editingMessage != null) "Confirm edit" else "Send"
                            )
                        }
                    } else {
                        FilledIconButton(
                            onClick = onMicClick,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Record voice note")
                        }
                    }
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
            Color(0xFF34B7F1)
        else if (status == DeliveryStatus.FAILED)
            MaterialTheme.colorScheme.error
        else
            tint
    )
}

private fun formatMessageTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun formatLastSeen(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "last seen just now"
        diff < 3600_000L -> "last seen ${diff / 60_000L}m ago"
        else -> "last seen ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))}"
    }
}
