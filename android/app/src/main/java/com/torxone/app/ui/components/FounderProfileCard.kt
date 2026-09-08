package com.torxone.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torxone.app.identity.profile.FounderProfile
import com.torxone.app.ui.theme.BorderColor
import com.torxone.app.ui.theme.PrimaryText
import com.torxone.app.ui.theme.SecondaryText
import com.torxone.app.ui.theme.SuccessGreen
import com.torxone.app.ui.theme.SurfaceCard
import com.torxone.app.ui.theme.SurfaceSecondary
import com.torxone.app.ui.theme.TorXPrimary
import com.torxone.app.ui.theme.WarningAmber

private val FounderGold = WarningAmber
private val FounderAmber = Color(0xFFB45309)
private val VerifiedBlue = TorXPrimary
private val SecureGreen = SuccessGreen
private val FounderSurface = SurfaceCard
private val GlassHighlight = SurfaceSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FounderBadge(
    modifier: Modifier = Modifier
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(FounderProfile.tooltip)
            }
        },
        state = tooltipState,
        modifier = modifier
    ) {
        Surface(
            shape = CircleShape,
            color = FounderGold.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, FounderGold.copy(alpha = 0.58f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "\uD83D\uDC51 Founder",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = FounderGold
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = "Verified Founder",
                    tint = VerifiedBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FounderProfileCard(
    modifier: Modifier = Modifier,
    torConnected: Boolean,
    decentralizedEnabled: Boolean = true
) {
    val cardShape = RoundedCornerShape(28.dp)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BoxedFounderIcon()
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Official Founder Account",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Verified privacy-first identity",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FounderBadge()
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = FounderProfile.bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                FounderMetricRow("Founder Since", FounderProfile.launchDate, Icons.Rounded.Stars, FounderGold)
                FounderMetricRow("Security Score", "Maximum", Icons.Rounded.Shield, SecureGreen)
                FounderMetricRow("End-to-End Encryption", "Active", Icons.Rounded.Lock, SecureGreen)
                FounderMetricRow(
                    "Tor Hidden Service",
                    if (torConnected) "Connected" else "Pending",
                    Icons.Rounded.Router,
                    if (torConnected) SecureGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
                FounderMetricRow(
                    "Decentralized Network",
                    if (decentralizedEnabled) "Enabled" else "Pending",
                    Icons.Rounded.Hub,
                    if (decentralizedEnabled) SecureGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FounderChip("Verified Founder", FounderGold)
                    FounderChip("Early Supporter", VerifiedBlue)
                    FounderChip("Privacy First", SecureGreen)
                }
            }
        }
    }
}

@Composable
private fun BoxedFounderIcon() {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = FounderGold.copy(alpha = 0.17f),
        border = BorderStroke(1.dp, FounderGold.copy(alpha = 0.46f)),
        modifier = Modifier.size(50.dp)
    ) {
        Box(
            modifier = Modifier.background(
                Brush.radialGradient(
                    listOf(
                        FounderGold.copy(alpha = 0.34f),
                        Color.Transparent
                    )
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Text("\uD83D\uDC51", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun FounderMetricRow(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = color.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.22f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .padding(6.dp)
                    .size(17.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun FounderChip(
    text: String,
    color: Color
) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Security,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(containerColor = color.copy(alpha = 0.11f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f))
    )
}
