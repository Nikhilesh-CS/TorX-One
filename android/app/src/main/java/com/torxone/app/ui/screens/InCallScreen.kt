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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.NorthEast
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
import com.torxone.app.call.CallQuality
import com.torxone.app.call.CallUiState
import com.torxone.app.call.bannerStatusText
import com.torxone.app.call.isActiveCall
import com.torxone.app.call.peerNameOrEmpty
import com.torxone.app.ui.components.AstraAvatar
import kotlinx.coroutines.delay

@Composable
fun CallOverlayHost(
    callState: CallUiState,
    isCallMinimized: Boolean,
    onMinimize: () -> Unit,
    onExpand: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismissUnavailable: () -> Unit
) {
    if (callState is CallUiState.Idle || callState is CallUiState.Ended) {
        return
    }

    if (callState is CallUiState.Unavailable) {
        AlertDialog(
            onDismissRequest = onDismissUnavailable,
            title = { Text("Call unavailable") },
            text = { Text(callState.reason) },
            confirmButton = {
                TextButton(onClick = onDismissUnavailable) { Text("Close") }
            }
        )
        return
    }

    if (!callState.isActiveCall) {
        return
    }

    if (isCallMinimized) {
        ActiveCallBanner(
            state = callState,
            onExpand = onExpand,
            onToggleMute = onToggleMute,
            onEnd = onEnd
        )
    } else {
        InCallScreen(
            state = callState,
            onMinimize = onMinimize,
            onAccept = onAccept,
            onReject = onReject,
            onEnd = onEnd,
            onToggleMute = onToggleMute,
            onToggleSpeaker = onToggleSpeaker
        )
    }
}

@Composable
fun InCallScreen(
    state: CallUiState,
    onMinimize: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    if (!state.isActiveCall) return

    val peerName = state.peerNameOrEmpty

    Dialog(
        onDismissRequest = onMinimize,
        properties = DialogProperties(
            dismissOnBackPress = true,
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
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(vertical = 24.dp, horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Minimize button at top
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Minimize Call",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Top section (Status & Peer)
                Spacer(modifier = Modifier.weight(1f))

                if (state is CallUiState.Ringing && state.direction == CallDirection.INCOMING) {
                    Text(
                        text = "Incoming Call",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
                
                AstraAvatar(model = null, name = peerName, size = 120.dp)
                
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
                    is CallUiState.Ringing -> if (state.direction == CallDirection.INCOMING) "🔊 Ringing..." else "Ringing…"
                    is CallUiState.Outgoing -> "Calling…"
                    is CallUiState.Accepted -> "Connecting…"
                    is CallUiState.Negotiating -> "Connecting…"
                    is CallUiState.IceConnecting -> "Connecting to peer…"
                    is CallUiState.MediaConnecting -> "Starting audio…"
                    is CallUiState.Reconnecting -> "Reconnecting…"
                    is CallUiState.Connected -> {
                        val duration = formatDuration(state.callDurationSeconds)
                        if (state.quality == CallQuality.POOR) {
                            "⚠️ Poor connection • $duration"
                        } else {
                            duration
                        }
                    }
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
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, color = Color.White, fontSize = 12.sp)
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

@Composable
fun ActiveCallBanner(
    state: CallUiState,
    onExpand: () -> Unit,
    onToggleMute: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isActiveCall) return

    val peerName = state.peerNameOrEmpty
    val statusText = state.bannerStatusText()
    val isMuted = (state as? CallUiState.Connected)?.isMuted == true

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .statusBarsPadding()
            .clickable { onExpand() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B382B)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2E7D32)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "Active call",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = peerName,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusText,
                        color = Color(0xFF81C784),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state is CallUiState.Connected) {
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isMuted) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Mute",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                IconButton(
                    onClick = onEnd,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onExpand,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NorthEast,
                        contentDescription = "Expand Call",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
