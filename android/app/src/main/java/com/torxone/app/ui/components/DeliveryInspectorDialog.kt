package com.torxone.app.ui.components

import androidx.compose.runtime.Composable
import com.torxone.app.data.entity.MessageEntity

/**
 * Diagnostic Delivery Inspector.
 * Displays precise timing and lifecycle transitions for a message.
 */
@Composable
fun DeliveryInspectorDialog(
    message: MessageEntity,
    onDismiss: () -> Unit
) {
    MessageInfoDialog(message = message, onDismiss = onDismiss)
}
