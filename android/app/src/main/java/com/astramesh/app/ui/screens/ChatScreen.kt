package com.astramesh.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.engine.MessageLifecycleState
import com.astramesh.app.engine.MessagePayload
import com.astramesh.app.network.MessageRouter
import com.astramesh.app.network.NearbyConnectionManager
import com.astramesh.app.transfer.MediaTransferManager
import com.astramesh.app.ui.components.ConnectionStatusPill
import com.astramesh.app.ui.components.MediaContent
import com.astramesh.app.ui.components.TransportType
import com.astramesh.app.ui.screens.chat.ChatViewModel
import com.astramesh.app.ui.screens.chat.SmartScrollEngine
import com.astramesh.app.ui.theme.AstraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import kotlin.math.roundToInt

private val ReactionChoices = listOf(
    "\u2764\uFE0F",
    "\uD83D\uDC4D",
    "\uD83D\uDE02",
    "\uD83D\uDE2E",
    "\uD83D\uDE22",
    "\uD83D\uDD25",
    "\uD83D\uDC4F"
)

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
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val viewModel = remember(contactKey) { ChatViewModel(contactKey, db, messageRouter) }

    DisposableEffect(contactKey, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> {
                    com.astramesh.app.service.ActiveConversationTracker.setActive(contactKey)
                    com.astramesh.app.service.NotificationHelper.clearContactNotifications(context, contactKey)
                }
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> {
                    com.astramesh.app.service.ActiveConversationTracker.clear(contactKey)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            com.astramesh.app.service.ActiveConversationTracker.setActive(contactKey)
            com.astramesh.app.service.NotificationHelper.clearContactNotifications(context, contactKey)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            com.astramesh.app.service.ActiveConversationTracker.clear(contactKey)
        }
    }

    val contactName by viewModel.contactName.collectAsStateWithLifecycle()
    val contactEndpoint by viewModel.contactEndpoint.collectAsStateWithLifecycle()
    val contactOnion by viewModel.contactOnion.collectAsStateWithLifecycle()
    val connectedEndpoints by nearbyManager.connectedEndpoints.collectAsStateWithLifecycle()
    val messages by viewModel.conversationEngine.messages.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchEngine.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchEngine.searchResults.collectAsStateWithLifecycle()
    val currentResultIndex by viewModel.searchEngine.currentResultIndex.collectAsStateWithLifecycle()
    val presenceStates by (com.astramesh.app.service.AstraMeshService.getInstance()
        ?.presenceManager
        ?.presence
        ?: kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.astramesh.app.presence.PresenceState>>(emptyMap())).collectAsStateWithLifecycle()
    val lastSeenStates by (com.astramesh.app.service.AstraMeshService.getInstance()
        ?.presenceManager
        ?.lastSeen
        ?: kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())).collectAsStateWithLifecycle()
    val livePresenceLabel = presenceStates[contactKey]?.label ?: lastSeenStates[contactKey]?.let { formatLastSeenLabel(it) }
    val conversationPresence = presenceStates[contactKey]
    val typingLabel = conversationPresence?.label?.takeIf { conversationPresence.activity == "typing" }

    val listState = rememberLazyListState()
    val smartScrollEngine = remember(listState) { SmartScrollEngine(listState, scope) }
    val isAtNewest by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 }
    }

    var text by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<MessagePayload?>(null) }
    var reactionTarget by remember { mutableStateOf<MessagePayload?>(null) }
    var reactionDetailsTarget by remember { mutableStateOf<MessagePayload?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var searchMode by remember { mutableStateOf(false) }
    var highlightedId by remember { mutableStateOf<String?>(null) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showVoiceRecorder by remember { mutableStateOf(false) }
    var structuredAttachment by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingVideoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraAction by remember { mutableStateOf<String?>(null) }
    var pendingAudioAction by remember { mutableStateOf<String?>(null) }

    val reversedMessages = remember(messages) { messages.asReversed() }
    val isNearbyOnline = connectedEndpoints.contains(contactEndpoint)
    val isConnected = isNearbyOnline || contactOnion.isNotBlank()
    val inSelectionMode = selectedIds.isNotEmpty()

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val messageType = when {
            mimeType.startsWith("image/") -> "IMAGE"
            mimeType.startsWith("video/") -> "VIDEO"
            mimeType.startsWith("audio/") -> "AUDIO"
            mimeType == "application/vnd.android.package-archive" -> "APK"
            else -> "DOCUMENT"
        }
        scope.launch {
            val queuedId = mediaTransferManager.queueMediaTransfer(contactKey, uri, mimeType, messageType)
            Toast.makeText(context, if (queuedId != null) "Attachment queued" else "Could not attach file", Toast.LENGTH_SHORT).show()
        }
    }

    fun queueMediaUri(uri: Uri, mimeType: String, messageType: String) {
        scope.launch {
            sendPresence(contactKey, "uploading", "Uploading ${messageType.lowercase()}...")
            val queuedId = mediaTransferManager.queueMediaTransfer(contactKey, uri, mimeType, messageType)
            Toast.makeText(context, if (queuedId != null) "$messageType queued" else "Could not send $messageType", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            queueMediaUri(uri, "image/jpeg", "IMAGE")
        }
        pendingCameraUri = null
    }
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val uri = pendingVideoUri
        if (success && uri != null) {
            queueMediaUri(uri, "video/mp4", "VIDEO")
        }
        pendingVideoUri = null
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            when (pendingCameraAction) {
                "video" -> {
                    sendPresence(contactKey, "recording_video", "Recording video...")
                    val uri = createTempMediaUri(context, "video", ".mp4")
                    pendingVideoUri = uri
                    videoLauncher.launch(uri)
                }
                else -> {
                    sendPresence(contactKey, "taking_photo", "Taking photo...")
                    val uri = createTempMediaUri(context, "camera", ".jpg")
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                }
            }
            pendingCameraAction = null
        } else {
            pendingCameraAction = null
            Toast.makeText(context, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAudioAction
        pendingAudioAction = null
        if (!granted) {
            Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        when (action) {
            "call" -> {
                sendPresence(contactKey, "in_call", "In call", ttlMs = 30_000L)
                val service = com.astramesh.app.service.AstraMeshService.getInstance()
                service?.callManager?.startAudioCall(contactKey)
                    ?: Toast.makeText(context, "Call service not ready", Toast.LENGTH_SHORT).show()
            }
            else -> showVoiceRecorder = true
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            sendCurrentLocation(context, viewModel)
        } else {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(messages.size) {
        val lastMessage = messages.lastOrNull()
        if (lastMessage != null && !searchMode) {
            smartScrollEngine.onNewMessageArrived(lastMessage.senderId == "me")
        }
    }

    LaunchedEffect(isAtNewest, unreadCount, messages.size) {
        if (isAtNewest && unreadCount > 0) {
            viewModel.markVisibleMessagesRead()
        }
    }

    LaunchedEffect(currentResultIndex, searchResults) {
        if (currentResultIndex >= 0 && searchResults.isNotEmpty()) {
            val targetId = searchResults[currentResultIndex]
            val index = reversedMessages.indexOfFirst { it.id == targetId }
            if (index >= 0) {
                listState.animateScrollToItem(index)
                highlightedId = targetId
            }
        }
    }

    LaunchedEffect(highlightedId) {
        if (highlightedId != null) {
            delay(1000)
            highlightedId = null
        }
    }

    LaunchedEffect(text) {
        if (text.isNotBlank()) {
            delay(450)
            if (text.isNotBlank()) {
                sendPresence(contactKey, "typing", "Typing...")
            }
        }
    }

    LaunchedEffect(showVoiceRecorder) {
        if (showVoiceRecorder) {
            sendPresence(contactKey, "recording_voice", "Recording voice...")
        }
    }

    LaunchedEffect(contactKey) {
        while (true) {
            sendPresence(contactKey, "online", "Online", ttlMs = 30_000L)
            delay(20_000L)
        }
    }

    DisposableEffect(contactKey) {
        onDispose {
            sendPresence(contactKey, "offline", "Last seen just now", ttlMs = 1_000L)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        modifier = Modifier.imePadding(),
        topBar = {
            ChatHeader(
                contactName = contactName,
                isConnected = isConnected,
                isNearbyOnline = isNearbyOnline,
                contactEndpoint = contactEndpoint,
                contactOnion = contactOnion,
                livePresenceLabel = livePresenceLabel,
                searchMode = searchMode,
                searchQuery = searchQuery,
                searchCountLabel = if (searchResults.isEmpty()) "" else "${currentResultIndex + 1}/${searchResults.size}",
                selectedCount = selectedIds.size,
                onBack = {
                    when {
                        inSelectionMode -> selectedIds = emptySet()
                        searchMode -> {
                            searchMode = false
                            viewModel.searchEngine.clearSearch()
                        }
                        else -> navController.popBackStack()
                    }
                },
                onSearch = { searchMode = true },
                onSearchChange = { viewModel.searchEngine.updateQuery(it) },
                onPreviousResult = { viewModel.searchEngine.previousResult() },
                onNextResult = { viewModel.searchEngine.nextResult() },
                onCopy = {
                    copyMessages(context, messages, selectedIds)
                    selectedIds = emptySet()
                },
                onReply = {
                    replyTo = messages.firstOrNull { it.id == selectedIds.firstOrNull() }
                    selectedIds = emptySet()
                },
                onDelete = {
                    viewModel.deleteMessages(selectedIds)
                    selectedIds = emptySet()
                },
                onOpenProfile = {
                    navController.navigate("contact_profile/$contactKey")
                },
                onVoiceCall = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        sendPresence(contactKey, "in_call", "In call", ttlMs = 30_000L)
                        val service = com.astramesh.app.service.AstraMeshService.getInstance()
                        service?.callManager?.startAudioCall(contactKey)
                            ?: Toast.makeText(context, "Call service not ready", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingAudioAction = "call"
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onVideoCall = {
                    sendPresence(contactKey, "in_video_call", "In video call", ttlMs = 30_000L)
                    Toast.makeText(context, "Audio call first. Video layer will be added after audio is stable.", Toast.LENGTH_LONG).show()
                }
            )
        },
        bottomBar = {
            ChatComposer(
                text = text,
                replyTo = replyTo,
                onTextChange = { text = it },
                onCancelReply = { replyTo = null },
                onOpenAttachmentSheet = { showAttachmentSheet = true },
                onCamera = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        sendPresence(contactKey, "taking_photo", "Taking photo...")
                        val uri = createTempMediaUri(context, "camera", ".jpg")
                        pendingCameraUri = uri
                        cameraLauncher.launch(uri)
                    } else {
                        pendingCameraAction = "photo"
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onVoice = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        showVoiceRecorder = true
                    } else {
                        pendingAudioAction = "voice"
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onSend = {
                    val outgoing = text.trim()
                    if (outgoing.isNotEmpty()) {
                        viewModel.sendMessage(outgoing, replyTo?.id)
                        text = ""
                        replyTo = null
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    com.astramesh.app.ui.components.AstraLoadingState(message = "Loading messages...")
                }
                messages.isEmpty() -> {
                    com.astramesh.app.ui.components.AstraEmptyState(
                        title = "No messages yet",
                        message = "Start a secure conversation with $contactName"
                    )
                }
                else -> {
                    MessageTimeline(
                        messages = reversedMessages,
                        listState = listState,
                        selectedIds = selectedIds,
                        highlightedId = highlightedId,
                        typingLabel = typingLabel,
                        onSelectToggle = { message ->
                            selectedIds = if (selectedIds.contains(message.id)) {
                                selectedIds - message.id
                            } else {
                                selectedIds + message.id
                            }
                        },
                        onReply = { replyTo = it },
                        onLongPress = { reactionTarget = it },
                        onReactionDetails = { reactionDetailsTarget = it },
                        onReplyClick = { replyId ->
                            val index = reversedMessages.indexOfFirst { it.id == replyId }
                            if (index >= 0) {
                                scope.launch {
                                    listState.animateScrollToItem(index)
                                    highlightedId = replyId
                                }
                            }
                        },
                        onFailedTap = { failed ->
                            if (failed.text.isNotBlank()) {
                                viewModel.sendMessage(failed.text, failed.replyToId)
                                Toast.makeText(context, "Retry queued", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onMediaPresence = { activity, label ->
                            sendPresence(contactKey, activity, label)
                        }
                    )
                }
            }

            val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }
            AnimatedVisibility(
                visible = showJump,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(0)
                            viewModel.markVisibleMessagesRead()
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Jump to latest")
                }
            }
            AnimatedVisibility(
                visible = unreadCount > 0 && !isAtNewest,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
            ) {
                Surface(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(0)
                            viewModel.markVisibleMessagesRead()
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Text(
                        text = if (unreadCount == 1) "1 New Message" else "$unreadCount New Messages",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    reactionTarget?.let { target ->
        ReactionDialog(
            message = target,
            onDismiss = { reactionTarget = null },
            onReaction = { emoji ->
                viewModel.toggleReaction(target.id, emoji)
                reactionTarget = null
            }
        )
    }

    reactionDetailsTarget?.let { target ->
        ReactionDetailsDialog(
            message = target,
            contactKey = contactKey,
            contactName = contactName,
            onDismiss = { reactionDetailsTarget = null }
        )
    }

    if (showAttachmentSheet) {
        AttachmentSheet(
            onDismiss = { showAttachmentSheet = false },
            onPick = { title, mimeType ->
                showAttachmentSheet = false
                when (title) {
                    "GIF" -> sendPresence(contactKey, "choosing_gif", "Choosing GIF...")
                    "Sticker" -> sendPresence(contactKey, "choosing_sticker", "Choosing sticker...")
                    "Gallery" -> sendPresence(contactKey, "choosing_media", "Choosing media...")
                    "Audio" -> sendPresence(contactKey, "choosing_audio", "Choosing audio...")
                    "Document", "Mesh File" -> sendPresence(contactKey, "choosing_file", "Choosing file...")
                }
                attachmentPicker.launch(mimeType)
            },
            onAction = { title ->
                showAttachmentSheet = false
                when (title) {
                    "Camera" -> {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            sendPresence(contactKey, "taking_photo", "Taking photo...")
                            val uri = createTempMediaUri(context, "camera", ".jpg")
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        } else {
                            pendingCameraAction = "photo"
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                    "Record Video" -> {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            sendPresence(contactKey, "recording_video", "Recording video...")
                            val uri = createTempMediaUri(context, "video", ".mp4")
                            pendingVideoUri = uri
                            videoLauncher.launch(uri)
                        } else {
                            pendingCameraAction = "video"
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                    "Voice Note" -> {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            showVoiceRecorder = true
                        } else {
                            pendingAudioAction = "voice"
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    "Location" -> {
                        sendPresence(contactKey, "sharing_location", "Sharing location...")
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            sendCurrentLocation(context, viewModel)
                        } else {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    }
                    "Contact" -> shareOwnContact(viewModel)
                    "Share Device" -> shareDeviceInfo(viewModel)
                    "Poll", "Code Snippet", "Calendar" -> structuredAttachment = title
                    else -> attachmentPicker.launch("*/*")
                }
            }
        )
    }

    structuredAttachment?.let { title ->
        StructuredAttachmentDialog(
            title = title,
            onDismiss = { structuredAttachment = null },
            onSend = { heading, body ->
                viewModel.sendMessage(formatStructuredAttachment(title, heading, body))
                structuredAttachment = null
            }
        )
    }

    if (showVoiceRecorder) {
        VoiceRecorderDialog(
            onDismiss = { showVoiceRecorder = false },
            onSend = { uri ->
                showVoiceRecorder = false
                queueMediaUri(uri, "audio/mp4", "VOICE")
            }
        )
    }
}

@Composable
private fun ChatHeader(
    contactName: String,
    isConnected: Boolean,
    isNearbyOnline: Boolean,
    contactEndpoint: String,
    contactOnion: String,
    livePresenceLabel: String?,
    searchMode: Boolean,
    searchQuery: String,
    searchCountLabel: String,
    selectedCount: Int,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onSearchChange: (String) -> Unit,
    onPreviousResult: () -> Unit,
    onNextResult: () -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onOpenProfile: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
                .padding(start = 4.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }

            Crossfade(targetState = when {
                selectedCount > 0 -> "selection"
                searchMode -> "search"
                else -> "normal"
            }, label = "chatHeader") { mode ->
                when (mode) {
                    "selection" -> SelectionHeader(selectedCount, onCopy, onReply, onDelete)
                    "search" -> SearchHeader(searchQuery, searchCountLabel, onSearchChange, onPreviousResult, onNextResult)
                    else -> NormalHeader(
                        contactName = contactName,
                        isConnected = isConnected,
                        isNearbyOnline = isNearbyOnline,
                        contactEndpoint = contactEndpoint,
                        contactOnion = contactOnion,
                        livePresenceLabel = livePresenceLabel,
                        onOpenProfile = onOpenProfile,
                        onSearch = onSearch,
                        onVoiceCall = onVoiceCall,
                        onVideoCall = onVideoCall
                    )
                }
            }
        }
    }
}

@Composable
private fun NormalHeader(
    contactName: String,
    isConnected: Boolean,
    isNearbyOnline: Boolean,
    contactEndpoint: String,
    contactOnion: String,
    livePresenceLabel: String?,
    onOpenProfile: () -> Unit,
    onSearch: () -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenProfile)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contactName.firstOrNull()?.uppercase() ?: "A",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenProfile)
                .padding(end = 4.dp)
        ) {
            Text(
                text = contactName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    !livePresenceLabel.isNullOrBlank() -> livePresenceLabel
                    isNearbyOnline -> "Bluetooth Relay • Encrypted"
                    contactOnion.isNotBlank() -> "Online • Encrypted"
                    isConnected -> "Mesh Connected • Encrypted"
                    else -> "Offline • Encrypted"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onSearch, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Search, contentDescription = "Search conversation")
        }
        IconButton(onClick = onVoiceCall, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Call, contentDescription = "Voice call")
        }
        IconButton(onClick = onVideoCall, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Videocam, contentDescription = "Video call")
        }
        IconButton(onClick = { }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Conversation menu")
        }
    }
}

@Composable
private fun SearchHeader(
    query: String,
    countLabel: String,
    onSearchChange: (String) -> Unit,
    onPreviousResult: () -> Unit,
    onNextResult: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onSearchChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search conversation") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        if (countLabel.isNotBlank()) {
            Text(countLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = onPreviousResult) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Previous")
            }
            IconButton(onClick = onNextResult) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Next")
            }
        }
    }
}

@Composable
private fun SelectionHeader(
    count: Int,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onCopy) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy")
        }
        IconButton(onClick = onReply, enabled = count == 1) {
            Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = "Reply")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, contentDescription = "Delete")
        }
    }
}

@Composable
private fun MessageTimeline(
    messages: List<MessagePayload>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectedIds: Set<String>,
    highlightedId: String?,
    typingLabel: String?,
    onSelectToggle: (MessagePayload) -> Unit,
    onReply: (MessagePayload) -> Unit,
    onLongPress: (MessagePayload) -> Unit,
    onReactionDetails: (MessagePayload) -> Unit,
    onReplyClick: (String) -> Unit,
    onFailedTap: (MessagePayload) -> Unit,
    onMediaPresence: (String, String) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp)
    ) {
        if (typingLabel != null) {
            item(key = "typing_indicator") {
                TypingIndicatorBubble(label = typingLabel)
            }
        }
        itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
            val nextOlder = messages.getOrNull(index + 1)
            val nextNewer = messages.getOrNull(index - 1)
            val sameSenderAsOlder = nextOlder?.senderId == message.senderId
            val sameSenderAsNewer = nextNewer?.senderId == message.senderId
            val date = dateFormat.format(Date(message.timestamp))
            val olderDate = nextOlder?.let { dateFormat.format(Date(it.timestamp)) }
            val topGap = if (sameSenderAsOlder) 8.dp else 16.dp

            Column(modifier = Modifier.fillMaxWidth()) {
                if (date != olderDate) {
                    DatePill(date)
                    Spacer(Modifier.height(8.dp))
                }
                SwipeReplyMessage(
                    message = message,
                    showSenderName = !sameSenderAsOlder && message.senderId != "me",
                    compactWithPrevious = sameSenderAsOlder,
                    compactWithNext = sameSenderAsNewer,
                    isSelected = selectedIds.contains(message.id),
                    isHighlighted = highlightedId == message.id,
                    topPadding = topGap,
                    onSelectToggle = { onSelectToggle(message) },
                    onReply = { onReply(message) },
                    onLongPress = { onLongPress(message) },
                    onReactionDetails = { onReactionDetails(message) },
                    onReplyClick = onReplyClick,
                    onFailedTap = { onFailedTap(message) },
                    onMediaPresence = onMediaPresence
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeReplyMessage(
    message: MessagePayload,
    showSenderName: Boolean,
    compactWithPrevious: Boolean,
    compactWithNext: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean,
    topPadding: androidx.compose.ui.unit.Dp,
    onSelectToggle: () -> Unit,
    onReply: () -> Unit,
    onLongPress: () -> Unit,
    onReactionDetails: () -> Unit,
    onReplyClick: (String) -> Unit,
    onFailedTap: () -> Unit,
    onMediaPresence: (String, String) -> Unit
) {
    val isMine = message.senderId == "me"
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val dragOffset = remember(message.id) { Animatable(0f) }
    var triggered by remember(message.id) { mutableStateOf(false) }
    val threshold = 82f
    val maxDrag = 118f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .pointerInput(message.id, isMine) {
                detectHorizontalDragGestures(
                    onDragStart = { triggered = false },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val next = (dragOffset.value + dragAmount).coerceIn(-maxDrag, maxDrag)
                        val allowed = if (isMine) next <= 0f else next >= 0f
                        if (allowed) {
                            scope.launch { dragOffset.snapTo(next) }
                            if (!triggered && kotlin.math.abs(next) > threshold) {
                                triggered = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    },
                    onDragEnd = {
                        if (kotlin.math.abs(dragOffset.value) > threshold) onReply()
                        scope.launch {
                            dragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            dragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    }
                )
            }
    ) {
        val iconAlpha = (kotlin.math.abs(dragOffset.value) / threshold).coerceIn(0f, 1f)
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = iconAlpha),
            modifier = Modifier
                .align(if (isMine) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 18.dp)
                .scale(0.8f + iconAlpha * 0.25f)
        )
        MessageBubble(
            message = message,
            isMine = isMine,
            showSenderName = showSenderName,
            compactWithPrevious = compactWithPrevious,
            compactWithNext = compactWithNext,
            isSelected = isSelected,
            isHighlighted = isHighlighted,
            onClick = {
                if (message.lifecycleState == MessageLifecycleState.FAILED) onFailedTap() else onSelectToggle()
            },
            onLongPress = onLongPress,
            onReactionDetails = onReactionDetails,
            onReplyClick = onReplyClick,
            onMediaPresence = onMediaPresence,
            modifier = Modifier.offset { IntOffset(dragOffset.value.roundToInt(), 0) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessagePayload,
    isMine: Boolean,
    showSenderName: Boolean,
    compactWithPrevious: Boolean,
    compactWithNext: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onReactionDetails: () -> Unit,
    onReplyClick: (String) -> Unit,
    onMediaPresence: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val maxBubbleWidth = (config.screenWidthDp * 0.72f).dp
    val bubbleColor by animateColorAsState(
        targetValue = when {
            isHighlighted -> MaterialTheme.colorScheme.tertiaryContainer
            isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            isMine -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "bubbleColor"
    )
    val textColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = bubbleShape(isMine, compactWithPrevious, compactWithNext)
    val reactions = remember(message.reactions) {
        message.reactions.values
            .flatten()
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
    }
    val pressScale by animateFloatAsState(if (isSelected) 0.98f else 1f, label = "bubbleScale")

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
            if (showSenderName) {
                Text(
                    text = "Astra contact",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                )
            }
            Surface(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .scale(pressScale)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongPress,
                        onDoubleClick = onLongPress
                    ),
                color = bubbleColor,
                shape = shape,
                tonalElevation = if (isMine) 4.dp else 2.dp,
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                    if (message.replyToId != null) {
                        InlineReplyPreview(
                            isMine = isMine,
                            sender = message.replyToSender.replySenderLabel(isMine),
                            type = message.replyToType ?: "TEXT",
                            preview = message.replyToText ?: "Original message unavailable",
                            onClick = { onReplyClick(message.replyToId) }
                        )
                        Spacer(Modifier.height(7.dp))
                    }
                    if (message.hasAttachments) {
                        MediaContent(
                            message = message,
                            isSent = isMine,
                            onPresence = { activity, label ->
                                if (!isMine) onMediaPresence(activity, label)
                            }
                        )
                        if (message.text.isNotBlank() && !message.text.startsWith("Media Message")) {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (message.text.isNotBlank() && !message.text.startsWith("Media Message") && !message.text.startsWith("Receiving ")) {
                        Text(
                            text = com.astramesh.app.ui.utils.TextUtils.parseMarkdown(
                                message.text,
                                codeColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            ),
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 23.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (message.isEncrypted) {
                            Icon(Icons.Rounded.Security, contentDescription = "Encrypted", modifier = Modifier.size(12.dp), tint = textColor.copy(alpha = 0.52f))
                        }
                        Text(
                            text = timeFormat.format(Date(message.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.62f)
                        )
                        if (isMine) MessageStatusIcon(message.lifecycleState, textColor.copy(alpha = 0.7f))
                    }
                }
            }
            if (reactions.isNotEmpty()) {
                ReactionCapsules(reactions = reactions, isMine = isMine, onClick = onReactionDetails)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineReplyPreview(
    isMine: Boolean,
    sender: String,
    type: String,
    preview: String,
    onClick: () -> Unit
) {
    val accent = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f))
            .combinedClickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "$sender • ${type.replyTypeLabel()}",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TypingIndicatorBubble(label: String) {
    val transition = rememberInfiniteTransition(label = "typingDots")
    val dotAlphas = List(3) { index ->
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520, delayMillis = index * 120),
                repeatMode = RepeatMode.Reverse
            ),
            label = "typingDot$index"
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp, 22.dp, 22.dp, 7.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp,
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .alpha(dotAlphas[index].value)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

private fun String?.replySenderLabel(isCurrentMessageMine: Boolean): String {
    return when (this) {
        null -> "Original"
        "me" -> if (isCurrentMessageMine) "You" else "Astra contact"
        else -> if (isCurrentMessageMine) "Astra contact" else "You"
    }
}

private fun String.replyTypeLabel(): String {
    return when (uppercase()) {
        "TEXT" -> "Message"
        "IMAGE" -> "Image"
        "VIDEO" -> "Video"
        "AUDIO" -> "Audio"
        "VOICE" -> "Voice note"
        "STICKER" -> "Sticker"
        "GIF" -> "GIF"
        "DOCUMENT" -> "Document"
        "CONTACT" -> "Contact"
        "POLL" -> "Poll"
        else -> lowercase().replaceFirstChar { it.titlecase() }
    }
}

@Composable
private fun MessageStatusIcon(state: MessageLifecycleState, tint: Color) {
    val text = when (state) {
        MessageLifecycleState.DRAFT,
        MessageLifecycleState.QUEUED,
        MessageLifecycleState.ENCRYPTING,
        MessageLifecycleState.SENDING,
        MessageLifecycleState.TRANSPORT_SELECTED,
        MessageLifecycleState.RETRYING -> "\u2026"
        MessageLifecycleState.IN_TRANSIT -> "\u2713"
        MessageLifecycleState.DELIVERED -> "\u2713\u2713"
        MessageLifecycleState.READ -> "\u2713\u2713"
        MessageLifecycleState.FAILED,
        MessageLifecycleState.CANCELLED,
        MessageLifecycleState.EXPIRED -> "!"
        MessageLifecycleState.ARCHIVED -> "\u2713"
    }
    Crossfade(targetState = text, label = "status") { label ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when (state) {
                MessageLifecycleState.FAILED,
                MessageLifecycleState.CANCELLED,
                MessageLifecycleState.EXPIRED -> MaterialTheme.colorScheme.error
                MessageLifecycleState.READ -> MaterialTheme.colorScheme.primary
                else -> tint
            },
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ReactionCapsules(reactions: Map<String, Int>, isMine: Boolean, onClick: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp, start = if (isMine) 0.dp else 8.dp, end = if (isMine) 8.dp else 0.dp)
    ) {
        reactions.forEach { (emoji, count) ->
            val scale by animateFloatAsState(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "reactionCapsule$emoji"
            )
            Surface(
                modifier = Modifier
                    .scale(scale)
                    .clickable(onClick = onClick),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Text(
                    text = if (count > 1) "$emoji $count" else emoji,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun DatePill(date: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
            tonalElevation = 3.dp
        ) {
            Text(
                text = date,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatComposer(
    text: String,
    replyTo: MessagePayload?,
    onTextChange: (String) -> Unit,
    onCancelReply: () -> Unit,
    onOpenAttachmentSheet: () -> Unit,
    onCamera: () -> Unit,
    onVoice: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            AnimatedVisibility(
                visible = replyTo != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                replyTo?.let {
                    ComposerReplyPreview(message = it, onCancel = onCancelReply)
                    Spacer(Modifier.height(8.dp))
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onOpenAttachmentSheet, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = "Open attachment options")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message...") },
                    shape = RoundedCornerShape(28.dp),
                    minLines = 1,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = onCamera, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.CameraAlt, contentDescription = "Camera", modifier = Modifier.size(20.dp))
                            }
                            if (text.isBlank()) {
                                IconButton(onClick = onVoice, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.KeyboardVoice, contentDescription = "Voice", modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                val enabled = text.isNotBlank()
                val scale by animateFloatAsState(if (enabled) 1f else 0.82f, label = "sendScale")
                IconButton(
                    onClick = onSend,
                    enabled = enabled,
                    modifier = Modifier
                        .size(48.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerReplyPreview(message: MessagePayload, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (message.senderId == "me") "Replying to You • ${message.messageType.replyTypeLabel()}" else "Replying to Astra contact • ${message.messageType.replyTypeLabel()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                message.replyComposerPreview(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, contentDescription = "Cancel reply")
        }
    }
}

private fun MessagePayload.replyComposerPreview(): String {
    val cleanText = text.trim()
    return when (messageType.uppercase()) {
        "TEXT" -> cleanText.ifBlank { "Message" }
        "IMAGE" -> cleanText.ifBlank { fileName?.let { "Image: $it" } ?: "Image" }
        "VIDEO" -> cleanText.ifBlank { fileName?.let { "Video: $it" } ?: "Video" }
        "AUDIO" -> cleanText.ifBlank { fileName?.let { "Audio: $it" } ?: "Audio" }
        "VOICE" -> cleanText.ifBlank { "Voice note" }
        "STICKER" -> cleanText.ifBlank { "Sticker" }
        "GIF" -> cleanText.ifBlank { "GIF" }
        "DOCUMENT" -> cleanText.ifBlank { fileName?.let { "Document: $it" } ?: "Document" }
        "CONTACT" -> cleanText.ifBlank { "Contact" }
        "POLL" -> cleanText.ifBlank { "Poll" }
        else -> cleanText.ifBlank { messageType.replyTypeLabel() }
    }
}

@Suppress("MissingPermission")
private fun sendCurrentLocation(context: Context, viewModel: ChatViewModel) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val providers = locationManager?.getProviders(true).orEmpty()
    val location = providers.asSequence()
        .mapNotNull { provider -> runCatching { locationManager?.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }

    if (location == null) {
        Toast.makeText(context, "Location unavailable", Toast.LENGTH_SHORT).show()
        return
    }

    viewModel.sendMessage(
        "[Location]\n" +
            "Latitude: ${"%.6f".format(location.latitude)}\n" +
            "Longitude: ${"%.6f".format(location.longitude)}\n" +
            "Accuracy: ${location.accuracy.toInt()} m\n" +
            "geo:${location.latitude},${location.longitude}"
    )
}

private fun shareOwnContact(viewModel: ChatViewModel) {
    val service = com.astramesh.app.service.AstraMeshService.getInstance()
    val identity = service?.identityManager?.loadIdentity()
    if (identity == null) {
        viewModel.sendMessage("[Contact]\nAstra identity unavailable on this device.")
        return
    }
    val payload = com.astramesh.app.crypto.CryptoManager.createContactString(
        identity,
        service.torManager.onionAddress.value.ifBlank { service.identityManager.loadOnionAddress() }
    )
    viewModel.sendMessage("[Contact]\n${identity.name}\n$payload")
}

private fun shareDeviceInfo(viewModel: ChatViewModel) {
    viewModel.sendMessage(
        "[Device]\n" +
            "Android ${Build.VERSION.RELEASE}\n" +
            "${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Astra Mesh secure device share"
    )
}

private fun sendPresence(contactKey: String, activity: String, label: String, ttlMs: Long = 8_000L) {
    com.astramesh.app.service.AstraMeshService.getInstance()
        ?.presenceManager
        ?.sendPresence(contactKey, activity, label, ttlMs)
}

private fun formatLastSeenLabel(timestamp: Long): String {
    val elapsedSeconds = ((System.currentTimeMillis() - timestamp) / 1_000L).coerceAtLeast(0L)
    return when {
        elapsedSeconds < 60L -> "Last seen just now"
        elapsedSeconds < 3_600L -> "Last seen ${elapsedSeconds / 60L} min ago"
        elapsedSeconds < 86_400L -> "Last seen ${elapsedSeconds / 3_600L} hr ago"
        else -> "Last seen ${elapsedSeconds / 86_400L} d ago"
    }
}

private fun formatStructuredAttachment(type: String, heading: String, body: String): String {
    val cleanHeading = heading.trim().ifBlank { type }
    val cleanBody = body.trim()
    return when (type) {
        "Poll" -> "[Poll]\n$cleanHeading\nOptions:\n$cleanBody"
        "Code Snippet" -> "[Code]\n$cleanHeading\n```\n$cleanBody\n```"
        "Calendar" -> "[Calendar]\n$cleanHeading\n$cleanBody"
        else -> "[$type]\n$cleanHeading\n$cleanBody"
    }
}

@Composable
private fun StructuredAttachmentDialog(
    title: String,
    onDismiss: () -> Unit,
    onSend: (String, String) -> Unit
) {
    var heading by remember(title) { mutableStateOf("") }
    var body by remember(title) { mutableStateOf("") }
    val bodyLabel = when (title) {
        "Poll" -> "Options, one per line"
        "Code Snippet" -> "Code"
        "Calendar" -> "Date, time, and notes"
        else -> "Details"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = heading,
                    onValueChange = { heading = it },
                    label = { Text(if (title == "Poll") "Question" else "Title") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(bodyLabel) },
                    minLines = 4
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(heading, body) },
                enabled = heading.isNotBlank() || body.isNotBlank()
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReactionDialog(
    message: MessagePayload,
    onDismiss: () -> Unit,
    onReaction: (String) -> Unit
) {
    var customEmoji by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReactionChoices.forEachIndexed { index, emoji ->
                        val scale by animateFloatAsState(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "reaction$index")
                        Text(
                            text = emoji,
                            fontSize = 28.sp,
                            modifier = Modifier
                                .size(44.dp)
                                .scale(scale)
                                .clip(CircleShape)
                                .combinedClickable(onClick = { onReaction(emoji) })
                                .padding(5.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customEmoji,
                        onValueChange = { customEmoji = it.take(16) },
                        singleLine = true,
                        label = { Text("Emoji") },
                        modifier = Modifier.widthIn(min = 120.dp, max = 180.dp)
                    )
                    Button(
                        onClick = {
                            val reaction = customEmoji.trim()
                            if (reaction.isNotBlank()) onReaction(reaction)
                        },
                        enabled = customEmoji.isNotBlank()
                    ) {
                        Text("Add")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            AssistChip(
                onClick = onDismiss,
                label = {
                    Text(
                        message.text.ifBlank { "Attachment" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 260.dp)
                    )
                },
                leadingIcon = {
                    if (message.lifecycleState == MessageLifecycleState.FAILED) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            )
        }
    }
}

@Composable
private fun ReactionDetailsDialog(
    message: MessagePayload,
    contactKey: String,
    contactName: String,
    onDismiss: () -> Unit
) {
    val reactionRows = remember(message.reactions, contactKey, contactName) {
        message.reactions
            .mapNotNull { (actor, emojis) ->
                val emoji = emojis.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                val name = when (actor) {
                    contactKey -> contactName
                    "me" -> "You"
                    else -> if (actor.length > 12) "You" else actor
                }
                emoji to name
            }
            .sortedBy { it.second }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reactions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (reactionRows.isEmpty()) {
                    Text("No reactions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    reactionRows.forEach { (emoji, name) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(emoji, style = MaterialTheme.typography.headlineSmall)
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private data class AttachmentAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val mimeType: String? = null
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AttachmentSheet(
    onDismiss: () -> Unit,
    onPick: (String, String) -> Unit,
    onAction: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val actions = remember {
        listOf(
            AttachmentAction("Gallery", "Choose photos & videos", Icons.Rounded.Image, "image/*"),
            AttachmentAction("Camera", "Take a photo", Icons.Rounded.CameraAlt),
            AttachmentAction("Record Video", "Capture video clip", Icons.Rounded.Videocam),
            AttachmentAction("Document", "PDF ZIP APK", Icons.Rounded.Description, "*/*"),
            AttachmentAction("Audio", "Send music", Icons.Rounded.AudioFile, "audio/*"),
            AttachmentAction("Voice Note", "Record instantly", Icons.Rounded.KeyboardVoice),
            AttachmentAction("Location", "Live location", Icons.Rounded.LocationOn),
            AttachmentAction("Contact", "Share contact", Icons.Rounded.Person),
            AttachmentAction("Poll", "Create poll", Icons.Rounded.Hub),
            AttachmentAction("GIF", "Choose animated image", Icons.Rounded.Image, "image/*"),
            AttachmentAction("Sticker", "Choose sticker image", Icons.Rounded.Person, "image/*"),
            AttachmentAction("Code Snippet", "Share formatted code", Icons.Rounded.Description),
            AttachmentAction("Calendar", "Schedule event", Icons.Rounded.Description),
            AttachmentAction("Share Device", "Nearby device details", Icons.Rounded.Smartphone),
            AttachmentAction("Mesh File", "Offline mesh transfer", Icons.Rounded.Hub, "*/*")
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Attach",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 14.dp)
            )
            actions.chunked(2).forEach { rowActions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowActions.forEach { action ->
                        AttachmentCard(
                            action = action,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (action.mimeType != null) onPick(action.title, action.mimeType) else onAction(action.title)
                            }
                        )
                    }
                    if (rowActions.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentCard(
    action: AttachmentAction,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(86.dp)
            .combinedClickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = action.subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VoiceRecorderDialog(
    onDismiss: () -> Unit,
    onSend: (Uri) -> Unit
) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordedUri by remember { mutableStateOf<Uri?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    var elapsedSeconds by remember { mutableStateOf(0L) }

    LaunchedEffect(isRecording, startedAt) {
        while (isRecording) {
            elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000L
            kotlinx.coroutines.delay(250)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.release() }
        }
    }

    Dialog(onDismissRequest = {
        if (!isRecording) onDismiss()
    }) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Voice note", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = if (isRecording) "Recording ${elapsedSeconds}s" else if (recordedUri != null) "Preview ready" else "Tap record to start",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AudioRecordingWave(isRecording = isRecording)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            if (isRecording) return@TextButton
                            recordedUri = null
                            onDismiss()
                        }
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            if (isRecording) {
                                runCatching {
                                    recorder?.stop()
                                    recorder?.release()
                                }
                                recorder = null
                                isRecording = false
                            } else {
                                val outputFile = createTempMediaFile(context, "voice", ".m4a")
                                val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
                                newRecorder.apply {
                                    setAudioSource(MediaRecorder.AudioSource.MIC)
                                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                    setAudioEncodingBitRate(96_000)
                                    setAudioSamplingRate(44_100)
                                    setOutputFile(outputFile.absolutePath)
                                    prepare()
                                    start()
                                }
                                recorder = newRecorder
                                recordedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outputFile)
                                startedAt = System.currentTimeMillis()
                                elapsedSeconds = 0
                                isRecording = true
                            }
                        }
                    ) {
                        Text(if (isRecording) "Stop" else "Record")
                    }
                    Button(
                        enabled = recordedUri != null && !isRecording,
                        onClick = { recordedUri?.let(onSend) }
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioRecordingWave(isRecording: Boolean) {
    Row(
        modifier = Modifier
            .width(220.dp)
            .height(42.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(24) { index ->
            val heights = if (isRecording) listOf(10, 24, 16, 36, 18, 28) else listOf(8, 12, 10, 14, 9, 11)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(heights[index % heights.size].dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isRecording) 0.88f else 0.35f))
            )
        }
    }
}

private fun createTempMediaUri(context: Context, prefix: String, extension: String): Uri {
    val file = createTempMediaFile(context, prefix, extension)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun createTempMediaFile(context: Context, prefix: String, extension: String): File {
    val dir = File(context.cacheDir, prefix).apply { mkdirs() }
    return File(dir, "${prefix}_${System.currentTimeMillis()}$extension").apply {
        if (!exists()) createNewFile()
    }
}

private fun bubbleShape(
    isMine: Boolean,
    compactWithPrevious: Boolean,
    compactWithNext: Boolean
): RoundedCornerShape {
    val big = 22.dp
    val small = 7.dp
    return if (isMine) {
        RoundedCornerShape(
            topStart = big,
            topEnd = if (compactWithPrevious) small else big,
            bottomStart = big,
            bottomEnd = if (compactWithNext) small else big
        )
    } else {
        RoundedCornerShape(
            topStart = if (compactWithPrevious) small else big,
            topEnd = big,
            bottomStart = if (compactWithNext) small else big,
            bottomEnd = big
        )
    }
}

private fun copyMessages(context: Context, messages: List<MessagePayload>, selectedIds: Set<String>) {
    if (selectedIds.isEmpty()) return
    val text = messages
        .filter { selectedIds.contains(it.id) }
        .joinToString("\n") { it.text.ifBlank { it.fileName ?: "Attachment" } }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("AstraMesh messages", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
