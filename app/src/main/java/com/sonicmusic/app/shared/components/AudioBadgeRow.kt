package com.sonicmusic.app.shared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicmusic.app.features.player.audio.AudioCapabilityDetector.AudioCapability

/** Shows audio output capabilities when the current device reports any. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioBadgeRow(
    capability: AudioCapability,
    modifier: Modifier = Modifier
) {
    val badges = capability.badgeLabels.filter { it != "Spatial Audio" }
    if (badges.isEmpty()) return

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        badges.forEach { AudioBadgePill(label = it) }
    }
}

/**
 * A single pill-shaped badge with subtle styling that matches the Material 3 theme.
 *
 * Design philosophy:
 * - Low visual emphasis — these are informational badges, not CTAs
 * - Uses [MaterialTheme.colorScheme.surfaceContainerHighest] for the background
 *   to blend with the player's existing surface hierarchy
 * - Small 10sp text with medium weight for readability without dominance
 * - Rounded pill shape (50% corner radius) for a modern, Apple Music-inspired look
 */
@Composable
private fun AudioBadgePill(
    label: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val badgeTextColor = colorScheme.onSurface.copy(alpha = 0.85f)
    val badgeBackground = colorScheme.onSurface.copy(alpha = 0.08f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(badgeBackground)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val dotColor = when {
            label.contains("DTS", ignoreCase = true) -> Color(0xFFE85D04)   // DTS orange
            label.contains("Hi-Res", ignoreCase = true) -> Color(0xFF48BB78) // Hi-Res green
            else -> colorScheme.primary
        }

        // Tiny colored dot as visual flair
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(5.dp)
        ) {
            drawCircle(color = dotColor)
        }

        Spacer(modifier = Modifier.width(5.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            ),
            color = badgeTextColor,
            maxLines = 1
        )
    }
}
