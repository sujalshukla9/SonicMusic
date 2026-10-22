package com.sonicmusic.app.shared.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicmusic.app.features.player.audio.AudioCapabilityDetector.AudioCapability


import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.GraphicEq

/**
 * Displays a horizontal row of small "pill" badges indicating audio quality and device capabilities
 * (e.g. "OPUS 160", "AAC 256", "DTS:X", "Hi-Res").
 *
 * This composable is designed to sit directly below the album art in the Full Player screen.
 * It only renders if at least one capability or quality badge is present — no empty row is shown.
 *
 * Badges animate in with a fade + slide when the capability state changes (e.g. when the
 * user plugs in headphones mid-playback or stream info resolves).
 *
 * @param capability The current audio capability detection result
 * @param spatialAudioEnabled Whether spatial audio is currently active
 * @param qualityBadge The formatted stream quality badge (e.g. "OPUS 160", "AAC 256")
 * @param onQualityBadgeClick Callback when user taps the quality badge to inspect technical stream details
 * @param modifier Modifier for the outer row
 */
@Composable
fun AudioBadgeRow(
    capability: AudioCapability = AudioCapability(),
    spatialAudioEnabled: Boolean = false,
    qualityBadge: String? = null,
    onQualityBadgeClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val showSpatial = (capability.spatialLabel != null) && spatialAudioEnabled
    val hasHardwareBadge = capability.dtsLabel != null || capability.hiResLabel != null ||
            capability.outputLabel != null || showSpatial
    val hasVisibleBadge = hasHardwareBadge || !qualityBadge.isNullOrBlank()

    // Reserve constant height so playback controls never shift when badges toggle.
    // The Box always occupies 28.dp; the badges animate in/out inside it.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .requiredHeight(28.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = hasVisibleBadge,
            enter = fadeIn(animationSpec = tween(400)),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Render Stream Quality Badge (e.g. "OPUS 160", "AAC 256", "AAC 128", "Lossless FLAC")
                if (!qualityBadge.isNullOrBlank()) {
                    StreamQualityBadgePill(
                        badgeText = qualityBadge,
                        onClick = onQualityBadgeClick
                    )
                }

                // Render DTS / Hi-Res / External DAC badges
                val hardwareBadges = capability.badgeLabels.filter { it != "Spatial Audio" }
                hardwareBadges.forEachIndexed { _, label ->
                    Spacer(modifier = Modifier.width(8.dp))
                    AudioBadgePill(label = label)
                }

                // Spatial Audio badge — active when spatial audio is enabled
                if (showSpatial) {
                    Spacer(modifier = Modifier.width(8.dp))
                    SpatialAudioBadgePill(active = spatialAudioEnabled)
                }
            }
        }
    }
}

/**
 * Interactive pill badge for the current audio stream quality.
 * Tapping opens the SongDetailsSheet for complete technical inspection.
 */
@Composable
fun StreamQualityBadgePill(
    badgeText: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val badgeTextColor = colorScheme.onSurface.copy(alpha = 0.85f)
    val badgeBackground = colorScheme.onSurface.copy(alpha = 0.08f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(badgeBackground)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val dotColor = when {
            badgeText.contains("FLAC", ignoreCase = true) || badgeText.contains("Lossless", ignoreCase = true) -> Color(0xFF48BB78)
            badgeText.contains("256", ignoreCase = true) -> Color(0xFF00BCD4)
            badgeText.contains("OPUS", ignoreCase = true) -> colorScheme.primary
            else -> colorScheme.primary.copy(alpha = 0.8f)
        }

        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = "Audio Quality: $badgeText",
            tint = dotColor,
            modifier = Modifier.size(10.dp)
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = badgeText,
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

/**
 * Dedicated badge pill for the Android Spatializer (Spatial Audio) state.
 *
 * - **Active** (isAvailable AND isEnabled): teal-tinted pill, icon at full opacity.
 *   Meaning: the OS is currently routing this app's audio through the spatial pipeline.
 * - **Inactive** (isAvailable but NOT enabled): same pill at 45% alpha.
 *   Meaning: hardware supports it, but the user hasn't turned it on in system settings
 *   or the current output device doesn't support it right now.
 *
 * The badge is never shown when Spatializer is completely unavailable (API < 32 or
 * hardware has no spatial capability) — controlled by the call site.
 */
@Composable
private fun SpatialAudioBadgePill(
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    // Teal color matching the "spatial" aesthetic
    val spatialActiveColor = Color(0xFF00BCD4)   // Material Cyan 500
    val spatialInactiveColor = colorScheme.onSurface.copy(alpha = 0.35f)

    val dotColor = if (active) spatialActiveColor else spatialInactiveColor
    val textColor = if (active)
        colorScheme.onSurface.copy(alpha = 0.90f)
    else
        colorScheme.onSurface.copy(alpha = 0.42f)
    val bgColor = if (active)
        spatialActiveColor.copy(alpha = 0.12f)
    else
        colorScheme.onSurface.copy(alpha = 0.05f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.SurroundSound,
            contentDescription = null,
            tint = dotColor,
            modifier = Modifier.size(11.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Spatial Audio",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            ),
            color = textColor,
            maxLines = 1
        )
    }
}
