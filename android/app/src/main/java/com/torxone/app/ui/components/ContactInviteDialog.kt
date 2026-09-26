package com.torxone.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torxone.app.contacts.ContactsUiState
import com.torxone.app.contacts.ContactsViewModel

@Composable
fun ContactInviteDialog(
    viewModel: ContactsViewModel,
    onContactAdded: (conversationId: String, contactName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var pasteInviteText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.generateMyInviteQr()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Verification Confirmation Step (Section 6)
                if (uiState.pendingInviteValidation != null) {
                    val valid = uiState.pendingInviteValidation!!
                    Text(
                        text = "Add Contact",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Add \"${valid.invite.displayName}\"?",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Verify safety fingerprint before connecting:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = valid.fingerprint,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.dismissValidation() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                viewModel.confirmAddContact { convId ->
                                    onDismiss()
                                    onContactAdded(convId, valid.invite.displayName)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Accept & Connect")
                        }
                    }
                } else {
                    // Tab selector: My Invite vs Enter Invite
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("My QR") },
                            icon = { Icon(Icons.Default.QrCode, contentDescription = null) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Add Peer") },
                            icon = { Icon(Icons.Default.VpnKey, contentDescription = null) }
                        )
                    }

                    if (selectedTab == 0) {
                        // My QR Code
                        val qrString = uiState.myInviteQrString
                        if (qrString != null) {
                            val qrBitmap = remember(qrString) {
                                QrCodeGenerator.generateQrBitmap(qrString, 400)
                            }
                            if (qrBitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .size(240.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.White)
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = "My Contact Invite QR",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("TorX Invite", qrString))
                                    Toast.makeText(context, "Invite link copied!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Copy Invite Link")
                            }
                        } else {
                            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                        }
                    } else {
                        // Live CameraX QR Scanner View with manual paste fallback
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            QrCameraScanner(
                                onQrScanned = { scannedString ->
                                    viewModel.onQrScanned(scannedString)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )

                            Text(
                                text = "Point camera at peer's QR code",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // Fallback Enter/Paste Peer Invite
                            OutlinedTextField(
                                value = pasteInviteText,
                                onValueChange = { pasteInviteText = it },
                                label = { Text("Or paste invite link (torx://contact/...)") },
                                placeholder = { Text("torx://contact/eyJ...") },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 2
                            )

                            Button(
                                onClick = {
                                    if (pasteInviteText.isNotBlank()) {
                                        viewModel.onQrScanned(pasteInviteText.trim())
                                    }
                                },
                                enabled = pasteInviteText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Validate & Connect")
                            }
                        }
                    }

                    if (uiState.error != null) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
