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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.astramesh.app.data.AppDatabase
import com.astramesh.app.data.MessageEntity
import com.astramesh.app.network.MessageRouter
import com.astramesh.app.network.NearbyConnectionManager
import com.astramesh.app.network.Transport
import com.astramesh.app.ui.theme.*
import com.astramesh.app.ui.components.*
import androidx.compose.animation.*
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.rounded.Check
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactKey: String,
    navController: NavController,
    db: AppDatabase,
    nearbyManager: NearbyConnectionManager,
    messageRouter: MessageRouter
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var messageText by remember { mutableStateOf("") }
    val messages by db.messageDao().getMessagesForContact(contactKey).collectAsState(initial = emptyList())
    var contactName by remember { mutableStateOf("Chat") }
    var contactEndpoint by remember { mutableStateOf("") }
    var contactOnion by remember { mutableStateOf("") }
    val connectedEndpoints by nearbyManager.connectedEndpoints.collectAsState()
    val listState = rememberLazyListState()
    
    // UI states
    var showMessageMenu by remember { mutableStateOf<MessageEntity?>(null) }
    var replyToMessage by remember { mutableStateOf<MessageEntity?>(null) }
    
    val isNearbyOnline = connectedEndpoints.contains(contactEndpoint)
    val isOnline = isNearbyOnline || contactOnion.isNotBlank()

    LaunchedEffect(contactKey) {
        withContext(Dispatchers.IO) {
            val c = db.contactDao().getContact(contactKey)
            if (c != null) {
                contactName = c.name
                contactEndpoint = c.endpointId
                contactOnion = c.onionAddress
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(messages) {
        withContext(Dispatchers.IO) {
            messages.filter { it.direction == "received" && it.status != "read" }.forEach { msg ->
                db.messageDao().updateMessageStatus(msg.messageId, "read")
                messageRouter.sendReadReceipt(msg.messageId, msg.contactKey)
            }
        }
    }

    Scaffold(
        containerColor = DeepBlack,
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = DarkSurface, shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = SoftWhite)
                    }
                    AstraAvatar(
                        name = contactName,
                        size = 40.dp,
                        isOnline = isOnline
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(contactName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = SoftWhite)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isNearbyOnline -> NeonGreen
                                            contactOnion.isNotBlank() -> AccentCyan
                                            else -> DimGray
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                when {
                                    isNearbyOnline -> "Nearby"
                                    contactOnion.isNotBlank() -> "Global Connected"
                                    else -> "Offline"
                                },
                                fontSize = 12.sp,
                                color = when {
                                    isNearbyOnline -> NeonGreen
                                    contactOnion.isNotBlank() -> AccentCyan
                                    else -> DimGray
                                }
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = DeepBlack, shadowElevation = 16.dp) {
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
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        IconButton(
                            onClick = { Toast.makeText(context, "Attachments coming soon", Toast.LENGTH_SHORT).show() },
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(Icons.Rounded.AttachFile, "Attach", tint = MutedGray)
                        }
                        
                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message...", color = DimGray) },
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentViolet,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = SoftWhite,
                                unfocusedTextColor = SoftWhite,
                                cursorColor = AccentViolet,
                                focusedContainerColor = CardSurface,
                                unfocusedContainerColor = CardSurface
                            ),
                            maxLines = 4
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        val isSendEnabled = messageText.isNotBlank()
                        val sendScale by animateFloatAsState(targetValue = if (isSendEnabled) 1f else 0.8f, label = "sendBtn")
                        
                        IconButton(
                            onClick = {
                                if (!isSendEnabled) return@IconButton
                                val text = if (replyToMessage != null) {
                                    "↩ ${replyToMessage!!.text.take(30)}...\n$messageText"
                                } else messageText
                                
                                messageText = ""
                                replyToMessage = null
                                
                                coroutineScope.launch {
                                    val result = messageRouter.sendMessage(contactKey, text)
                                    if (!result.success) {
                                        Toast.makeText(context, result.error ?: "Send failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = isSendEnabled,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .size(48.dp)
                                .scale(sendScale)
                                .clip(CircleShape)
                                .background(
                                    if (isSendEnabled) Brush.linearGradient(listOf(AccentViolet, AccentBlue))
                                    else Brush.linearGradient(listOf(DimGray, DimGray))
                                )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("👋", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No messages yet", fontSize = 18.sp, color = SoftWhite, fontWeight = FontWeight.Bold)
                    Text("Say hello to $contactName!", fontSize = 14.sp, color = MutedGray)
                }
            } else {
                val reversedMessages = remember(messages) { messages.asReversed() }
                val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    items(reversedMessages.size) { index ->
                        val message = reversedMessages[index]
                        val nextMessage = reversedMessages.getOrNull(index + 1)
                        
                        val dateStr = dateFormat.format(Date(message.timestamp))
                        val nextDateStr = nextMessage?.let { dateFormat.format(Date(it.timestamp)) }
                        
                        MessageBubble(
                            message = message,
                            onLongClick = { showMessageMenu = message },
                            onSwipeReply = { replyToMessage = message }
                        )
                        
                        if (dateStr != nextDateStr) {
                            DateSeparator(dateStr)
                        }
                    }
                }
            }
        }

        if (showMessageMenu != null) {
            MessageActionsSheet(
                message = showMessageMenu!!,
                onDismiss = { showMessageMenu = null },
                onReply = { 
                    replyToMessage = showMessageMenu
                    showMessageMenu = null
                },
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Copied message", showMessageMenu!!.text))
                    Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
                    showMessageMenu = null
                },
                onForward = {
                    Toast.makeText(context, "Forwarding coming soon", Toast.LENGTH_SHORT).show()
                    showMessageMenu = null
                },
                onDelete = {
                    val msgToDelete = showMessageMenu!!
                    coroutineScope.launch(Dispatchers.IO) {
                        db.messageDao().deleteMessage(msgToDelete.messageId)
                    }
                    showMessageMenu = null
                }
            )
        }
    }
}

@Composable
fun DateSeparator(dateStr: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            text = dateStr,
            fontSize = 12.sp,
            color = MutedGray,
            modifier = Modifier.background(CardSurface, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun ReplyPreviewBanner(message: MessageEntity, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(4.dp).height(32.dp).background(AccentViolet, RoundedCornerShape(2.dp)))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (message.direction == "sent") "You" else "Them", fontSize = 13.sp, color = AccentViolet, fontWeight = FontWeight.Bold)
            Text(message.text, fontSize = 13.sp, color = MutedGray, maxLines = 1)
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, "Cancel", tint = DimGray)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    message: MessageEntity,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CardSurface
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text("Message Actions", color = SoftWhite, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
            HorizontalDivider(color = DimGray)
            
            ListItem(
                headlineContent = { Text("Reply", color = SoftWhite) },
                modifier = Modifier.clickable { onReply() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            ListItem(
                headlineContent = { Text("Copy Text", color = SoftWhite) },
                modifier = Modifier.clickable { onCopy() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            ListItem(
                headlineContent = { Text("Forward", color = SoftWhite) },
                modifier = Modifier.clickable { onForward() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            ListItem(
                headlineContent = { Text("Delete for me", color = AccentPink) },
                modifier = Modifier.clickable { onDelete() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: MessageEntity, onLongClick: () -> Unit, onSwipeReply: () -> Unit) {
    val isSent = message.direction == "sent"
    val align = if (isSent) Alignment.CenterEnd else Alignment.CenterStart
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isEmojiOnly = message.text.length <= 3 && message.text.all { !it.isLetterOrDigit() && !it.isWhitespace() }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    var swipeOffset by remember { mutableStateOf(0f) }
    val animatedSwipeOffset by animateFloatAsState(targetValue = swipeOffset)

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            initialOffsetX = { if (isSent) it / 2 else -it / 2 }
        ) + fadeIn() + expandVertically()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .offset { androidx.compose.ui.unit.IntOffset(animatedSwipeOffset.toInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeOffset > 100f || swipeOffset < -100f) {
                                // Trigger reply if swiped far enough
                                onSwipeReply()
                            }
                            swipeOffset = 0f
                        },
                        onDragCancel = { swipeOffset = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            // Only allow swipe left for sent, right for received
                            if (isSent && dragAmount < 0) {
                                swipeOffset += dragAmount
                            } else if (!isSent && dragAmount > 0) {
                                swipeOffset += dragAmount
                            }
                            // Limit swipe distance
                            if (swipeOffset > 150f) swipeOffset = 150f
                            if (swipeOffset < -150f) swipeOffset = -150f
                        }
                    )
                },
            contentAlignment = align
        ) {
            Column(horizontalAlignment = if (isSent) Alignment.End else Alignment.Start) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .shadow(
                            elevation = 8.dp,
                            shape = if (isSent) RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp)
                                    else RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp),
                            spotColor = if (isSent) AccentViolet.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.3f)
                        )
                        .clip(
                            if (isSent) RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp)
                            else RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp)
                        )
                        .background(
                            if (isSent) Brush.linearGradient(listOf(AccentBlue, AccentViolet))
                            else Brush.linearGradient(listOf(CardSurface, DarkSurface))
                        )
                        .combinedClickable(onClick = {}, onLongClick = onLongClick)
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Column {
                        if (message.replyToText != null) {
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 6.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.2f))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = message.replyToText,
                                    fontSize = 12.sp,
                                    color = if (isSent) Color.White.copy(alpha = 0.7f) else MutedGray,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                        Text(
                            message.text,
                            fontSize = if (isEmojiOnly) 40.sp else 16.sp,
                            color = if (isSent) Color.White else SoftWhite,
                            lineHeight = 24.sp
                        )
                        Row(
                            modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                timeFormat.format(Date(message.timestamp)),
                                fontSize = 11.sp,
                                color = if (isSent) Color.White.copy(alpha = 0.8f) else MutedGray
                            )
                            if (message.transport != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (message.transport == "NEARBY") "📶" else "🧅",
                                    fontSize = 10.sp
                                )
                            }
                            if (isSent) {
                                Spacer(modifier = Modifier.width(4.dp))
                                when (message.status) {
                                    "read" -> {
                                        Icon(
                                            Icons.Rounded.DoneAll,
                                            contentDescription = "Read",
                                            tint = AccentCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    "delivered" -> {
                                        Icon(
                                            Icons.Rounded.DoneAll,
                                            contentDescription = "Delivered",
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    else -> {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = "Sent",
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(16.dp)
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
}
