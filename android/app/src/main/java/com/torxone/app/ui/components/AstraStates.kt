package com.torxone.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.torxone.app.ui.theme.AstraTheme

@Composable
fun AstraLoadingState(
    modifier: Modifier = Modifier,
    message: String = "Loading..."
) {
    Column(
        modifier = modifier.fillMaxSize().padding(AstraTheme.spacing.standard),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
            modifier = Modifier.size(AstraTheme.iconSizes.extraLarge)
        )
        Spacer(modifier = Modifier.height(AstraTheme.spacing.standard))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun AstraEmptyState(
    modifier: Modifier = Modifier,
    title: String = "Nothing here yet",
    message: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxSize().padding(AstraTheme.spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (message != null) {
            Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(AstraTheme.spacing.large))
            action()
        }
    }
}

@Composable
fun AstraErrorState(
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    message: String,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxSize().padding(AstraTheme.spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(AstraTheme.spacing.large))
            AstraPrimaryButton(
                text = "Retry",
                onClick = onRetry
            )
        }
    }
}

@Composable
fun AstraOfflineState(
    modifier: Modifier = Modifier,
    message: String = "You are currently offline. Check your network or mesh connection."
) {
    AstraEmptyState(
        modifier = modifier,
        title = "Offline",
        message = message
    )
}
