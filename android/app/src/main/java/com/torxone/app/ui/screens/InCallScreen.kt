package com.torxone.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torxone.app.call.CallDirection
import com.torxone.app.call.CallUiState
import kotlinx.coroutines.delay

@Composable
fun InCallScreen(
    state: CallUiState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismissEnded: () -> Unit
) {
    if (state is CallUiState.Idle) return

    if (state is CallUiState.Unavailable) {
        AlertDialog(
            onDismissRequest = onDismissEnded,
            title = { Text("Call unavailable") },
            text = { Text(state.reason) },
            confirmButton = {
                TextButton(onClick = onDismissEnded) { Text("Close") }
            }
        )
        return
    }

    if (state is CallUiState.Ended) {
        LaunchedEffect(Unit) {
            delay(2000)
            onDismissEnded()
        }
        Dialog(
            onDismissRequest = onDismissEnded,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF1A1A1A)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Call Ended", color = Color.White, fontSize = 24.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(state.reason, color = Color.Gray, fontSize = 16.sp)
                    if (state.durationSeconds > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(formatDuration(state.durationSeconds), color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }
        }
        return
    }

    // Active Call States (Ringing, Connecting, Connected)
    val peerName = when (state) {
        is CallUiState.Ringing -> state.peerName
        is CallUiState.Outgoing -> state.peerName
        is CallUiState.Accepted -> state.peerName
        is CallUiState.Negotiating -> state.peerName
        is CallUiState.IceConnecting -> state.peerName
        is CallUiState.MediaConnecting -> state.peerName
        is CallUiState.Reconnecting -> state.peerName
        is CallUiState.Connected -> state.peerName
        else -> ""
    }

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF121212)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 48.dp, horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top section (Status & Peer)
                Spacer(modifier = Modifier.weight(1f))
                
                // Avatar placeholder
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2D2D2D)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = peerName.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = peerName,
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Status / Timer
                val statusText = when (state) {
                    is CallUiState.Ringing -> if (state.direction == CallDirection.INCOMING) "Incoming WhatsApp Call" else "Ringing..."
                    is CallUiState.Outgoing -> "Calling..."
                    is CallUiState.Accepted -> "Connecting..."
                    is CallUiState.Negotiating -> "Securing Call..."
                    is CallUiState.IceConnecting -> "Connecting to peer..."
                    is CallUiState.MediaConnecting -> "Starting audio..."
                    is CallUiState.Reconnecting -> "Reconnecting..."
                    is CallUiState.Connected -> formatDuration(state.callDurationSeconds)
                    else -> ""
                }
                
                Text(
                    text = statusText,
                    color = Color.LightGray,
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.weight(2f))

                // Bottom section (Controls)
                when (state) {
                    is CallUiState.Ringing -> {
                        if (state.direction == CallDirection.INCOMING) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                CallActionButton(
                                    icon = Icons.Default.CallEnd,
                                    color = Color(0xFFE53935),
                                    label = "Decline",
                                    onClick = onReject
                                )
                                CallActionButton(
                                    icon = Icons.Default.Phone,
                                    color = Color(0xFF43A047),
                                    label = "Accept",
                                    onClick = onAccept
                                )
                            }
                        } else {
                            CallActionButton(
                                icon = Icons.Default.CallEnd,
                                color = Color(0xFFE53935),
                                label = "End",
                                onClick = onEnd
                            )
                        }
                    }
                    is CallUiState.Outgoing,
                    is CallUiState.Accepted,
                    is CallUiState.Negotiating,
                    is CallUiState.IceConnecting,
                    is CallUiState.MediaConnecting,
                    is CallUiState.Reconnecting -> {
                        CallActionButton(
                            icon = Icons.Default.CallEnd,
                            color = Color(0xFFE53935),
                            label = "End",
                            onClick = onEnd
                        )
                    }
                    is CallUiState.Connected -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            CallControlButton(
                                icon = if (state.isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                label = "Speaker",
                                isActive = state.isSpeaker,
                                onClick = onToggleSpeaker
                            )
                            CallControlButton(
                                icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                label = "Mute",
                                isActive = state.isMuted,
                                onClick = onToggleMute
                            )
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        CallActionButton(
                            icon = Icons.Default.CallEnd,
                            color = Color(0xFFE53935),
                            label = "End Call",
                            onClick = onEnd
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun CallActionButton(
    icon: ImageVector,
    color: Color,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (isActive) Color.White else Color(0xFF333333))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) Color.Black else Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, color = Color.White, fontSize = 12.sp)
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}
