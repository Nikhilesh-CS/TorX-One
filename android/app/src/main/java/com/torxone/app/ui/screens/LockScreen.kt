package com.torxone.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.security.BiometricAuthManager
import com.torxone.app.security.BiometricCapability
import com.torxone.app.ui.theme.AppBackground
import com.torxone.app.ui.theme.BorderColor
import com.torxone.app.ui.theme.ErrorRed
import com.torxone.app.ui.theme.PrimaryText
import com.torxone.app.ui.theme.SecondaryText
import com.torxone.app.ui.theme.SurfaceCard
import com.torxone.app.ui.theme.SurfaceSecondary
import com.torxone.app.ui.theme.TextMuted
import com.torxone.app.ui.theme.TorXPrimary
import com.torxone.app.ui.theme.TorXPrimarySoft

@Composable
fun LockScreen(
    biometricAuthManager: BiometricAuthManager,
    onUnlock: () -> Unit
) {
    var errorText by remember { mutableStateOf<String?>(null) }
    val canUseBiometrics = remember {
        biometricAuthManager.canAuthenticate() == BiometricCapability.Available
    }
    var usePasswordFallback by remember { mutableStateOf(!canUseBiometrics) }
    var passwordInput by remember { mutableStateOf("") }

    fun triggerBiometrics() {
        errorText = null
        biometricAuthManager.unlockApp(
            onSuccess = { onUnlock() },
            onError = { err ->
                errorText = err
            },
            onFailedAttempt = {
                errorText = "Authentication failed. Please try again or use password."
            }
        )
    }

    fun submitPassword() {
        if (passwordInput.isBlank()) return
        val ok = biometricAuthManager.unlockAppWithPassword(passwordInput)
        if (ok) {
            errorText = null
            onUnlock()
        } else {
            errorText = "Incorrect backup password"
        }
    }

    LaunchedEffect(usePasswordFallback) {
        if (!usePasswordFallback && canUseBiometrics) {
            triggerBiometrics()
        }
    }

    Scaffold(
        containerColor = AppBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = TorXPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "TorX One Protected",
                    color = SecondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Center Card with proper typography & contrast
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                border = BorderStroke(1.dp, BorderColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(TorXPrimarySoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            modifier = Modifier.size(36.dp),
                            tint = TorXPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "TorX One is Locked",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryText,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Unlock to view your secure messages, contacts, and offline mesh routes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SecondaryText,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    if (errorText != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFEF2F2))
                                .border(1.dp, Color(0xFFFCA5A5), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = errorText ?: "",
                                color = ErrorRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (!usePasswordFallback && canUseBiometrics) {
                        Button(
                            onClick = { triggerBiometrics() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TorXPrimary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Icon(
                                Icons.Default.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Unlock with Biometrics",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        TextButton(
                            onClick = {
                                usePasswordFallback = true
                                errorText = null
                            }
                        ) {
                            Text(
                                "Use backup password",
                                color = TorXPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = {
                                passwordInput = it
                                errorText = null
                            },
                            label = { Text("Backup Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TorXPrimary,
                                unfocusedBorderColor = BorderColor,
                                focusedTextColor = PrimaryText,
                                unfocusedTextColor = PrimaryText,
                                focusedLabelColor = TorXPrimary,
                                unfocusedLabelColor = SecondaryText,
                                focusedContainerColor = SurfaceSecondary,
                                unfocusedContainerColor = SurfaceSecondary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { submitPassword() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TorXPrimary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            enabled = passwordInput.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text("Unlock", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }

                        if (canUseBiometrics) {
                            Spacer(modifier = Modifier.height(10.dp))
                            TextButton(
                                onClick = {
                                    usePasswordFallback = false
                                    errorText = null
                                }
                            ) {
                                Text(
                                    "Use Biometrics",
                                    color = TorXPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // Footer info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = "End-to-End Encrypted • Zero-Knowledge Mesh",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
