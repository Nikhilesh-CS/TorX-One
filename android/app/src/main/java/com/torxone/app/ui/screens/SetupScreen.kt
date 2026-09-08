package com.torxone.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.crypto.CryptoManager
import com.torxone.app.identity.IdentityManager
import com.torxone.app.identity.backup.IdentityRestoreManager
import com.torxone.app.ui.theme.AppBackground
import com.torxone.app.ui.theme.BluetoothAccent
import com.torxone.app.ui.theme.BorderColor
import com.torxone.app.ui.theme.ErrorRed
import com.torxone.app.ui.theme.PrimaryText
import com.torxone.app.ui.theme.SecondaryText
import com.torxone.app.ui.theme.SurfaceCard
import com.torxone.app.ui.theme.SurfaceSecondary
import com.torxone.app.ui.theme.TextMuted
import com.torxone.app.ui.theme.TorAccent
import com.torxone.app.ui.theme.TorXPrimary
import com.torxone.app.ui.theme.TorXPrimaryDark
import com.torxone.app.ui.theme.TorXPrimarySoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetupScreen(
    identityManager: IdentityManager,
    onIdentityCreated: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Brand header
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "TorX One",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = PrimaryText,
                letterSpacing = 0.5.sp
            )

            // Pager takes up central area
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
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Page Indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        val isSelected = pagerState.currentPage == index
                        val indicatorWidth by animateDpAsState(
                            targetValue = if (isSelected) 24.dp else 8.dp,
                            label = "indicatorWidth"
                        )
                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(indicatorWidth)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) TorXPrimary else BorderColor)
                        )
                    }
                }

                // Next Button (hidden on the 4th page)
                if (pagerState.currentPage < 3) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    ) {
                        Text(
                            text = "Next →",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TorXPrimary,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(64.dp))
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
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(TorXPrimarySoft)
                .border(1.dp, BorderColor, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Security,
                contentDescription = "TorX One Security",
                tint = TorXPrimary,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Communicate Freely",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryText,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Private, end-to-end encrypted messaging without central servers, accounts, or trackers. Your conversations belong exclusively to you.",
            style = MaterialTheme.typography.bodyLarge,
            color = SecondaryText,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}

@Composable
fun OnboardingPage2() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFE0F2FE))
                .border(1.dp, BorderColor, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Wifi,
                contentDescription = "Connect Nearby",
                tint = BluetoothAccent,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Connect Nearby",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryText,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Communicate directly with nearby peers using Bluetooth and Wi-Fi Direct—even completely offline when internet infrastructure is down.",
            style = MaterialTheme.typography.bodyLarge,
            color = SecondaryText,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}

@Composable
fun OnboardingPage3() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFF3E8FF))
                .border(1.dp, BorderColor, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Public,
                contentDescription = "Tor Onion Routing",
                tint = TorAccent,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Reach The World Securely",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = PrimaryText,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Private Onion Routes enable global peer-to-peer connectivity with layered cryptographic anonymity and zero metadata leakage.",
            style = MaterialTheme.typography.bodyLarge,
            color = SecondaryText,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}

@Composable
fun OnboardingPage4(identityManager: IdentityManager, onIdentityCreated: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val biometricAuthManager = remember(activity) { activity?.let { com.torxone.app.security.BiometricAuthManager(it) } }
    val settingsManager = remember(context) { com.torxone.app.data.SettingsManager(context) }
    val scope = rememberCoroutineScope()
    var showBiometricPromptDialog by remember { mutableStateOf(false) }

    fun finalizeIdentity(enableAppLock: Boolean) {
        val cleanName = name.trim()
        if (cleanName.isNotBlank() && password.isNotBlank()) {
            val identity = CryptoManager.generateIdentity(cleanName)
            identityManager.saveIdentity(identity)
            if (enableAppLock && biometricAuthManager != null) {
                biometricAuthManager.setupAppLockWithPassword(password)
                scope.launch {
                    settingsManager.setAppLockEnabled(true)
                }
            }
            focusManager.clearFocus(force = true)
            onIdentityCreated()
        }
    }

    fun handleCreateClick() {
        if (biometricAuthManager?.canAuthenticate() == com.torxone.app.security.BiometricCapability.Available) {
            showBiometricPromptDialog = true
        } else {
            finalizeIdentity(enableAppLock = false)
        }
    }

    if (showBiometricPromptDialog) {
        AlertDialog(
            onDismissRequest = {
                showBiometricPromptDialog = false
                finalizeIdentity(enableAppLock = false)
            },
            icon = {
                Icon(
                    Icons.Rounded.Fingerprint,
                    contentDescription = null,
                    tint = TorXPrimary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Enable Biometric Lock?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryText
                )
            },
            text = {
                Text(
                    text = "Would you like to lock TorX One with fingerprint or face recognition? Only you will be able to unlock the app, and your password serves as the secure backup key.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryText,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBiometricPromptDialog = false
                        finalizeIdentity(enableAppLock = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TorXPrimary)
                ) {
                    Text("Enable Biometrics", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBiometricPromptDialog = false
                        finalizeIdentity(enableAppLock = false)
                    }
                ) {
                    Text("Skip for Now", color = SecondaryText)
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = SurfaceCard,
            tonalElevation = 0.dp
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Create your identity",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryText,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Your identity belongs entirely to you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryText,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("Choose a display name", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PrimaryText,
                        unfocusedTextColor = PrimaryText,
                        focusedContainerColor = SurfaceCard,
                        unfocusedContainerColor = AppBackground,
                        focusedBorderColor = TorXPrimary,
                        unfocusedBorderColor = BorderColor,
                        focusedLabelColor = TorXPrimary,
                        unfocusedLabelColor = TextMuted,
                        cursorColor = TorXPrimary
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    textStyle = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Password field
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    placeholder = { Text("Choose an identity password", color = TextMuted) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PrimaryText,
                        unfocusedTextColor = PrimaryText,
                        focusedContainerColor = SurfaceCard,
                        unfocusedContainerColor = AppBackground,
                        focusedBorderColor = TorXPrimary,
                        unfocusedBorderColor = BorderColor,
                        focusedLabelColor = TorXPrimary,
                        unfocusedLabelColor = TextMuted,
                        cursorColor = TorXPrimary
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { handleCreateClick() }),
                    textStyle = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { handleCreateClick() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    enabled = name.trim().isNotBlank() && password.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TorXPrimary,
                        contentColor = Color.White,
                        disabledContainerColor = SurfaceSecondary,
                        disabledContentColor = TextMuted
                    )
                ) {
                    Text(
                        text = "Create Identity",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
            Text(
                text = "Restore Existing Identity",
                color = TorXPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }

        if (showRestoreDialog) {
            AlertDialog(
                onDismissRequest = { if (!isRestoring) showRestoreDialog = false },
                title = {
                    Text(
                        text = "Restore Identity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryText
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Enter the password used to encrypt the backup:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SecondaryText
                        )
                        Spacer(modifier = Modifier.height(12.dp))
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
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PrimaryText,
                                unfocusedTextColor = PrimaryText,
                                focusedContainerColor = SurfaceCard,
                                unfocusedContainerColor = AppBackground,
                                focusedBorderColor = TorXPrimary,
                                unfocusedBorderColor = BorderColor,
                                focusedLabelColor = TorXPrimary,
                                unfocusedLabelColor = TextMuted,
                                cursorColor = TorXPrimary
                            )
                        )
                        if (restoreError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = restoreError!!,
                                color = ErrorRed,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (isRestoring) {
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                color = TorXPrimary
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        enabled = restorePassword.isNotEmpty() && !isRestoring,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TorXPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Select Backup File")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showRestoreDialog = false },
                        enabled = !isRestoring
                    ) {
                        Text("Cancel", color = SecondaryText)
                    }
                },
                shape = RoundedCornerShape(24.dp),
                containerColor = SurfaceCard,
                tonalElevation = 0.dp
            )
        }
    }
}
