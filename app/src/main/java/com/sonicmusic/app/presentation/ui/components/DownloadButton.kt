package com.sonicmusic.app.presentation.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.data.local.entity.DownloadStatus

@Composable
fun DownloadButton(
    status: DownloadStatus?,
    progress: Float, // 0f to 1f
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (status) {
                null, DownloadStatus.REMOVED, DownloadStatus.UNKNOWN -> {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DownloadStatus.PENDING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Preparing download",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                }
                DownloadStatus.QUEUED -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Queued",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                DownloadStatus.DOWNLOADING -> {
                    val animatedProgress by animateFloatAsState(targetValue = progress, label = "DownloadProgress")
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Rounded.Pause,
                        contentDescription = "Downloading: ${ (progress * 100).toInt() }%",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                DownloadStatus.PAUSED -> {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Paused",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DownloadStatus.RETRYING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Retrying download",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                DownloadStatus.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                DownloadStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "Download failed",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
