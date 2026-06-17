package com.sonicmusic.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicmusic.app.player.audio.AudioCapabilityDetector.AudioCapability
import com.sonicmusic.app.R


/**
 * Displays a horizontal row of small "pill" badges indicating device audio capabilities
 * (e.g. "Dolby Atmos", "DTS:X", "Hi-Res").
 *
 * This composable is designed to sit directly below the album art in the Full Player screen.
 * It only renders if at least one capability badge is present — no empty row is shown.
 *
 * Badges animate in with a fade + slide when the capability state changes (e.g. when the
 * user plugs in Dolby-capable headphones mid-playback).
 *
 * @param capability The current audio capability detection result
 * @param modifier Modifier for the outer row
 */
@Composable
fun AudioBadgeRow(
    capability: AudioCapability,
    modifier: Modifier = Modifier
) {
    // Don't render anything if no capabilities detected — avoids empty spacer
    AnimatedVisibility(
        visible = capability.hasAnyCapability,
        enter = fadeIn(animationSpec = tween(400)) +
                slideInVertically(
                    animationSpec = tween(400),
                    initialOffsetY = { it / 2 }
                ),
        exit = fadeOut(animationSpec = tween(300))
    ) {
        Row(
            modifier = modifier.padding(top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            capability.badgeLabels.forEachIndexed { index, label ->
                if (index > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                AudioBadgePill(label = label)
            }
        }
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

    if (label.contains("Dolby", ignoreCase = true)) {
        Icon(
            painter = painterResource(id = R.drawable.ic_dolby_atmos),
            contentDescription = "Dolby Atmos",
            tint = badgeTextColor,
            modifier = modifier
                .padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
                .size(width = 106.dp, height = 15.dp)
        )
    } else {
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
}
