package com.sonicmusic.app.presentation.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.usecase.SleepTimerManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Sleep Timer Bottom Sheet
 *
 * Material 3 bottom sheet for configuring the sleep timer.
 * Features:
 * - Preset duration chips
 * - Active timer display with remaining time
 * - Add 5 minutes button when timer is active
 * - Cancel timer option
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onSelectDuration: (Duration) -> Unit,
    onCancel: () -> Unit,
    onAddFiveMinutes: () -> Unit,
    isTimerActive: Boolean,
    remainingTime: Duration?,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    if (!isVisible) return

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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Bedtime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Sleep Timer",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (isTimerActive) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Cancel timer",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Active timer display
            if (isTimerActive && remainingTime != null) {
                ActiveTimerDisplay(
                    remainingTime = remainingTime,
                    onAddFiveMinutes = onAddFiveMinutes,
                    onCancel = onCancel
                )
            } else {
                // Duration presets
                Text(
                    text = "Stop playing after",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                DurationChips(onSelectDuration = onSelectDuration)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationChips(
    onSelectDuration: (Duration) -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = listOf(
        15.minutes to "15 min",
        30.minutes to "30 min",
        45.minutes to "45 min",
        60.minutes to "1 hour",
        120.minutes to "2 hours"
    )

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { (duration, label) ->
            FilterChip(
                selected = false,
                onClick = { onSelectDuration(duration) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

@Composable
private fun ActiveTimerDisplay(
    remainingTime: Duration,
    onAddFiveMinutes: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Remaining time display
        Text(
            text = "Music will stop in",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = formatDuration(remainingTime),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onAddFiveMinutes) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("5 min")
            }

            TextButton(onClick = onCancel) {
                Text(
                    text = "Cancel timer",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Compact sleep timer indicator for the player screen.
 * Shows remaining time when timer is active.
 */
@Composable
fun SleepTimerIndicator(
    remainingTime: Duration?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (remainingTime == null) return

    TextButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Outlined.Bedtime,
            contentDescription = "Sleep timer active",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = formatDuration(remainingTime),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format("%d:%02d", minutes, seconds)
    }
}
