package com.torxone.app.ui.screens

import com.torxone.app.ui.theme.AstraTheme

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.identity.IdentityManager
import com.torxone.app.identity.backup.IdentityRestoreManager
import com.torxone.app.ui.components.PremiumAuroraBackground
import com.torxone.app.ui.components.premiumGlass
import com.torxone.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

import androidx.compose.foundation.ExperimentalFoundationApi

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetupScreen(
    identityManager: IdentityManager,
    onIdentityCreated: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()

    PremiumAuroraBackground {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pager takes up most of the screen
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    0 -> OnboardingPage1()
                    1 -> OnboardingPage2()
                    2 -> OnboardingPage3()
                    3 -> OnboardingPage4(
                        identityManager = identityManager,
                        onIdentityCreated = onIdentityCreated
                    )
                }
            }

            // Bottom Navigation Area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AstraTheme.spacing.massive1, vertical = AstraTheme.spacing.massive3),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Page Indicators
                Row(horizontalArrangement = Arrangement.spacedBy(AstraTheme.spacing.small)) {
                    repeat(4) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 10.dp else AstraTheme.spacing.small)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary 
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                )
                        )
                    }
                }

                // Next Button (hidden on last page)
                if (pagerState.currentPage < 3) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    ) {
                        Text(
                            "Next",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(AstraTheme.spacing.massive5))
                }
            }
        }
    }
}

@Composable
fun OnboardingPage1() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AstraTheme.spacing.massive1),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon/Graphic placeholder
        Box(
            modifier = Modifier
                .size(120.dp)
                .glassmorphism(cornerRadius = AstraTheme.spacing.massive1, backgroundColor = Color(0x0AFFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            Text("A", style = MaterialTheme.typography.headlineLarge, fontSize = AstraTheme.typography.headlineLarge.fontSize, color = MaterialTheme.colorScheme.onSurface)
        }
        
        Spacer(modifier = Modifier.height(AstraTheme.spacing.massive3))
        
        Text(
            text = "Communicate Without Central Servers",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
        
        Text(
            text = "Your conversations belong to you. No middlemen, no tracking, just pure peer-to-peer connection.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun OnboardingPage2() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AstraTheme.spacing.massive1),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .glassmorphism(cornerRadius = 60.dp, backgroundColor = BluetoothGlow),
            contentAlignment = Alignment.Center
        ) {
            Text("📶", fontSize = AstraTheme.typography.headlineLarge.fontSize)
        }
        
        Spacer(modifier = Modifier.height(AstraTheme.spacing.massive3))
        
        Text(
            text = "Connect Nearby",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
        
        Text(
            text = "Bluetooth and Wi-Fi Direct allow local communication without internet infrastructure.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun OnboardingPage3() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AstraTheme.spacing.massive1),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .glassmorphism(cornerRadius = 60.dp, backgroundColor = TorGlow),
            contentAlignment = Alignment.Center
        ) {
            Text("🧅", fontSize = AstraTheme.typography.headlineLarge.fontSize)
        }
        
        Spacer(modifier = Modifier.height(AstraTheme.spacing.massive3))
        
        Text(
            text = "Reach The World Securely",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
        
        Text(
            text = "Private Onion Routes enable long-distance decentralized communication.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun OnboardingPage4(identityManager: IdentityManager, onIdentityCreated: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    fun createIdentity() {
        val cleanName = name.trim()
        if (cleanName.isNotBlank() && password.isNotBlank()) {
            val identity = CryptoManager.generateIdentity(cleanName)
            identityManager.saveIdentity(identity)
            focusManager.clearFocus(force = true)
            onIdentityCreated()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AstraTheme.spacing.massive1),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .premiumGlass(radius = 30.dp, alpha = 0.12f)
                .padding(AstraTheme.spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Your Network\nYour Privacy\nYour Freedom",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                color = Color(0xFFF6F7FF),
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.height(AstraTheme.spacing.small))

            Text(
                text = "Welcome to TorX One",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(AstraTheme.spacing.massive2))

            // Name field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display Name", style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                    focusedContainerColor = Color.White.copy(alpha = 0.10f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.07f)
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                textStyle = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))

            // Password field
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Enter your password", style = MaterialTheme.typography.bodyMedium) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                    focusedContainerColor = Color.White.copy(alpha = 0.10f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.07f)
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { createIdentity() }),
                textStyle = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(AstraTheme.spacing.massive1))

            Button(
                onClick = {
                    createIdentity()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AstraTheme.spacing.massive4),
                enabled = name.trim().isNotBlank() && password.isNotBlank(),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    "Create Identity",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))

        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var showRestoreDialog by remember { mutableStateOf(false) }
        var restorePassword by remember { mutableStateOf("") }
        var restoreError by remember { mutableStateOf<String?>(null) }
        var isRestoring by remember { mutableStateOf(false) }

        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                isRestoring = true
                restoreError = null
                coroutineScope.launch {
                    try {
                        val restoreManager = IdentityRestoreManager(context)
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            val result = restoreManager.restoreBackup(inputStream, restorePassword.toCharArray())
                            if (result.isSuccess) {
                                withContext(Dispatchers.Main) {
                                    showRestoreDialog = false
                                    onIdentityCreated()
                                }
                            } else {
                                restoreError = result.exceptionOrNull()?.message ?: "Unknown error"
                            }
                        } else {
                            restoreError = "Could not read file."
                        }
                    } catch (e: Exception) {
                        restoreError = "Error: ${e.message}"
                    } finally {
                        isRestoring = false
                    }
                }
            }
        }

        TextButton(onClick = { showRestoreDialog = true }) {
            Text("Restore Existing Identity", color = MaterialTheme.colorScheme.primary, fontSize = AstraTheme.typography.bodyLarge.fontSize)
        }

        if (showRestoreDialog) {
            AlertDialog(
                onDismissRequest = { if (!isRestoring) showRestoreDialog = false },
                title = { Text("Restore Identity", color = Color(0xFFF6F7FF), fontWeight = FontWeight.Black) },
                text = {
                    Column {
                        Text("Enter the password used to encrypt the backup:", color = Color(0xFFB9C3D4))
                        Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                        OutlinedTextField(
                            value = restorePassword,
                            onValueChange = { restorePassword = it },
                            label = { Text("Backup Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            singleLine = true,
                            enabled = !isRestoring,
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                                focusedContainerColor = Color.White.copy(alpha = 0.10f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.07f)
                            )
                        )
                        if (restoreError != null) {
                            Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
                            Text(restoreError!!, color = AstraTheme.colors.error, fontSize = AstraTheme.typography.labelMedium.fontSize)
                        }
                        if (isRestoring) {
                            Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        enabled = restorePassword.isNotEmpty() && !isRestoring
                    ) {
                        Text("Select Backup File")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showRestoreDialog = false },
                        enabled = !isRestoring
                    ) {
                        Text("Cancel")
                    }
                },
                shape = RoundedCornerShape(30.dp),
                containerColor = Color(0xE6111827),
                titleContentColor = Color(0xFFF6F7FF),
                textContentColor = Color(0xFFB9C3D4),
                tonalElevation = 0.dp
            )
        }
    }
}
