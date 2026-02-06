package com.sonicmusic.app.presentation.player.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onSkipPreviousClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onSkipPreviousClick) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Previous",
                modifier = Modifier.size(32.dp)
            )
        }

        IconButton(
            onClick = onPlayPauseClick,
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        IconButton(onClick = onSkipNextClick) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
