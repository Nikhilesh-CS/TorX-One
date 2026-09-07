package com.torxone.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.security.BiometricAuthManager
import com.torxone.app.security.BiometricCapability
import com.torxone.app.ui.components.PremiumAuroraBackground
import com.torxone.app.ui.components.premiumGlass
import com.torxone.app.ui.theme.AstraTheme

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
                errorText = "Authentication failed. Try again or use password."
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

    PremiumAuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .premiumGlass(radius = 28.dp, alpha = 0.12f)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .premiumGlass(radius = 36.dp, alpha = 0.20f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        modifier = Modifier.size(36.dp),
                        tint = AstraTheme.colors.primary
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "AstraMesh is Locked",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Unlock to view your messages and identity",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(28.dp))

                if (errorText != null) {
                    Text(
                        text = errorText ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                if (!usePasswordFallback && canUseBiometrics) {
                    Button(
                        onClick = { triggerBiometrics() },
                        colors = ButtonDefaults.buttonColors(containerColor = AstraTheme.colors.primary),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Unlock with Biometrics", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(
                        onClick = {
                            usePasswordFallback = true
                            errorText = null
                        }
                    ) {
                        Text("Use backup password", color = Color.White.copy(alpha = 0.75f))
                    }
                } else {
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            errorText = null
                        },
                        label = { Text("Backup Password", color = Color.White.copy(alpha = 0.7f)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AstraTheme.colors.primary,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { submitPassword() },
                        colors = ButtonDefaults.buttonColors(containerColor = AstraTheme.colors.primary),
                        shape = RoundedCornerShape(20.dp),
                        enabled = passwordInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text("Unlock", fontWeight = FontWeight.SemiBold)
                    }

                    if (canUseBiometrics) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(
                            onClick = {
                                usePasswordFallback = false
                                errorText = null
                            }
                        ) {
                            Text("Use Biometrics", color = Color.White.copy(alpha = 0.75f))
                        }
                    }
                }
            }
        }
    }
}
