package com.torxone.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.theme.AccentViolet
import com.torxone.app.ui.theme.AstraTheme
import com.torxone.app.ui.theme.MutedGray
import com.torxone.app.ui.theme.SoftWhite

@Composable
fun AddContactDialog(
    onDismiss: () -> Unit,
    onContactAdded: (String) -> Unit
) {
    var contactString by remember { mutableStateOf("") }
    var showQrScanner by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Contact", color = SoftWhite) },
        text = {
            Column {
                Text(
                    "Paste a TorX One contact key or scan an identity QR code.",
                    fontSize = AstraTheme.typography.bodySmall.fontSize,
                    color = MutedGray
                )
                Spacer(modifier = Modifier.height(AstraTheme.spacing.medium))
                OutlinedTextField(
                    value = contactString,
                    onValueChange = { contactString = it },
                    label = { Text("Contact Key") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    trailingIcon = {
                        IconButton(onClick = { showQrScanner = true }) {
                            Icon(
                                Icons.Rounded.QrCodeScanner,
                                contentDescription = "Scan contact QR",
                                tint = AccentViolet
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SoftWhite,
                        unfocusedTextColor = SoftWhite,
                        focusedContainerColor = Color.White.copy(alpha = 0.10f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.07f),
                        focusedBorderColor = Color(0xFF8B5CF6),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.14f)
                    )
                )
            }
        },
        containerColor = Color(0xE6111827),
        shape = RoundedCornerShape(30.dp),
        titleContentColor = Color(0xFFF6F7FF),
        textContentColor = Color(0xFFB9C3D4),
        tonalElevation = 0.dp,
        confirmButton = {
            TextButton(
                onClick = { onContactAdded(contactString) },
                enabled = contactString.isNotBlank()
            ) {
                Text("Add", color = AccentViolet)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MutedGray)
            }
        }
    )

    if (showQrScanner) {
        QrContactScannerDialog(
            onDismiss = { showQrScanner = false },
            onContactScanned = { scannedContact ->
                contactString = scannedContact
                showQrScanner = false
            }
        )
    }
}
