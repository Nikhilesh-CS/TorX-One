package com.torxone.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.ui.theme.AccentCyan
import com.torxone.app.ui.theme.CardSurface
import com.torxone.app.ui.theme.MutedGray
import com.torxone.app.ui.theme.SoftWhite
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrContactScannerDialog(
    onDismiss: () -> Unit,
    onContactScanned: (String) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = AccentCyan)
                Spacer(Modifier.size(10.dp))
                Text("Scan Contact QR", color = SoftWhite)
            }
        },
        text = {
            if (hasCameraPermission) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Point the camera at a TorX One identity QR code. The contact key will fill automatically.",
                        color = MutedGray
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black)
                            .border(1.dp, AccentCyan.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                    ) {
                        ContactQrCameraPreview(
                            modifier = Modifier.fillMaxSize(),
                            onQrDetected = { value ->
                                onContactScanned(value)
                            }
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Camera permission is required to scan identity QR codes.",
                        color = MutedGray
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Allow Camera")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MutedGray)
            }
        }
    )
}

@OptIn(ExperimentalGetImage::class)
@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun ContactQrCameraPreview(
    modifier: Modifier,
    onQrDetected: (String) -> Unit
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
    val hasScanned = remember { AtomicBoolean(false) }

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
                            if (mediaImage == null || hasScanned.get()) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            val inputImage = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )
                            scanner.process(inputImage)
                                .addOnSuccessListener { barcodes ->
                                    val value = barcodes
                                        .asSequence()
                                        .mapNotNull { it.rawValue?.trim() }
                                        .firstOrNull { CryptoManager.parseContactString(it) != null }
                                    if (value != null && hasScanned.compareAndSet(false, true)) {
                                        onQrDetected(value)
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
