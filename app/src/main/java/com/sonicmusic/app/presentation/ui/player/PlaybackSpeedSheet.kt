package com.sonicmusic.app.presentation.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Playback Speed Bottom Sheet
 *
 * Allows users to adjust playback speed from 0.5x to 2.0x
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedSheet(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sliderValue by remember(currentSpeed) { mutableFloatStateOf(currentSpeed) }

    // Preset speed values
    val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Playback Speed",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Current: ${formatSpeed(sliderValue)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Speed Slider
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = { newValue ->
                        // Snap to nearest 0.05
                        sliderValue = (newValue * 20).toInt() / 20f
                    },
                    onValueChangeFinished = {
                        onSpeedChange(sliderValue)
                    },
                    valueRange = 0.5f..2.0f,
                    steps = 29, // (2.0 - 0.5) / 0.05 - 1 = 29
                    modifier = Modifier.fillMaxWidth()
                )

                // Min/Max Labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "0.5x",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "2.0x",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Preset Chips
            Text(
                text = "Presets",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { speed ->
                    val isSelected = (sliderValue * 100).toInt() == (speed * 100).toInt()
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            sliderValue = speed
                            onSpeedChange(speed)
                        },
                        label = {
                            Text(
                                text = formatSpeed(speed),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Reset button
            if (sliderValue != 1.0f) {
                Text(
                    text = "Reset to Normal Speed",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            sliderValue = 1.0f
                            onSpeedChange(1.0f)
                        }
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

private fun formatSpeed(speed: Float): String {
    return when {
        speed == 1.0f -> "Normal"
        speed < 1.0f -> "${speed}x (Slower)"
        else -> "${speed}x (Faster)"
    }
}
