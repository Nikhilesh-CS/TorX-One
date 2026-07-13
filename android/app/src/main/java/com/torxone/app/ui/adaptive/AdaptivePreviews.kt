package com.torxone.app.ui.adaptive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.torxone.app.engine.MessageLifecycleState
import com.torxone.app.ui.theme.TorXOneTheme
import com.torxone.app.ui.theme.AstraTheme

@PreviewScreenSizes
@PreviewFontScale
@Composable
fun AdaptiveChatBubblePreview() {
    TorXOneTheme {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AdaptiveChatBubble(
                    isMine = false,
                    timestamp = "10:24",
                    lifecycleState = MessageLifecycleState.DELIVERED,
                    replyContent = {
                        ReplyPreview(
                            senderName = "Alice",
                            messageText = "Let's meet at 5?",
                            accentColor = AstraTheme.colors.primary
                        )
                    }
                ) {
                    Text(
                        text = "Sounds good! I'll be there.",
                        color = AstraTheme.colors.onSurfaceVariant,
                        style = AstraTheme.typography.bodyMedium
                    )
                }

                AdaptiveChatBubble(
                    isMine = true,
                    timestamp = "10:25",
                    lifecycleState = MessageLifecycleState.READ,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(
                        text = "Perfect, see you soon.",
                        color = AstraTheme.colors.onPrimary,
                        style = AstraTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
