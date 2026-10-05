package com.torxone.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.torxone.app.ui.components.rememberUiActionState
import com.torxone.app.ui.components.temporaryMuteUntil
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.chat.ChatService
import com.torxone.app.data.entity.ContactEntity
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.notifications.NotificationPolicy
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactInfoScreen(
    contact: ContactEntity?,
    conversation: ConversationEntity?,
    chatService: ChatService,
    onBackClick: () -> Unit,
    onChatDeleted: () -> Unit,
    onOpenMedia: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleVerification: ((Boolean) -> Unit)? = null,
    nickname: String? = null,
    onSaveNickname: (suspend (String) -> Unit)? = null,
    onAudioCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onTheme: (() -> Unit)? = null,
    onConnection: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val actions = rememberUiActionState()
    var showMuteDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showResetSessionConfirm by rememberSaveable { mutableStateOf(false) }
    var showSafetyNumberDialog by rememberSaveable { mutableStateOf(false) }
    var showNicknameDialog by rememberSaveable { mutableStateOf(false) }
    var nicknameInput by rememberSaveable { mutableStateOf("") }
    var nicknameSaving by remember { mutableStateOf(false) }
    var nicknameError by remember { mutableStateOf<String?>(null) }

    val isMuted = NotificationPolicy.isConversationMuted(conversation?.mutedUntil)
    val contactName = nickname ?: contact?.displayName ?: conversation?.title ?: "Contact"
    val isVerified = contact?.verificationState == "VERIFIED"
    val fingerprint = remember(contact?.signingPublicKey) {
        val pub = contact?.signingPublicKey
        if (pub != null && pub.isNotEmpty()) {
            IdentityCrypto.computeFingerprint(pub)
        } else {
            "Not available"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Contact info") }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            com.torxone.app.ui.components.ProfileAvatar(contactName, contact?.avatarHash, Modifier.size(96.dp))
            if (!contact?.about.isNullOrBlank()) Text(contact!!.about)

            // Contact Name
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = contactName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isVerified) "Safety number verified locally" else "Contact",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                onAudioCall?.let { OutlinedButton(onClick = it, enabled = !actions.busy) { Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text("Audio") } }
                onVideoCall?.let { OutlinedButton(onClick = it, enabled = !actions.busy) { Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(8.dp)); Text("Video") } }
                onSearch?.let { OutlinedButton(onClick = it) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Search") } }
            }
            onTheme?.let { ListItem(headlineContent = { Text("Chat theme") }, leadingContent = { Icon(Icons.Default.Palette, null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable(onClick = it)) }
            onConnection?.let { ListItem(headlineContent = { Text("Connection") }, leadingContent = { Icon(Icons.Default.Link, null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable(onClick = it)) }

            if (contact != null && onSaveNickname != null) {
                ListItem(
                    headlineContent = { Text("Local nickname") },
                    supportingContent = { Text(nickname ?: "Choose a name visible only to you") },
                    trailingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.clickable(enabled = !actions.busy) {
                        nicknameInput = nickname.orEmpty()
                        nicknameError = null
                        showNicknameDialog = true
                    }
                )
            }

            // Media, Links & Docs Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                ListItem(
                    headlineContent = { Text("Media, links and docs") },
                    supportingContent = { Text("Encrypted photos, videos, voice notes and files") },
                    leadingContent = {
                        Icon(Icons.Default.PermMedia, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onOpenMedia)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    ListItem(
                        headlineContent = { Text("Mute notifications") },
                        supportingContent = {
                            Text(if (isMuted) "Muted" else "Off")
                        },
                        leadingContent = {
                            Icon(
                                if (isMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable(enabled = !actions.busy) {
                            if (isMuted) {
                                actions.run("Could not update this chat") {
                                    if (conversation != null) {
                                        chatService.setChatMuted(conversation.conversationId, null)
                                    }
                                }
                            } else {
                                showMuteDialog = true
                            }
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    ListItem(
                        headlineContent = { Text("Encryption & safety number") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Messages are end-to-end encrypted with Double Ratchet.")
                                if (isVerified) {
                                    Text(
                                        "✓ Verified safety number",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Text(
                                        "Tap to view and verify safety number",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (isVerified) Icons.Default.VerifiedUser else Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (isVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        trailingContent = {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable(enabled = !actions.busy) { showSafetyNumberDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    ListItem(
                        headlineContent = { Text("Reset secure session") },
                        supportingContent = {
                            Text("Clear ratchet state to recover from decryption or sync errors.")
                        },
                        leadingContent = {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                        },
                        modifier = Modifier.clickable(enabled = !actions.busy) { showResetSessionConfirm = true }
                    )
                }
            }

            // Danger Zone Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                )
            ) {
                ListItem(
                    headlineContent = {
                        Text("Delete chat", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    },
                    leadingContent = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable(enabled = !actions.busy) { showDeleteConfirm = true }
                )
            }
        }
    }

    if (showNicknameDialog && onSaveNickname != null) {
        AlertDialog(
            onDismissRequest = { if (!nicknameSaving) showNicknameDialog = false },
            title = { Text("Local nickname") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = nicknameInput, onValueChange = { nicknameInput = it.take(60) },
                        label = { Text("Nickname") }, singleLine = true, enabled = !nicknameSaving)
                    Text("Only this device uses this name. Leave it empty to use their profile name.")
                    nicknameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !nicknameSaving, onClick = {
                    coroutineScope.launch {
                        nicknameSaving = true
                        try {
                            onSaveNickname(nicknameInput)
                            showNicknameDialog = false
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            nicknameError = failure.message ?: "Unable to save nickname"
                        } finally { nicknameSaving = false }
                    }
                }) { Text(if (nicknameSaving) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(enabled = !nicknameSaving,
                onClick = { showNicknameDialog = false }) { Text("Cancel") } }
        )
    }

    // Mute Dialog
    if (showMuteDialog && conversation != null) {
        AlertDialog(
            onDismissRequest = { if (!actions.busy) showMuteDialog = false },
            title = { Text("Mute notifications for...") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(
                        enabled = !actions.busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            actions.run("Could not update this chat") {
                                chatService.setChatMuted(conversation.conversationId, temporaryMuteUntil(System.currentTimeMillis(), 8 * 3600_000L))
                                showMuteDialog = false
                            }
                        }
                    ) {
                        Text("8 hours", modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        enabled = !actions.busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            actions.run("Could not update this chat") {
                                chatService.setChatMuted(conversation.conversationId, temporaryMuteUntil(System.currentTimeMillis(), 7 * 24 * 3600_000L))
                                showMuteDialog = false
                            }
                        }
                    ) {
                        Text("1 week", modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        enabled = !actions.busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            actions.run("Could not update this chat") {
                                chatService.setChatMuted(conversation.conversationId, Long.MAX_VALUE)
                                showMuteDialog = false
                            }
                        }
                    ) {
                        Text("Always", modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMuteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Chat Confirmation
    if (showDeleteConfirm && conversation != null) {
        AlertDialog(
            onDismissRequest = { if (!actions.busy) showDeleteConfirm = false },
            title = { Text("Delete this chat?") },
            text = {
                Column { Text("Messages will be deleted from this device only. The contact and secure key exchange will remain saved."); actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
            },
            confirmButton = {
                TextButton(
                    enabled = !actions.busy,
                    onClick = {
                        actions.run("Could not update this chat") {
                            chatService.deleteChatLocally(conversation.conversationId)
                            showDeleteConfirm = false
                            onChatDeleted()
                        }
                    }
                ) {
                    Text("Delete chat", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Reset Session Confirmation
    if (showResetSessionConfirm && contact != null) {
        AlertDialog(
            onDismissRequest = { if (!actions.busy) showResetSessionConfirm = false },
            title = { Text("Reset secure session?") },
            text = {
                Column { Text("This replaces the saved secure session. Only use this to recover from repeated decryption or sync errors."); actions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
            },
            confirmButton = {
                TextButton(
                    enabled = !actions.busy,
                    onClick = {
                        actions.run("Could not update this chat") {
                            chatService.resetSession(contact.relationshipId)
                            showResetSessionConfirm = false
                        }
                    }
                ) {
                    Text("Reset Session", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetSessionConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Safety Number Verification Dialog
    if (showSafetyNumberDialog && contact != null) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showSafetyNumberDialog = false },
            icon = {
                Icon(
                    if (isVerified) Icons.Default.VerifiedUser else Icons.Default.Security,
                    contentDescription = null,
                    tint = if (isVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            },
            title = { Text("Verify safety number") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "To verify that messages and calls with ${contact.displayName} are end-to-end encrypted, compare the safety number below with the number on their device.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = fingerprint,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Safety Number", fingerprint))
                            Toast.makeText(context, "Safety number copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy safety number")
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = onToggleVerification != null,
                    onClick = {
                        val newVerified = !isVerified
                        onToggleVerification?.invoke(newVerified)
                        showSafetyNumberDialog = false
                    }
                ) {
                    Text(if (isVerified) "Clear verification" else "Mark as verified")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSafetyNumberDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
