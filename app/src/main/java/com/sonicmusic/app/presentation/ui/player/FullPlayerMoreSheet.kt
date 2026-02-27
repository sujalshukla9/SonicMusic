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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.data.downloadmanager.DownloadProgress
import com.sonicmusic.app.data.downloadmanager.DownloadStatus
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.ui.components.SongThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerMoreSheet(
    isVisible: Boolean,
    song: Song?,
    isLiked: Boolean,
    isDownloaded: Boolean,
    downloadProgress: DownloadProgress?,
    onDismiss: () -> Unit,
    onToggleLike: () -> Unit,
    onShare: () -> Unit,
    onStartRadio: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEqualizer: () -> Unit,
    onSpeedAndPitch: () -> Unit,
    onSleepTimer: () -> Unit,
    onGoToAlbum: () -> Unit,
    onMoreFromArtist: () -> Unit,
    onWatchOnYouTube: () -> Unit,
    onOpenInYouTubeMusic: () -> Unit,
    onDownloadOffline: () -> Unit,
    onRemoveDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible || song == null) return

    val colorScheme = MaterialTheme.colorScheme
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Delete confirmation dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DownloadDone,
                    contentDescription = null,
                    tint = colorScheme.primary
                )
            },
            title = { Text("Remove download?") },
            text = {
                Text("\"${song.title}\" will be removed from your downloads. You can download it again anytime.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onRemoveDownload()
                }) {
                    Text("Remove", color = colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colorScheme.surfaceContainerHigh,
        scrimColor = colorScheme.scrim.copy(alpha = 0.62f),
        contentColor = colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SongThumbnail(
                            artworkUrl = song.thumbnailUrl,
                            modifier = Modifier.size(58.dp),
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row {
                        IconButton(onClick = onToggleLike) {
                            Icon(
                                imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = "Like",
                                tint = if (isLiked) colorScheme.error else colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onShare) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "Share",
                                tint = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            FullPlayerMoreActionRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                label = "Start radio",
                onClick = onStartRadio
            )
            FullPlayerMoreActionRow(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                label = "Add to playlist",
                onClick = onAddToPlaylist,
                showTrailingArrow = true
            )
            FullPlayerMoreActionRow(
                icon = Icons.Rounded.Equalizer,
                label = "Equalizer",
                onClick = onEqualizer
            )
            FullPlayerMoreActionRow(
                icon = Icons.Rounded.Speed,
                label = "Speed & pitch",
                onClick = onSpeedAndPitch
            )
            FullPlayerMoreActionRow(
                icon = Icons.Rounded.Bedtime,
                label = "Sleep timer",
                onClick = onSleepTimer
            )
            FullPlayerMoreActionRow(
                icon = Icons.Rounded.Album,
                label = "Go to album",
                onClick = onGoToAlbum
            )
            FullPlayerMoreActionRow(
                icon = Icons.Rounded.Person,
                label = "More from ${song.artist}",
                onClick = onMoreFromArtist
            )
            FullPlayerMoreActionRow(
                icon = Icons.Rounded.PlayArrow,
                label = "Watch on YouTube",
                onClick = onWatchOnYouTube
            )
            FullPlayerMoreActionRow(
                icon = Icons.Rounded.MusicNote,
                label = "Open in YouTube Music",
                onClick = onOpenInYouTubeMusic
            )

            // ═══════════════════════════════════════════════════
            // DOWNLOAD ROW — three states: downloading, downloaded, not downloaded
            // ═══════════════════════════════════════════════════
            val isDownloading = downloadProgress != null &&
                    (downloadProgress.status == DownloadStatus.DOWNLOADING ||
                    downloadProgress.status == DownloadStatus.PENDING)

            if (isDownloading && downloadProgress != null) {
                // Show progress with percentage
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Downloading… ${downloadProgress.progress}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(start = 36.dp),
                        color = colorScheme.primary,
                        trackColor = colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round
                    )
                }
            } else if (isDownloaded) {
                FullPlayerMoreActionRow(
                    icon = Icons.Rounded.DownloadDone,
                    label = "Downloaded ✓",
                    onClick = { showDeleteConfirmation = true },
                    tint = colorScheme.primary
                )
            } else {
                FullPlayerMoreActionRow(
                    icon = Icons.Rounded.Download,
                    label = "Download",
                    onClick = onDownloadOffline
                )
            }
        }
    }
}

@Composable
private fun FullPlayerMoreActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    showTrailingArrow: Boolean = false,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface
            )
        }

        if (showTrailingArrow) {
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}
