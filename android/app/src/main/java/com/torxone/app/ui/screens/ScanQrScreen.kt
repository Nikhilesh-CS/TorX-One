package com.torxone.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.data.AppDatabase
import com.torxone.app.data.ContactEntity
import com.torxone.app.group.GroupManager
import com.torxone.app.identity.IdentityManager
import com.torxone.app.ui.theme.AppBackground
import com.torxone.app.ui.theme.BorderColor
import com.torxone.app.ui.theme.PrimaryText
import com.torxone.app.ui.theme.SecondaryText
import com.torxone.app.ui.theme.SurfaceCard
import com.torxone.app.ui.theme.TorXPrimary
import com.torxone.app.ui.theme.TorXPrimarySoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed class QrDetectedPayload {
    data class Contact(
        val parsed: CryptoManager.ParsedContact,
        val raw: String
    ) : QrDetectedPayload()

    data class Group(
        val token: String,
        val groupId: String,
        val inviterKey: String,
        val groupName: String? = null
    ) : QrDetectedPayload()
}

@Composable
fun ScanQrScreen(
    navController: NavController,
    db: AppDatabase,
    groupManager: GroupManager? = null,
    identityManager: IdentityManager? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var detectedPayload by remember { mutableStateOf<QrDetectedPayload?>(null) }
    var isProcessingAction by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard)
                    .border(width = 1.dp, color = BorderColor)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AppBackground)
                            .border(1.dp, BorderColor, CircleShape)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = PrimaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Scan QR",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryText
                        )
                        Text(
                            text = "Scan a TorX One contact or group QR code",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondaryText
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(AppBackground)
        ) {
            if (hasCameraPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Camera Preview Frame
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black)
                            .border(1.dp, BorderColor, RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        UniversalQrCameraPreview(
                            modifier = Modifier.fillMaxSize(),
                            isPaused = detectedPayload != null,
                            onQrScanned = { rawPayload ->
                                if (detectedPayload == null) {
                                    scope.launch {
                                        val detected = parsePayload(rawPayload, db)
                                        if (detected != null) {
                                            detectedPayload = detected
                                        }
                                    }
                                }
                            }
                        )

                        // Visual Viewfinder overlay with stylish corner brackets
                        ViewfinderOverlay(
                            isDetected = detectedPayload != null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Instruction indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.QrCodeScanner,
                            contentDescription = null,
                            tint = TorXPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (detectedPayload == null) "Point your camera at a TorX QR code" else "QR code recognized",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (detectedPayload == null) SecondaryText else TorXPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Detection Confirmation Bottom Card
                AnimatedVisibility(
                    visible = detectedPayload != null,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    detectedPayload?.let { payload ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(SurfaceCard)
                                .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
                                .padding(20.dp)
                        ) {
                            when (payload) {
                                is QrDetectedPayload.Contact -> {
                                    ContactDetectedCard(
                                        contact = payload.parsed,
                                        isProcessing = isProcessingAction,
                                        onCancel = { detectedPayload = null },
                                        onAddContact = {
                                            isProcessingAction = true
                                            scope.launch {
                                                val sigKeyHex = CryptoManager.toHex(payload.parsed.signingPublicKey)
                                                withContext(Dispatchers.IO) {
                                                    db.contactDao().insertContact(
                                                        ContactEntity(
                                                            signingPublicKey = sigKeyHex,
                                                            encryptionPublicKey = CryptoManager.toHex(payload.parsed.encryptionPublicKey),
                                                            name = payload.parsed.name,
                                                            endpointId = "",
                                                            onionAddress = payload.parsed.onionAddress ?: "",
                                                            isConnected = false
                                                        )
                                                    )
                                                }
                                                Toast.makeText(context, "Added ${payload.parsed.name}", Toast.LENGTH_SHORT).show()
                                                navController.popBackStack()
                                            }
                                        }
                                    )
                                }
                                is QrDetectedPayload.Group -> {
                                    GroupDetectedCard(
                                        groupId = payload.groupId,
                                        groupName = payload.groupName,
                                        inviterKey = payload.inviterKey,
                                        isProcessing = isProcessingAction,
                                        onCancel = { detectedPayload = null },
                                        onJoinGroup = {
                                            isProcessingAction = true
                                            scope.launch {
                                                val manager = groupManager
                                                    ?: com.torxone.app.service.TorXOneService.getInstance()?.groupManager
                                                if (manager != null) {
                                                    val success = manager.requestJoinWithInvite(payload.token)
                                                    if (success) {
                                                        Toast.makeText(context, "Join request sent", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(context, "Invite request submitted", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else {
                                                    Toast.makeText(context, "Mesh group service not ready", Toast.LENGTH_SHORT).show()
                                                }
                                                navController.popBackStack()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Permission Request Card
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceCard)
                            .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
                            .padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(TorXPrimarySoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.CameraAlt,
                                contentDescription = null,
                                tint = TorXPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Camera Access Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryText
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "TorX One needs camera permission to scan identity and group invitation QR codes directly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SecondaryText,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = TorXPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Allow Camera", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactDetectedCard(
    contact: CryptoManager.ParsedContact,
    isProcessing: Boolean,
    onCancel: () -> Unit,
    onAddContact: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(TorXPrimarySoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = TorXPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Contact found",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TorXPrimary
                )
                Text(
                    text = contact.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = TorXPrimary, modifier = Modifier.size(20.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        val keySnippet = CryptoManager.toHex(contact.signingPublicKey).take(16)
        Text(
            text = "Identity Key: $keySnippet...",
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText
        )

        contact.onionAddress?.let { onion ->
            if (onion.isNotBlank()) {
                Text(
                    text = "Tor Route: ${onion.take(16)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) {
                Text("Cancel", color = SecondaryText)
            }
            Button(
                onClick = onAddContact,
                colors = ButtonDefaults.buttonColors(containerColor = TorXPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1.2f),
                enabled = !isProcessing
            ) {
                Text("Add Contact", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GroupDetectedCard(
    groupId: String,
    groupName: String?,
    inviterKey: String,
    isProcessing: Boolean,
    onCancel: () -> Unit,
    onJoinGroup: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(TorXPrimarySoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Group, contentDescription = null, tint = TorXPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Group invitation found",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TorXPrimary
                )
                Text(
                    text = groupName ?: "Group ${groupId.take(8)}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = TorXPrimary, modifier = Modifier.size(20.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Group ID: ${groupId.take(16)}...",
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText
        )
        Text(
            text = "Inviter: ${inviterKey.take(16)}...",
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText
        )

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) {
                Text("Cancel", color = SecondaryText)
            }
            Button(
                onClick = onJoinGroup,
                colors = ButtonDefaults.buttonColors(containerColor = TorXPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1.2f),
                enabled = !isProcessing
            ) {
                Text("Join Group", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ViewfinderOverlay(
    isDetected: Boolean,
    modifier: Modifier = Modifier
) {
    val cornerColor = if (isDetected) Color(0xFF16A34A) else TorXPrimary

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val boxSize = (canvasWidth.coerceAtMost(canvasHeight) * 0.68f).coerceIn(200.dp.toPx(), 300.dp.toPx())
        val left = (canvasWidth - boxSize) / 2f
        val top = (canvasHeight - boxSize) / 2f
        val right = left + boxSize
        val bottom = top + boxSize
        val cornerLength = 26.dp.toPx()
        val strokeWidth = 3.5.dp.toPx()

        // Top-left corner
        drawLine(cornerColor, Offset(left, top), Offset(left + cornerLength, top), strokeWidth, cap = StrokeCap.Round)
        drawLine(cornerColor, Offset(left, top), Offset(left, top + cornerLength), strokeWidth, cap = StrokeCap.Round)

        // Top-right corner
        drawLine(cornerColor, Offset(right, top), Offset(right - cornerLength, top), strokeWidth, cap = StrokeCap.Round)
        drawLine(cornerColor, Offset(right, top), Offset(right, top + cornerLength), strokeWidth, cap = StrokeCap.Round)

        // Bottom-left corner
        drawLine(cornerColor, Offset(left, bottom), Offset(left + cornerLength, bottom), strokeWidth, cap = StrokeCap.Round)
        drawLine(cornerColor, Offset(left, bottom), Offset(left, bottom - cornerLength), strokeWidth, cap = StrokeCap.Round)

        // Bottom-right corner
        drawLine(cornerColor, Offset(right, bottom), Offset(right - cornerLength, bottom), strokeWidth, cap = StrokeCap.Round)
        drawLine(cornerColor, Offset(right, bottom), Offset(right, bottom - cornerLength), strokeWidth, cap = StrokeCap.Round)
    }
}

@OptIn(ExperimentalGetImage::class)
@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun UniversalQrCameraPreview(
    modifier: Modifier,
    isPaused: Boolean,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val isPausedRef = remember { AtomicBoolean(isPaused) }
    LaunchedEffect(isPaused) {
        isPausedRef.set(isPaused)
    }

    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(viewContext)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage == null || isPausedRef.get()) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            val inputImage = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )
                            scanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    if (isPausedRef.get()) return@addOnSuccessListener
                                    for (barcode in barcodes) {
                                        val raw = barcode.rawValue?.trim() ?: continue
                                        if (raw.isNotBlank()) {
                                            onQrScanned(raw)
                                            break
                                        }
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        }
                    }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analyzer
                    )
                } catch (_: Exception) {
                    cameraProvider.unbindAll()
                }
            }, ContextCompat.getMainExecutor(viewContext))
            previewView
        }
    )
}

private suspend fun parsePayload(raw: String, db: AppDatabase): QrDetectedPayload? = withContext(Dispatchers.IO) {
    val trimmed = raw.trim()

    // 1. Try Contact QR
    val contact = CryptoManager.parseContactString(trimmed)
    if (contact != null) {
        return@withContext QrDetectedPayload.Contact(contact, trimmed)
    }

    // 2. Try Group Invite Token (JSON)
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        try {
            val json = JSONObject(trimmed)
            if (json.has("inviteId") && json.has("groupId") && json.has("inviterKey")) {
                val groupId = json.getString("groupId")
                val inviterKey = json.getString("inviterKey")
                val localGroup = db.groupDao().getGroup(groupId)
                return@withContext QrDetectedPayload.Group(
                    token = trimmed,
                    groupId = groupId,
                    inviterKey = inviterKey,
                    groupName = localGroup?.name
                )
            }
        } catch (_: Exception) {
            // Not a group JSON
        }
    }

    null
}
