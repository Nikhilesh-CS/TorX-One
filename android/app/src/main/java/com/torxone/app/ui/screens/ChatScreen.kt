package com.torxone.app.ui.screens

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RequiresApi
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.torxone.app.ui.components.*
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
import com.torxone.app.ui.theme.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.torxone.app.media.VoiceNoteHelper
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
import androidx.compose.ui.viewinterop.AndroidView
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.chat.*
import com.torxone.app.data.entity.ConversationAppearanceEntity
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.media.MediaStatus
import com.torxone.app.media.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    modifier: Modifier = Modifier,
    onHeaderClick: () -> Unit = {},
    onForward: ((List<String>) -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onSchedule: ((String, String?) -> Unit)? = null,
    connectionSnapshot: com.torxone.app.ui.connection.ConnectionUxSnapshot? = null,
    onOpenConnection: (() -> Unit)? = null,
    onDisappearing: (() -> Unit)? = null,
    initialMessageId: String? = null,
    appearance: ConversationAppearanceEntity? = null,
    onSaveAppearance: (suspend (ConversationAppearanceEntity) -> Unit)? = null,
    entryUnreadMessageId: String? = null,
    entryUnreadCount: Int = 0,
    hasEarlierMessages: Boolean = false,
    onLoadEarlierMessages: (() -> Unit)? = null,
    onJumpToMessage: ((String) -> Unit)? = null,
    onJumpToLatest: (() -> Unit)? = null,
    isLatestWindow: Boolean = true,
    newerMessageCount: Int = 0,
    onEditAppearance: (() -> Unit)? = null,
    chatAppearance: com.torxone.app.ui.appearance.ChatAppearance? = null,
    onRetryDelivery: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var localPermissionError by remember { mutableStateOf<String?>(null) }
    val recordAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startVoiceRecording()
        else localPermissionError = "Microphone permission is required for voice notes"
    }
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
        contactAvatar = uiState.avatarHash,
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
        starredIds = uiState.starredIds,
        onSetStarred = viewModel::setStarred,
        onForward = onForward,
        onSearch = onSearch,
        onSchedule = onSchedule,
        connectionSnapshot = connectionSnapshot,
        onOpenConnection = onOpenConnection,
        onDisappearing = onDisappearing,
        initialMessageId = initialMessageId,
        appearance = appearance,
        onSaveAppearance = onSaveAppearance,
        entryUnreadMessageId = entryUnreadMessageId,
        entryUnreadCount = entryUnreadCount,
        onEditAppearance = onEditAppearance,
        chatAppearance = chatAppearance,
        onRetryDelivery = onRetryDelivery,
        hasEarlierMessages = uiState.hasEarlierMessages || hasEarlierMessages,
        onLoadEarlierMessages = onLoadEarlierMessages ?: viewModel::loadEarlierMessages,
        onJumpToMessage = onJumpToMessage ?: viewModel::openTimelineMessage,
        onJumpToLatest = onJumpToLatest ?: viewModel::jumpToLatestMessages,
        isLatestWindow = uiState.isLatestWindow && isLatestWindow,
        newerMessageCount = maxOf(uiState.newerMessageCount, newerMessageCount),
        uiError = uiState.error ?: localPermissionError,
        onDismissUiError = { localPermissionError = null; viewModel.clearError() },
        isSending = uiState.isSending,
        onSendImage = { name, bytes, mime -> viewModel.sendImage(name, bytes, mime) },
        onSendVideo = { name, bytes, mime -> viewModel.sendImage(name, bytes, mime) },
        onSendDocument = { name, bytes, mime -> viewModel.sendDocument(name, bytes, mime) },
        onDownloadMedia = viewModel::downloadMedia,
        onCancelMediaTransfer = viewModel::cancelMediaTransfer,
        onPauseMediaTransfer = viewModel::pauseMediaTransfer,
        onResumeMediaTransfer = viewModel::downloadMedia,
        onStartVoiceRecording = {
            if (com.torxone.app.ui.permissions.PermissionHelper.isRecordAudioGranted(context)) {
                viewModel.startVoiceRecording()
            } else {
                recordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        },
        onCancelVoiceRecording = viewModel::cancelVoiceRecording,
        onFinishVoiceRecording = viewModel::finishVoiceRecording,
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
    modifier: Modifier = Modifier,
    contactAvatar: String? = null,
    displayName: String? = null,
    onHeaderClick: () -> Unit = {},
    onStartVoiceCall: (() -> Unit)? = null,
    onStartVideoCall: (() -> Unit)? = null,
    onForward: ((List<String>) -> Unit)? = null,
    onRequestMessageInfo: ((MessageUiModel) -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onSchedule: ((String, String?) -> Unit)? = null,
    connectionSnapshot: com.torxone.app.ui.connection.ConnectionUxSnapshot? = null,
    onOpenConnection: (() -> Unit)? = null,
    onDisappearing: (() -> Unit)? = null,
    initialMessageId: String? = null,
    appearance: ConversationAppearanceEntity? = null,
    onSaveAppearance: (suspend (ConversationAppearanceEntity) -> Unit)? = null,
    entryUnreadMessageId: String? = null,
    entryUnreadCount: Int = 0,
    hasEarlierMessages: Boolean = false,
    onLoadEarlierMessages: (() -> Unit)? = null,
    onJumpToMessage: ((String) -> Unit)? = null,
    onJumpToLatest: (() -> Unit)? = null,
    isLatestWindow: Boolean = true,
    newerMessageCount: Int = 0,
    onEditAppearance: (() -> Unit)? = null,
    chatAppearance: com.torxone.app.ui.appearance.ChatAppearance? = null,
    onRetryDelivery: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var localPermissionError by remember { mutableStateOf<String?>(null) }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceRecording()
        } else localPermissionError = "Microphone permission is required for voice notes"
    }

    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val callNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) localPermissionError = "Calls cannot ring in the background while notifications are disabled."
        pendingCallAction?.invoke()
        pendingCallAction = null
    }
    fun startWithCallNotificationSetup(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingCallAction = action
            callNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                localPermissionError = "Calls cannot ring in the background while notifications are disabled."
            }
            action()
        }
    }

    val callAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartVoiceCall?.let { startWithCallNotificationSetup(it) }
        } else localPermissionError = "Microphone permission is required for calls."
    }

    val videoCallPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            onStartVideoCall?.let { startWithCallNotificationSetup(it) }
        } else localPermissionError = "Camera and microphone permissions are required for video calls."
    }

    ChatScreen(
        contactName = displayName ?: uiState.title,
        contactAvatar = contactAvatar,
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
        starredIds = uiState.starredIds,
        onSetStarred = viewModel::setStarred,
        onForward = onForward,
        onSearch = onSearch,
        onSchedule = onSchedule,
        connectionSnapshot = connectionSnapshot,
        onOpenConnection = onOpenConnection,
        onDisappearing = onDisappearing,
        onRequestMessageInfo = onRequestMessageInfo,
        initialMessageId = initialMessageId,
        appearance = appearance,
        onSaveAppearance = onSaveAppearance,
        entryUnreadMessageId = entryUnreadMessageId,
        entryUnreadCount = entryUnreadCount,
        onEditAppearance = onEditAppearance,
        chatAppearance = chatAppearance,
        onRetryDelivery = onRetryDelivery,
        hasEarlierMessages = uiState.hasEarlierMessages || hasEarlierMessages,
        onLoadEarlierMessages = onLoadEarlierMessages ?: viewModel::loadEarlierMessages,
        onJumpToMessage = onJumpToMessage ?: viewModel::openTimelineMessage,
        onJumpToLatest = onJumpToLatest ?: viewModel::jumpToLatestMessages,
        isLatestWindow = uiState.isLatestWindow && isLatestWindow,
        newerMessageCount = maxOf(uiState.newerMessageCount, newerMessageCount),
        uiError = uiState.error ?: localPermissionError,
        onDismissUiError = { localPermissionError = null; viewModel.clearError() },
        isSending = uiState.isSending,
        onStartVoiceRecording = {
            if (com.torxone.app.ui.permissions.PermissionHelper.isRecordAudioGranted(context)) {
                viewModel.startVoiceRecording()
            } else {
                recordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        },
        onCancelVoiceRecording = viewModel::cancelVoiceRecording,
        onFinishVoiceRecording = viewModel::finishVoiceRecording,
        onSendImage = { name, bytes, mime -> viewModel.sendImage(name, bytes, mime) },
        onSendVideo = { name, bytes, mime -> viewModel.sendVideo(name, bytes, mime) },
        onSendDocument = { name, bytes, mime -> viewModel.sendDocument(name, bytes, mime) },
        onDownloadMedia = viewModel::downloadMedia,
        onCancelMediaTransfer = viewModel::cancelMediaTransfer,
        onPauseMediaTransfer = viewModel::pauseMediaTransfer,
        onResumeMediaTransfer = viewModel::downloadMedia,
        onHeaderClick = onHeaderClick,
        onBackClick = onBackClick,
        onStartVoiceCall = onStartVoiceCall?.let {
            {
                if (com.torxone.app.ui.permissions.PermissionHelper.isRecordAudioGranted(context)) {
                    startWithCallNotificationSetup(onStartVoiceCall)
                } else {
                    callAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        onStartVideoCall = onStartVideoCall?.let {
            {
                val perms = arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
                if (com.torxone.app.ui.permissions.PermissionHelper.arePermissionsGranted(context, perms)) {
                    startWithCallNotificationSetup(onStartVideoCall)
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    contactName: String,
    messages: List<MessageUiModel>,
    composerText: String,
    modifier: Modifier = Modifier,
    contactAvatar: String? = null,
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
    onSendImage: (String, ByteArray, String) -> Unit = { _, _, _ -> },
    onSendVideo: (String, ByteArray, String) -> Unit = { _, _, _ -> },
    onSendDocument: (String, ByteArray, String) -> Unit = { _, _, _ -> },
    onCancelMediaTransfer: ((String) -> Unit)? = null,
    onPauseMediaTransfer: ((String) -> Unit)? = null,
    onResumeMediaTransfer: ((String) -> Unit)? = null,
    onDownloadMedia: ((String) -> Unit)? = null,
    onRequestMessageInfo: ((MessageUiModel) -> Unit)? = null,
    onHeaderClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onStartVoiceCall: (() -> Unit)? = null,
    onStartVideoCall: (() -> Unit)? = null,
    starredIds: Set<String> = emptySet(),
    onSetStarred: ((Set<String>, Boolean) -> Unit)? = null,
    onForward: ((List<String>) -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onSchedule: ((String, String?) -> Unit)? = null,
    connectionSnapshot: com.torxone.app.ui.connection.ConnectionUxSnapshot? = null,
    onOpenConnection: (() -> Unit)? = null,
    onDisappearing: (() -> Unit)? = null,
    initialMessageId: String? = null,
    appearance: ConversationAppearanceEntity? = null,
    onSaveAppearance: (suspend (ConversationAppearanceEntity) -> Unit)? = null,
    entryUnreadMessageId: String? = null,
    entryUnreadCount: Int = 0,
    hasEarlierMessages: Boolean = false,
    onLoadEarlierMessages: (() -> Unit)? = null,
    onJumpToMessage: ((String) -> Unit)? = null,
    onJumpToLatest: (() -> Unit)? = null,
    isLatestWindow: Boolean = true,
    newerMessageCount: Int = 0,
    onEditAppearance: (() -> Unit)? = null,
    chatAppearance: com.torxone.app.ui.appearance.ChatAppearance? = null,
    onRetryDelivery: (() -> Unit)? = null,
    uiError: String? = null,
    onDismissUiError: () -> Unit = {},
    isSending: Boolean = false
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var selectedMessageForMenu by remember { mutableStateOf<MessageUiModel?>(null) }
    var reactionPickerMessageId by remember { mutableStateOf<String?>(null) }
    val reactionHaptics = LocalHapticFeedback.current
    val react: (String, String) -> Unit = { id, emoji ->
        reactionHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        EmojiReactionHistory.record(context, emoji)
        onToggleReaction(id, emoji)
    }
    var messagePendingDelete by remember { mutableStateOf<MessageUiModel?>(null) }
    var viewerMedia by remember { mutableStateOf<MediaUiModel?>(null) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var selectionMenu by remember { mutableStateOf(false) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var chatOptionsOpen by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var jumpApplied by remember(initialMessageId) { mutableStateOf(false) }
    var requestedJumpId by remember(initialMessageId) { mutableStateOf<String?>(null) }
    var pendingQuoteJumpId by remember { mutableStateOf<String?>(null) }
    var latestRequested by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }
    var preparingAttachment by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    fun stageAttachment(uri: android.net.Uri?, type: MediaType, fallback: String, forcedMime: String? = null) {
        if (uri != null) {
            attachmentError = null
            pendingAttachment = PendingAttachment(resolveMediaFileName(context, uri, fallback),
                forcedMime ?: context.contentResolver.getType(uri) ?: "application/octet-stream", type, uri = uri)
        }
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        stageAttachment(it, MediaType.IMAGE, "photo.jpg")
    }
    val videoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        stageAttachment(it, MediaType.VIDEO, "video.mp4")
    }
    val docPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        stageAttachment(it, MediaType.DOCUMENT, "document")
    }
    val cameraCaptureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) pendingAttachment = PendingAttachment("camera_${System.currentTimeMillis()}.jpg",
            "image/jpeg", MediaType.IMAGE, cameraBitmap = bitmap)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraCaptureLauncher.launch(null) else permissionError = "Camera permission is required to take a photo."
    }
    fun takePhoto() {
        if (com.torxone.app.ui.permissions.PermissionHelper.isCameraGranted(context)) cameraCaptureLauncher.launch(null)
        else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }
    val gifPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        stageAttachment(it, MediaType.IMAGE, "animation.gif", "image/gif")
    }
    pendingAttachment?.let { attachment ->
        AttachmentPreview(attachment, preparingAttachment, attachmentError,
            onCancel = { pendingAttachment = null; attachmentError = null }, onSend = {
                if (!preparingAttachment) coroutineScope.launch {
                    preparingAttachment = true
                    attachmentError = null
                    try {
                        val bytes = if (attachment.cameraBitmap != null) withContext(kotlinx.coroutines.Dispatchers.Default) {
                            java.io.ByteArrayOutputStream().use { output ->
                                attachment.cameraBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)
                                output.toByteArray()
                            }
                        } else attachment.uri?.let { readUriWithLimit(context, it) { error -> attachmentError = error } }
                        if (bytes != null && bytes.isNotEmpty()) {
                            when (attachment.type) {
                                MediaType.IMAGE -> onSendImage(attachment.name, bytes, attachment.mimeType)
                                MediaType.VIDEO -> onSendVideo(attachment.name, bytes, attachment.mimeType)
                                else -> onSendDocument(attachment.name, bytes, attachment.mimeType)
                            }
                            pendingAttachment = null
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { attachmentError = failure.message ?: "Unable to prepare attachment" }
                    finally { preparingAttachment = false }
                }
            })
    }

    var followLatest by remember { mutableStateOf(true) }
    var previousCount by remember { mutableIntStateOf(0) }
    var lastKnownMessageId by remember { mutableStateOf<String?>(null) }
    var unreadSinceScroll by remember { mutableIntStateOf(0) }
    var calendarRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) { calendarRevision++ }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_DATE_CHANGED)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val timeline = remember(messages, entryUnreadMessageId, entryUnreadCount, calendarRevision) {
        chatTimeline(messages, entryUnreadMessageId, entryUnreadCount)
    }
    val messageIndices = remember(messages) { messages.mapIndexed { index, message -> message.logicalMessageId to index }.toMap() }
    LaunchedEffect(listState, isLatestWindow) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index,
            listState.layoutInfo.totalItemsCount) }.collect { (scrolling, last, total) ->
            if (!scrolling && total > 0) {
                followLatest = isLatestWindow && (last ?: 0) >= total - 2
                if (followLatest) unreadSinceScroll = 0
            }
        }
    }
    LaunchedEffect(messages.lastOrNull()?.logicalMessageId, messages.size, isLatestWindow) {
        if (messages.isNotEmpty()) {
            val oldLastIndex = lastKnownMessageId?.let(messageIndices::get)
            val appended = if (oldLastIndex != null) messages.drop(oldLastIndex + 1) else emptyList()
            val outgoing = appended.any { it.direction == MessageDirection.OUTGOING }
            val firstOpen = previousCount == 0
            val entryIndex = entryUnreadMessageId?.let(messageIndices::get)
            if (latestRequested && isLatestWindow) {
                listState.scrollToItem(messages.lastIndex)
                followLatest = true; unreadSinceScroll = 0; latestRequested = false
            } else if (firstOpen && initialMessageId == null && entryIndex != null) {
                listState.scrollToItem(entryIndex)
                followLatest = false
            } else if ((initialMessageId == null || jumpApplied) && isLatestWindow && (firstOpen || followLatest || outgoing)) {
                listState.scrollToItem(messages.lastIndex)
                followLatest = true
                unreadSinceScroll = 0
            } else if (!followLatest) unreadSinceScroll += appended.count { it.direction == MessageDirection.INCOMING }
            previousCount = messages.size
            lastKnownMessageId = messages.last().logicalMessageId
        }
    }
    LaunchedEffect(initialMessageId, messageIndices) {
        if (!jumpApplied && initialMessageId != null) {
            val index = messageIndices[initialMessageId]
            if (index != null) {
                listState.scrollToItem(index); highlightedMessageId = initialMessageId
                followLatest = false; jumpApplied = true
            } else if (requestedJumpId != initialMessageId && onJumpToMessage != null) {
                requestedJumpId = initialMessageId
                onJumpToMessage(initialMessageId)
            }
        }
        selectedIds = selectedIds.intersect(messages.filterNot { it.isDeleted }.map { it.logicalMessageId }.toSet())
    }
    LaunchedEffect(pendingQuoteJumpId, messageIndices) {
        val target = pendingQuoteJumpId
        val index = target?.let(messageIndices::get)
        if (target != null && index != null) {
            listState.scrollToItem(index); highlightedMessageId = target; followLatest = false
            pendingQuoteJumpId = null
            delay(1200)
            if (highlightedMessageId == target) highlightedMessageId = null
        }
    }
    androidx.activity.compose.BackHandler(selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    if (confirmBulkDelete) AlertDialog(onDismissRequest = { confirmBulkDelete = false }, title = { Text("Delete ${selectedIds.size} messages locally?") },
        confirmButton = { TextButton(onClick = { selectedIds.forEach(onDeleteForMe); selectedIds = emptySet(); confirmBulkDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") } })

    if (showAppearance && appearance != null && onSaveAppearance != null) {
        com.torxone.app.ui.components.ConversationAppearanceDialog(appearance, onSaveAppearance) { showAppearance = false }
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
                        com.torxone.app.ui.components.ProfileAvatar(contactName, contactAvatar, Modifier.size(48.dp), previewOnClick = true)

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
                                presence == PresenceStatus.UNKNOWN -> "last seen unavailable"
                                else -> "offline"
                            }
                            val subtitleColor = when {
                                isTyping -> MaterialTheme.colorScheme.primary
                                presence == PresenceStatus.ONLINE -> MaterialTheme.colorScheme.onlineStatus
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
                    IconButton(onClick = { if (selectedIds.isNotEmpty()) selectedIds = emptySet() else onBackClick() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedIds.isNotEmpty()) {
                        Text("${selectedIds.size} selected")
                        Box {
                            IconButton(onClick = { selectionMenu = true }) { Icon(Icons.Default.MoreVert, "Selection actions") }
                            DropdownMenu(selectionMenu, { selectionMenu = false }) {
                                DropdownMenuItem(text = { Text("Copy") }, onClick = {
                                    clipboardManager.setText(AnnotatedString(messages.filter { it.logicalMessageId in selectedIds }.mapNotNull { it.body }.joinToString("\n")))
                                    selectedIds = emptySet(); selectionMenu = false
                                })
                                val allStarred = selectedIds.all { it in starredIds }
                                onSetStarred?.let { star -> DropdownMenuItem(text = { Text(if (allStarred) "Unstar" else "Star") }, onClick = {
                                    star(selectedIds, !allStarred); selectedIds = emptySet(); selectionMenu = false
                                }) }
                                onForward?.let { forward -> DropdownMenuItem(text = { Text("Forward") }, onClick = {
                                    forward(messages.filter { it.logicalMessageId in selectedIds }.map { it.logicalMessageId }); selectedIds = emptySet(); selectionMenu = false
                                }) }
                                DropdownMenuItem(text = { Text("Delete locally") }, onClick = { confirmBulkDelete = true; selectionMenu = false })
                                if (selectedIds.size == 1) DropdownMenuItem(text = { Text("Message actions") }, onClick = {
                                    selectedMessageForMenu = messages.find { it.logicalMessageId in selectedIds }; selectedIds = emptySet(); selectionMenu = false
                                })
                            }
                        }
                    } else {
                    if (onSearch != null || onEditAppearance != null || onSaveAppearance != null || onSchedule != null || onOpenConnection != null || onDisappearing != null) Box {
                        IconButton(onClick = { chatOptionsOpen = true }) { Icon(Icons.Default.MoreVert, "Chat options") }
                        DropdownMenu(chatOptionsOpen, { chatOptionsOpen = false }) {
                            onSearch?.let { search -> DropdownMenuItem(text = { Text("Search this chat") }, onClick = { chatOptionsOpen = false; search() }) }
                            if (onEditAppearance != null || onSaveAppearance != null) DropdownMenuItem(text = { Text("Chat appearance") }, onClick = {
                                chatOptionsOpen = false
                                if (onEditAppearance != null) onEditAppearance() else showAppearance = true
                            })
                            onSchedule?.let { schedule -> DropdownMenuItem(text = { Text("Scheduled messages") }, enabled = editingMessage == null,
                                onClick = { chatOptionsOpen = false; schedule(composerText, replyingTo?.logicalMessageId) }) }
                            onDisappearing?.let { action -> DropdownMenuItem(text = { Text("Disappearing messages") }, onClick = { chatOptionsOpen = false; action() }) }
                            onOpenConnection?.let { action -> DropdownMenuItem(text = { Text("Connection") }, onClick = { chatOptionsOpen = false; action() }) }
                        }
                    }
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
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
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
                ChatMessageComposer(
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
                    onSendRecording = onFinishVoiceRecording,
                    showVoiceNote = true,
                    isSending = isSending,
                    onCameraClick = ::takePhoto
                )
            }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        modifier = modifier
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
        if (connectionSnapshot != null && onOpenConnection != null) {
            com.torxone.app.ui.components.ConnectionStatusBanner(connectionSnapshot, onOpenConnection)
        }
        val visibleError = uiError ?: permissionError
        if (visibleError != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(visibleError, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { permissionError = null; onDismissUiError() }) { Icon(Icons.Default.Close, "Dismiss error") }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val maximumBubbleWidth = minOf(maxWidth * 0.80f, 560.dp)
        val effectiveAppearance = chatAppearance?.let { config ->
            (appearance ?: ConversationAppearanceEntity("preview")).copy(
                bubbleStyle = if (config.bubbleStyle == "COMPACT") "SQUARE" else "ROUNDED")
        } ?: appearance
        val outgoingColor = chatAppearance?.effectiveAccentId?.let { id ->
            torXBrandColorScheme(MaterialTheme.colorScheme.surface.luminance() < 0.5f, TorXAccent.resolve(id)).primaryContainer
        }
        if (chatAppearance != null) ChatBackground(chatAppearance, Modifier.matchParentSize())
        else Box(Modifier.matchParentSize().background(when (appearance?.wallpaper) {
            "WARM" -> lerp(MaterialTheme.colorScheme.background, Color(0xFFC88845), 0.16f)
            "COOL" -> lerp(MaterialTheme.colorScheme.background, Color(0xFF397A9C), 0.16f)
            else -> MaterialTheme.colorScheme.background
        }))
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = if (hasEarlierMessages) 48.dp else 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 8.dp,
                start = 12.dp,
                end = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
        ) {
            itemsIndexed(
                items = timeline,
                key = { _, item -> item.message.logicalMessageId }
            ) { _, item ->
                val message = item.message
                if (item.dateLabel != null) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(item.dateLabel, Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (item.unreadCount > 0) Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("${item.unreadCount} unread messages", style = MaterialTheme.typography.labelMedium)
                    HorizontalDivider(Modifier.weight(1f))
                }
                if (item.startsGroup) Spacer(Modifier.height(5.dp))
                MessageBubble(
                    startsGroup = item.startsGroup,
                    endsGroup = item.endsGroup,
                    maximumWidth = maximumBubbleWidth,
                    message = message,
                    appearance = effectiveAppearance,
                    outgoingColor = outgoingColor,
                    contactName = contactName,
                    isHighlighted = message.logicalMessageId == highlightedMessageId || message.logicalMessageId in selectedIds,
                    onReply = { onReply(message) },
                    onRetryDelivery = onRetryDelivery,
                    onInspectFailure = { onRequestMessageInfo?.invoke(message) ?: run { selectedMessageForMenu = message } },
                    onLongClick = {
                        selectedMessageForMenu = message
                    },
                    onSelectionClick = if (selectedIds.isEmpty()) null else { {
                        selectedIds = if (message.logicalMessageId in selectedIds) selectedIds - message.logicalMessageId else selectedIds + message.logicalMessageId
                    } },
                    onMediaClick = { media ->
                        if (media.type == MediaType.IMAGE && media.localPath?.let { java.io.File(it).isFile } == true) viewerMedia = media
                        else if (media.type == MediaType.IMAGE) com.torxone.app.ui.components.openMedia(context, media,
                            onDownloadMedia?.let { download -> { download(media.mediaId) } })
                    },
                    onDownload = onDownloadMedia?.let { download -> { message.media?.let { download(it.mediaId) } } },
                    onCancelTransfer = onCancelMediaTransfer,
                    onPauseTransfer = onPauseMediaTransfer,
                    onResumeTransfer = onResumeMediaTransfer,
                    onQuoteClick = { targetMsgId ->
                        coroutineScope.launch {
                            val targetIndex = messageIndices[targetMsgId]
                            if (targetIndex != null) {
                                listState.animateScrollToItem(targetIndex)
                                highlightedMessageId = targetMsgId
                                delay(1200)
                                if (highlightedMessageId == targetMsgId) {
                                    highlightedMessageId = null
                                }
                            } else if (onJumpToMessage != null) {
                                pendingQuoteJumpId = targetMsgId
                                onJumpToMessage(targetMsgId)
                            }
                        }
                    },
                    onToggleReaction = { emoji ->
                        react(message.logicalMessageId, emoji)
                    }
                )
                if (message.logicalMessageId in starredIds) Text("Starred", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (hasEarlierMessages && onLoadEarlierMessages != null) TextButton(
            onClick = { followLatest = false; onLoadEarlierMessages() },
            modifier = Modifier.align(Alignment.TopCenter)) { Text("Load earlier messages") }
        if (!followLatest || !isLatestWindow) FilledTonalButton(onClick = {
            latestRequested = !isLatestWindow
            onJumpToLatest?.invoke()
            if (isLatestWindow && messages.isNotEmpty()) coroutineScope.launch {
                listState.animateScrollToItem(messages.lastIndex); followLatest = true; unreadSinceScroll = 0
            }
        }, modifier = Modifier.align(Alignment.BottomEnd).padding(
            end = 12.dp, bottom = innerPadding.calculateBottomPadding() + 12.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to latest")
            val count = maxOf(newerMessageCount, unreadSinceScroll)
            if (count > 0) Text(count.toString(), Modifier.padding(start = 4.dp))
        }
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
                        icon = Icons.Default.PhotoCamera,
                        label = "Camera",
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        showAttachmentMenu = false
                        takePhoto()
                    }

                    AttachmentOptionItem(
                        icon = Icons.Default.Image,
                        label = "Photo",
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        showAttachmentMenu = false
                        photoPickerLauncher.launch("image/*")
                    }

                    AttachmentOptionItem(
                        icon = Icons.Default.Videocam,
                        label = "Video",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        showAttachmentMenu = false
                        videoPickerLauncher.launch("video/*")
                    }

                    AttachmentOptionItem(
                        icon = Icons.Default.Description,
                        label = "Document",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        showAttachmentMenu = false
                        docPickerLauncher.launch("*/*")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AttachmentOptionItem(
                        icon = Icons.Default.Gif,
                        label = "GIF",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        showAttachmentMenu = false
                        gifPickerLauncher.launch("image/gif")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Full-screen Image / Media Viewer Dialog
    viewerMedia?.let { media -> com.torxone.app.ui.components.MediaViewer(media) { viewerMedia = null } }

    reactionPickerMessageId?.let { id -> EmojiReactionPicker(
        onDismiss = { reactionPickerMessageId = null },
        onSelected = { emoji -> if (messages.any { it.logicalMessageId == id && !it.isDeleted }) react(id, emoji) }
    ) }

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
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val quickEmojis = QuickReactions
                        for (emoji in quickEmojis) {
                            val userReacted = target.reactions.any { it.emoji == emoji && it.userReacted }
                            Surface(
                                shape = CircleShape,
                                color = if (userReacted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics {
                                        contentDescription = "React with ${reactionName(emoji)}"
                                        stateDescription = if (userReacted) "Selected" else "Not selected"
                                    }
                                    .clickable {
                                        selectedMessageForMenu = null
                                        react(target.logicalMessageId, emoji)
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(text = emoji, fontSize = 22.sp)
                                }
                            }
                        }
                        IconButton(onClick = {
                            reactionPickerMessageId = target.logicalMessageId
                            selectedMessageForMenu = null
                        }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Add, "More reactions") }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                if (!target.isDeleted && onSetStarred != null) ListItem(
                    headlineContent = { Text("Select message") },
                    leadingContent = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                    modifier = Modifier.clickable { selectedIds = selectedIds + target.logicalMessageId; selectedMessageForMenu = null })
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

                if (onRequestMessageInfo != null && (!isGroup || target.direction == MessageDirection.OUTGOING)) {
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    message: MessageUiModel,
    startsGroup: Boolean = true,
    endsGroup: Boolean = true,
    maximumWidth: androidx.compose.ui.unit.Dp = 310.dp,
    outgoingColor: Color? = null,
    appearance: ConversationAppearanceEntity?,
    contactName: String,
    isHighlighted: Boolean,
    onReply: () -> Unit,
    onRetryDelivery: (() -> Unit)? = null,
    onInspectFailure: () -> Unit,
    onLongClick: () -> Unit,
    onSelectionClick: (() -> Unit)? = null,
    onMediaClick: (MediaUiModel) -> Unit,
    onDownload: (() -> Unit)?,
    onCancelTransfer: ((String) -> Unit)?,
    onPauseTransfer: ((String) -> Unit)?,
    onResumeTransfer: ((String) -> Unit)?,
    onQuoteClick: (String) -> Unit,
    onToggleReaction: (String) -> Unit
) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var crossedReplyThreshold by remember { mutableStateOf(false) }
    var showReactions by remember { mutableStateOf(false) }
    var showMoreReactions by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val maximumDrag = with(LocalDensity.current) { 104.dp.toPx() }
    val returnedOffset by animateFloatAsState(dragOffsetX, spring(), label = "replyReturn")

    val baseBubbleColor = if (isOutgoing)
        outgoingColor ?: when (appearance?.theme) {
            "OCEAN" -> lerp(MaterialTheme.colorScheme.surface, Color(0xFF397A9C), 0.22f)
            "FOREST" -> lerp(MaterialTheme.colorScheme.surface, Color(0xFF43835A), 0.22f)
            else -> MaterialTheme.colorScheme.primaryContainer
        }
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
        dragging = true
        val resistance = if (dragOffsetX > threshold && delta > 0) 0.35f else 1f
        dragOffsetX = (dragOffsetX + delta * resistance).coerceIn(0f, maximumDrag)
        if (dragOffsetX >= threshold && !crossedReplyThreshold && !message.isDeleted) {
            crossedReplyThreshold = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset((if (dragging) dragOffsetX else returnedOffset).roundToInt(), 0) }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    if (dragOffsetX >= threshold && !message.isDeleted) {
                        onReply()
                    }
                    dragging = false
                    crossedReplyThreshold = false
                    dragOffsetX = 0f
                }
            )
    ) {
        if (dragOffsetX > 4f) Icon(Icons.AutoMirrored.Filled.Reply, "Reply", Modifier.align(Alignment.CenterStart).size(24.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = (dragOffsetX / threshold).coerceIn(0f, 1f)))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
        ) {
            Box {
            Surface(
                modifier = Modifier
                    .widthIn(max = maximumWidth)
                    .combinedClickable(
                        onClick = {
                            if (onSelectionClick != null) onSelectionClick() else message.media?.let { onMediaClick(it) }
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (message.isDeleted) onLongClick() else showReactions = true
                        },
                        onLongClickLabel = "React or open message actions"
                    ),
                shape = RoundedCornerShape(
                    topStart = if (appearance?.bubbleStyle == "SQUARE" || (!isOutgoing && !startsGroup)) 4.dp else 16.dp,
                    topEnd = if (appearance?.bubbleStyle == "SQUARE" || (isOutgoing && !startsGroup)) 4.dp else 16.dp,
                    bottomStart = if (appearance?.bubbleStyle == "SQUARE" || (!isOutgoing && !endsGroup)) 4.dp else 16.dp,
                    bottomEnd = if (appearance?.bubbleStyle == "SQUARE" || (isOutgoing && !endsGroup)) 4.dp else 16.dp
                ),
                color = animatedColor,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Sender display name (for group chats)
                    if (startsGroup && !isOutgoing && message.senderDisplayName != null && !message.isDeleted) {
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
                                tint = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Text(
                                text = if (message.expiresAt != null) "This message expired or was deleted" else "This message was deleted",
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        // Media Attachment View (if present)
                        message.media?.let { media ->
                            when (media.type) {
                                MediaType.IMAGE -> ImageBubbleView(media, isOutgoing)
                                MediaType.VOICE_NOTE -> VoiceNoteBubbleView(media, isOutgoing, onDownload)
                                MediaType.VIDEO -> VideoBubbleView(media, isOutgoing, onDownload)
                                MediaType.DOCUMENT -> DocumentBubbleView(media, isOutgoing, onDownload)
                                MediaType.AUDIO -> com.torxone.app.ui.components.AudioPlayback(media, Modifier.fillMaxWidth(), onDownload)
                            }
                            com.torxone.app.ui.components.MediaTransferControls(media,
                                onPause = onPauseTransfer, onResume = onResumeTransfer, onCancel = onCancelTransfer)
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // Text body (if not purely media or if has text caption)
                        if (message.media == null || (message.body != null && message.body != message.media.fileName)) {
                            Text(
                                text = remember(message.body) { com.torxone.app.ui.components.SafeRichText.render(message.body.orEmpty()) },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
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
                                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        if (message.expiresAt != null && !message.isDeleted) {
                            Icon(Icons.Default.Timer, contentDescription = "Disappears at ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(message.expiresAt))}",
                                modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (endsGroup) Text(
                            text = formatMessageTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )

                        if (isOutgoing && !message.isDeleted) {
                            DeliveryStatusIcon(
                                status = message.status,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            DropdownMenu(expanded = showReactions, onDismissRequest = { showReactions = false }) {
                FlowRow(Modifier.widthIn(max = 320.dp).padding(horizontal = 4.dp)) {
                    QuickReactions.forEach { emoji ->
                        IconButton(onClick = { showReactions = false; onToggleReaction(emoji) }, modifier = Modifier.size(48.dp).semantics {
                                contentDescription = "React with ${reactionName(emoji)}"
                            }) {
                            Text(emoji, fontSize = 22.sp)
                        }
                    }
                    IconButton(onClick = { showReactions = false; showMoreReactions = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "More reactions")
                    }
                }
                DropdownMenuItem(text = { Text("More actions") }, onClick = { showReactions = false; onLongClick() })
            }
            }
            if (showMoreReactions && !message.isDeleted) EmojiReactionPicker(
                onDismiss = { showMoreReactions = false }, onSelected = onToggleReaction)
            if (isOutgoing && message.status == DeliveryStatus.FAILED && !message.isDeleted) {
                TextButton(onClick = onInspectFailure, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Couldn't send. View details.", color = MaterialTheme.colorScheme.error)
                }
            } else if (isOutgoing && message.status in setOf(DeliveryStatus.WAITING_FOR_PEER, DeliveryStatus.RETRY_WAIT) &&
                !message.isDeleted && onRetryDelivery != null) {
                TextButton(onClick = onRetryDelivery, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry delivery") }
            }
            // Emoji Reaction Pills
            if (message.reactions.isNotEmpty() && !message.isDeleted) {
                FlowRow(
                    modifier = Modifier
                        .widthIn(max = maximumWidth)
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
    val animatedDrawable by produceState<AnimatedImageDrawable?>(null, media.localPath, media.mimeType) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            media.mimeType.equals("image/gif", ignoreCase = true) &&
            media.localPath != null
        ) {
            runCatching {
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(java.io.File(media.localPath))
                ) { decoder, info, _ ->
                    val scale = minOf(1f, 1024f / maxOf(info.size.width, info.size.height).coerceAtLeast(1))
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1))
                } as? AnimatedImageDrawable
            }.getOrNull()
        } else null }
    }
    val thumbnailReader = LocalChatThumbnailReader.current
    val imageBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, media.mediaId, media.thumbnailData, media.localPath, thumbnailReader) {
        suspend fun decode(bytes: ByteArray?) {
            value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { LocalImagePreview.message(bytes, media.localPath)?.asImageBitmap() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
        }
        if (media.thumbnailData != null || thumbnailReader == null) decode(media.thumbnailData)
        else thumbnailReader(media.mediaId).collect { decode(it) }
    }

    val aspect = imageBitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
        ?: animatedDrawable?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight.coerceAtLeast(1) }
        ?: (4f / 3f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((maxWidth.value / aspect.coerceAtLeast(0.01f)).coerceIn(80f, 360f).dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && animatedDrawable != null) {
                animatedDrawable?.let { AnimatedGifView(it) }
            } else if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap!!,
                    contentDescription = media.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${media.fileSize / 1024} KB",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
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
}

@RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun AnimatedGifView(drawable: AnimatedImageDrawable) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(drawable, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) drawable.stop()
            else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) drawable.start()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); drawable.stop() }
    }
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(drawable)
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) drawable.start()
            }
        },
        update = { view ->
            if (view.drawable !== drawable) view.setImageDrawable(drawable)
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                if (!drawable.isRunning) drawable.start()
            } else drawable.stop()
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun VoiceNoteBubbleView(media: MediaUiModel, isOutgoing: Boolean, onDownload: (() -> Unit)?) {
    com.torxone.app.ui.components.AudioPlayback(media, Modifier.fillMaxWidth(), onDownload)
}

@Composable
private fun VideoBubbleView(media: MediaUiModel, isOutgoing: Boolean, onDownload: (() -> Unit)?) {
    var viewing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (viewing) com.torxone.app.ui.components.MediaViewer(media) { viewing = false }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clickable {
                if (media.localPath?.let { java.io.File(it).isFile } == true) viewing = true
                else com.torxone.app.ui.components.openMedia(context, media, onDownload)
            }
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
                if (media.status == MediaStatus.DOWNLOADING) CircularProgressIndicator(progress = { media.progress }, color = Color.White)
                else Icon(if (media.localPath != null) Icons.Default.PlayArrow else Icons.Default.Download,
                    contentDescription = if (media.localPath != null) "Play video" else "Download video", tint = Color.White, modifier = Modifier.size(32.dp))
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
private fun DocumentBubbleView(media: MediaUiModel, isOutgoing: Boolean, onDownload: (() -> Unit)?) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth().clickable { com.torxone.app.ui.components.openMedia(context, media, onDownload) }
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
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
                    color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${media.fileSize / 1024} KB • ${media.status.name.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                if (media.status == MediaStatus.COMPLETE) Icons.Default.Check else Icons.Default.HourglassEmpty,
                contentDescription = null,
                tint = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
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
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = "${reaction.emoji}, ${reaction.count} reactions"
                stateDescription = if (reaction.userReacted) "Your reaction; tap to remove" else "Tap to react"
            }
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
                        if (isOutgoingBubble) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(2.dp)
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = quoted.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isOutgoingBubble) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = quoted.previewText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isOutgoingBubble) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DeliveryStatusIcon(
    status: DeliveryStatus,
    tint: Color
) {
    val presentation = DeliveryPresentation.from(status)
    val isRead = presentation.read
    val icon = when (presentation.glyph) {
        DeliveryGlyph.WAITING -> Icons.Default.Schedule
        DeliveryGlyph.SENT -> Icons.Default.Done
        DeliveryGlyph.RECEIVED -> Icons.Default.DoneAll
        DeliveryGlyph.ERROR -> Icons.Default.ErrorOutline
        DeliveryGlyph.EXPIRED -> Icons.Default.TimerOff
    }


    Icon(
        imageVector = icon,
        contentDescription = presentation.label,
        modifier = Modifier.size(14.dp),
        tint = if (isRead)
            MaterialTheme.colorScheme.readReceipt
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
    return com.torxone.app.chat.PresenceFormatter.formatLastSeen(timestamp)
}

private fun resolveMediaFileName(context: android.content.Context, uri: android.net.Uri, defaultName: String): String {
    return try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) cursor.getString(index) else defaultName
            } else defaultName
        } ?: defaultName
    } catch (_: Exception) {
        defaultName
    }
}

private suspend fun readUriWithLimit(
    context: android.content.Context,
    uri: android.net.Uri,
    maxBytes: Int = 32 * 1024 * 1024,
    onError: (String) -> Unit = {}
): ByteArray? {
    return try {
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= maxBytes) { "Attachment exceeds ${maxBytes / (1024 * 1024)} MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().also { require(it.isNotEmpty()) { "Selected attachment is empty" } }
            } ?: error("Cannot read selected attachment")
        }
        bytes
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            onError(error.message ?: "Cannot read attachment")
        }
        null
    }
}

private fun reactionName(emoji: String): String = when (emoji) {
    "👍" -> "thumbs up"; "❤️" -> "heart"; "😂" -> "laughing"; "😮" -> "surprised"
    "😢" -> "sad"; "🙏" -> "thanks"; "🎉" -> "celebration"; "🔥" -> "fire"; "👏" -> "applause"
    "✅" -> "check mark"; "💯" -> "hundred points"; "🤔" -> "thinking"; else -> emoji
}
