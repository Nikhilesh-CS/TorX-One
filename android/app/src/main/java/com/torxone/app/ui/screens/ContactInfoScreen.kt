package com.torxone.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoScreen(
    contact: ContactEntity?,
    conversation: ConversationEntity?,
    chatService: ChatService,
    onBackClick: () -> Unit,
    onChatDeleted: () -> Unit,
    onToggleVerification: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var showMuteDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showResetSessionConfirm by remember { mutableStateOf(false) }
    var showSafetyNumberDialog by remember { mutableStateOf(false) }

    val isMuted = NotificationPolicy.isConversationMuted(conversation?.mutedUntil)
    val contactName = contact?.displayName ?: conversation?.title ?: "Contact"
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Large Avatar
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contactName.take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Contact Name
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = contactName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "TorX Direct Peer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                    modifier = Modifier.clickable { /* Open media gallery */ }
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
                        modifier = Modifier.clickable {
                            if (isMuted) {
                                coroutineScope.launch {
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
                        modifier = Modifier.clickable { showSafetyNumberDialog = true }
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
                        modifier = Modifier.clickable { showResetSessionConfirm = true }
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
                    modifier = Modifier.clickable { showDeleteConfirm = true }
                )
            }
        }
    }

    // Mute Dialog
    if (showMuteDialog && conversation != null) {
        AlertDialog(
            onDismissRequest = { showMuteDialog = false },
            title = { Text("Mute notifications for...") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            coroutineScope.launch {
                                chatService.setChatMuted(conversation.conversationId, 8 * 3600_000L)
                                showMuteDialog = false
                            }
                        }
                    ) {
                        Text("8 hours", modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            coroutineScope.launch {
                                chatService.setChatMuted(conversation.conversationId, 7 * 24 * 3600_000L)
                                showMuteDialog = false
                            }
                        }
                    ) {
                        Text("1 week", modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            coroutineScope.launch {
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
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this chat?") },
            text = {
                Text("Messages will be deleted from this device only. The contact and secure key exchange will remain saved.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
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
            onDismissRequest = { showResetSessionConfirm = false },
            title = { Text("Reset secure session?") },
            text = {
                Text("This deletes the current Double Ratchet session keys for this contact. Use this to recover from key-desync or decryption errors.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
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
