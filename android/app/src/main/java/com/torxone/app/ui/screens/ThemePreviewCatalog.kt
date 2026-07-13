package com.torxone.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.torxone.app.ui.components.*
import com.torxone.app.ui.theme.AstraTheme

@Composable
fun ThemePreviewCatalog() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(AstraTheme.spacing.standard),
        verticalArrangement = Arrangement.spacedBy(AstraTheme.spacing.large)
    ) {
        item {
            Text("Buttons", style = AstraTheme.typography.titleLarge, color = AstraTheme.colors.onBackground)
            AstraPrimaryButton(text = "Primary Button", onClick = {})
            Spacer(modifier = Modifier.height(AstraTheme.spacing.small))
            AstraSecondaryButton(text = "Secondary Button", onClick = {})
        }

        item {
            Text("TextFields", style = AstraTheme.typography.titleLarge, color = AstraTheme.colors.onBackground)
            AstraTextField(
                value = "",
                onValueChange = {},
                placeholder = "Enter something..."
            )
        }

        item {
            Text("Avatars", style = AstraTheme.typography.titleLarge, color = AstraTheme.colors.onBackground)
            Row(horizontalArrangement = Arrangement.spacedBy(AstraTheme.spacing.small)) {
                AstraAvatar(name = "User", size = AstraTheme.iconSizes.extraLarge, isOnline = true)
                AstraAvatar(name = "Bot", size = AstraTheme.iconSizes.large, isOnline = false)
            }
        }

        item {
            Text("States", style = AstraTheme.typography.titleLarge, color = AstraTheme.colors.onBackground)
            Box(modifier = Modifier.height(AstraTheme.spacing.massive3)) {
                AstraEmptyState(
                    title = "Nothing Found",
                    message = "We couldn't find anything to show here."
                )
            }
        }

        item {
            Box(modifier = Modifier.height(AstraTheme.spacing.massive3)) {
                AstraErrorState(
                    title = "Network Error",
                    message = "Could not connect to the mesh network.",
                    onRetry = {}
                )
            }
        }
    }
}
