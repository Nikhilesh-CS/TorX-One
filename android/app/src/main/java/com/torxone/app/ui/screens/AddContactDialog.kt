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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torxone.app.ui.theme.AppBackground
import com.torxone.app.ui.theme.BorderColor
import com.torxone.app.ui.theme.PrimaryText
import com.torxone.app.ui.theme.SecondaryText
import com.torxone.app.ui.theme.SurfaceCard
import com.torxone.app.ui.theme.TextMuted
import com.torxone.app.ui.theme.TorXPrimary

@Composable
fun AddContactDialog(
    onDismiss: () -> Unit,
    onContactAdded: (String) -> Unit,
    onScanQrClick: (() -> Unit)? = null
) {
    var contactString by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        title = {
            Text(
                "Add Contact",
                color = PrimaryText,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column {
                Text(
                    "Paste a TorX One contact key or scan an identity QR code.",
                    fontSize = 14.sp,
                    color = SecondaryText
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = contactString,
                    onValueChange = { contactString = it },
                    label = { Text("Contact Key", color = SecondaryText) },
                    placeholder = { Text("Paste contact key or scan...", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (onScanQrClick != null) {
                            IconButton(onClick = onScanQrClick) {
                                Icon(
                                    Icons.Rounded.QrCodeScanner,
                                    contentDescription = "Scan contact QR",
                                    tint = TorXPrimary
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PrimaryText,
                        unfocusedTextColor = PrimaryText,
                        focusedContainerColor = AppBackground,
                        unfocusedContainerColor = AppBackground,
                        focusedBorderColor = TorXPrimary,
                        unfocusedBorderColor = BorderColor,
                        cursorColor = TorXPrimary
                    ),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onContactAdded(contactString) },
                enabled = contactString.isNotBlank()
            ) {
                Text(
                    "Add",
                    color = if (contactString.isNotBlank()) TorXPrimary else TextMuted,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SecondaryText)
            }
        }
    )
}
