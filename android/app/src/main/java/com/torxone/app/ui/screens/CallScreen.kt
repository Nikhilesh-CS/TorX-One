package com.torxone.app.ui.screens

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.torxone.app.TorXOneApplication
import com.torxone.app.calls.*
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * CallScreen — State-of-the-art Call interface for TorX 1-to-1 secure calls.
 *
 * Supports:
 * - Incoming call (ringing, accept/decline)
 * - Outgoing call (preparing, ringing)
 * - Active voice call (duration timer, animated wave avatar, mute, speaker, video toggle)
 * - Active video call (full-screen remote video, picture-in-picture local preview, camera flip)
 * - Reconnecting & Ended transitions
 */
@Composable
fun CallScreen(
    viewModel: CallViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as TorXOneApplication

    // Back button minimizes call screen without hanging up
    BackHandler {
        onBackClick()
    }

    // Auto-dismiss screen shortly after call ends
    LaunchedEffect(uiState.state) {
        if (uiState.state in setOf(
                CallState.ENDED,
                CallState.DECLINED,
                CallState.BUSY,
                CallState.MISSED,
                CallState.FAILED
            )
        ) {
            kotlinx.coroutines.delay(1800)
            onBackClick()
        }
    }

    val isVideo = uiState.callType == CallType.VIDEO || uiState.isCameraOn

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF090D16),
                        Color(0xFF020617)
                    )
                )
            )
    ) {
        if (isVideo && uiState.state == CallState.CONNECTED) {
            // ── Video Call Layout ──
            VideoCallLayout(
                uiState = uiState,
                app = app,
                viewModel = viewModel,
                onBackClick = onBackClick
            )
        } else {
            // ── Voice / Pre-connect Call Layout ──
            VoiceCallLayout(
                uiState = uiState,
                viewModel = viewModel,
                onBackClick = onBackClick
            )
        }
    }
}

// ─── Voice Call & Incoming/Outgoing Layout ────────────────────────────────────

@Composable
private fun VoiceCallLayout(
    uiState: CallViewModel.CallUiState,
    viewModel: CallViewModel,
    onBackClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Bar: Minimize button + Encryption badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Minimize")
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Secured",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "End-to-End Secured",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // Placeholder to balance top bar layout
            Spacer(modifier = Modifier.size(40.dp))
        }

        // Center Content: Avatar with radar pulse + Caller Info
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            CallAvatar(
                name = uiState.peerName,
                isPulsing = uiState.state == CallState.INCOMING_RINGING ||
                    uiState.state == CallState.OUTGOING_RINGING ||
                    uiState.state == CallState.CONNECTED
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = uiState.peerName.ifEmpty { "Unknown" },
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            val displayStatus = when {
                uiState.state == CallState.CONNECTED -> uiState.durationText.ifEmpty { "00:00" }
                uiState.statusText.isNotEmpty() -> uiState.statusText
                else -> ""
            }

            Text(
                text = displayStatus,
                fontSize = 16.sp,
                color = if (uiState.state == CallState.CONNECTED) Color(0xFF94A3B8) else Color(0xFF38BDF8),
                fontWeight = FontWeight.Medium
            )
        }

        // Bottom Controls: Incoming (Accept/Decline) vs Active (Mute/Speaker/Video/Hangup)
        if (uiState.state == CallState.INCOMING_RINGING) {
            IncomingCallControls(
                onAccept = { viewModel.acceptCall() },
                onDecline = { viewModel.declineCall() }
            )
        } else {
            ActiveCallControls(
                uiState = uiState,
                onToggleMute = { viewModel.toggleMute() },
                onToggleSpeaker = { viewModel.toggleSpeaker() },
                onToggleCamera = { viewModel.toggleCamera() },
                onHangUp = { viewModel.hangUp() }
            )
        }
    }
}

// ─── Video Call Layout ────────────────────────────────────────────────────────

@Composable
private fun VideoCallLayout(
    uiState: CallViewModel.CallUiState,
    app: TorXOneApplication,
    viewModel: CallViewModel,
    onBackClick: () -> Unit
) {
    var controlsVisible by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { controlsVisible = !controlsVisible }
    ) {
        // Full-screen Remote Video Surface
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    init(app.webRtcClient.getEglBase()?.eglBaseContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setEnableHardwareScaler(true)
                    app.webRtcClient.remoteVideoSink = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { renderer ->
                renderer.release()
                app.webRtcClient.remoteVideoSink = null
            }
        )

        // Remote Video Placeholder if remote camera is disabled
        if (!uiState.isRemoteCameraOn) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A).copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CallAvatar(name = uiState.peerName, isPulsing = false)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "${uiState.peerName}'s camera is off",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 16.sp
                    )
                }
            }
        }

        // Picture-in-Picture Local Video Preview (Draggable / Top-Right corner)
        if (uiState.isCameraOn) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 16.dp, end = 16.dp)
                    .size(width = 110.dp, height = 150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            init(app.webRtcClient.getEglBase()?.eglBaseContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            setMirror(true)
                            setEnableHardwareScaler(true)
                            app.webRtcClient.localVideoSink = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { renderer ->
                        renderer.release()
                        app.webRtcClient.localVideoSink = null
                    }
                )
            }
        }

        // Animated Overlays (Controls & Headers)
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Video Bar: Back + Contact Info & Duration
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBackClick,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.4f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Minimize")
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = uiState.peerName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = uiState.durationText.ifEmpty { "Connected" },
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                // Bottom Controls for Video Call
                VideoCallControls(
                    uiState = uiState,
                    onToggleMute = { viewModel.toggleMute() },
                    onToggleSpeaker = { viewModel.toggleSpeaker() },
                    onToggleCamera = { viewModel.toggleCamera() },
                    onSwitchCamera = { viewModel.switchCamera() },
                    onHangUp = { viewModel.hangUp() }
                )
            }
        }
    }
}

// ─── Avatar Component with Radar Pulse ────────────────────────────────────────

@Composable
private fun CallAvatar(name: String, isPulsing: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "avatarScale"
    )

    Box(contentAlignment = Alignment.Center) {
        if (isPulsing) {
            Box(
                modifier = Modifier
                    .size(170.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8).copy(alpha = 0.12f))
            )
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(scale * 0.95f)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8).copy(alpha = 0.2f))
            )
        }

        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2563EB),
                            Color(0xFF1D4ED8),
                            Color(0xFF1E3A8A)
                        )
                    )
                )
                .border(3.dp, Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.take(1).uppercase().ifEmpty { "?" },
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

// ─── Incoming Call Controls (Accept / Decline) ────────────────────────────────

@Composable
private fun IncomingCallControls(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Decline button
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onDecline,
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
            ) {
                Icon(
                    Icons.Default.CallEnd,
                    contentDescription = "Decline",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Decline", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        }

        // Accept button
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onAccept,
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981))
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Accept",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Accept", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}

// ─── Active Call Controls (Voice) ─────────────────────────────────────────────

@Composable
private fun ActiveCallControls(
    uiState: CallViewModel.CallUiState,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onHangUp: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = Color(0xFF1E293B).copy(alpha = 0.85f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute Button
            CallControlButton(
                icon = if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (uiState.isMuted) "Unmute" else "Mute",
                isActive = uiState.isMuted,
                activeColor = Color(0xFFEF4444),
                onClick = onToggleMute
            )

            // Speaker Button
            CallControlButton(
                icon = if (uiState.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.VolumeMute,
                label = "Speaker",
                isActive = uiState.isSpeakerOn,
                onClick = onToggleSpeaker
            )

            // Video Toggle (only displayed for video calls)
            if (uiState.callType == com.torxone.app.calls.CallType.VIDEO) {
                CallControlButton(
                    icon = if (uiState.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    label = "Video",
                    isActive = uiState.isCameraOn,
                    onClick = onToggleCamera
                )
            }

            // Hang Up Button
            IconButton(
                onClick = onHangUp,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
            ) {
                Icon(
                    Icons.Default.CallEnd,
                    contentDescription = "Hang Up",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

// ─── Video Call Controls ──────────────────────────────────────────────────────

@Composable
private fun VideoCallControls(
    uiState: CallViewModel.CallUiState,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onHangUp: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = Color(0xFF0F172A).copy(alpha = 0.8f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flip Camera
            CallControlButton(
                icon = Icons.Default.Cameraswitch,
                label = "Flip",
                isActive = false,
                onClick = onSwitchCamera
            )

            // Camera Toggle
            CallControlButton(
                icon = if (uiState.isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                label = "Camera",
                isActive = !uiState.isCameraOn,
                activeColor = Color(0xFFEF4444),
                onClick = onToggleCamera
            )

            // Mute Button
            CallControlButton(
                icon = if (uiState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (uiState.isMuted) "Unmute" else "Mute",
                isActive = uiState.isMuted,
                activeColor = Color(0xFFEF4444),
                onClick = onToggleMute
            )

            // Speaker Button
            CallControlButton(
                icon = if (uiState.isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.VolumeMute,
                label = "Speaker",
                isActive = uiState.isSpeakerOn,
                onClick = onToggleSpeaker
            )

            // Hang Up
            IconButton(
                onClick = onHangUp,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
            ) {
                Icon(
                    Icons.Default.CallEnd,
                    contentDescription = "Hang Up",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

// ─── Control Button Helper ────────────────────────────────────────────────────

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color = Color(0xFF38BDF8),
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) activeColor.copy(alpha = 0.25f)
                    else Color.White.copy(alpha = 0.12f)
                )
                .border(
                    1.dp,
                    if (isActive) activeColor else Color.Transparent,
                    CircleShape
                )
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (isActive) activeColor else Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}
