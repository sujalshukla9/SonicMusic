package com.sonicmusic.app.features.player.presentation.sheets

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicmusic.app.features.downloads.data.DownloadProgress
import com.sonicmusic.app.data.local.entity.DownloadStatus
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.shared.components.SongThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongOptionsBottomSheet(
    isVisible: Boolean,
    song: Song?,
    isLiked: Boolean,
    isDownloaded: Boolean,
    downloadProgress: DownloadProgress?,
    onDismiss: () -> Unit,
    onToggleLike: () -> Unit,
    onShare: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onGoToAlbum: () -> Unit,
    onMoreFromArtist: (artistName: String, artistId: String?) -> Unit,
    onWatchOnYouTube: () -> Unit,
    onOpenInYouTubeMusic: () -> Unit,
    onDownloadOffline: () -> Unit,
    onRemoveDownload: () -> Unit,
    // Conditional actions depending on context (Home vs Queue)
    onPlayNext: (() -> Unit)? = null,
    onEnqueue: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (!isVisible || song == null) return

    val colorScheme = MaterialTheme.colorScheme
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Delete confirmation dialog for download removal
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
            title = { Text("Remove from Offline?") },
            text = {
                Text("\"${song.title}\" will be deleted from your offline cache.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onRemoveDownload()
                }) {
                    Text(" Remove", color = colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(" Cancel")
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
            // ── Header: thumbnail + title + artist + like + share ──────────────────
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
                            modifier = Modifier
                                .size(58.dp)
                                .clip(RoundedCornerShape(10.dp)),
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
                                contentDescription = " Like",
                                tint = if (isLiked) colorScheme.error else colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onShare) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = " Share",
                                tint = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            // ── Play next / Enqueue / Start radio (Homepage Only) ─────────────────
            if (onPlayNext != null) {
                SongOptionsActionRow(
                    icon = Icons.Rounded.PlayArrow,
                    label = " Play next",
                    onClick = onPlayNext
                )
            }
            if (onEnqueue != null) {
                SongOptionsActionRow(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    label = " Enqueue",
                    onClick = onEnqueue
                )
            }
            if (onStartRadio != null) {
                SongOptionsActionRow(
                    icon = Icons.Rounded.GraphicEq,
                    label = " Start radio",
                    onClick = onStartRadio
                )
            }

            // ── Playlist / Album ──────────────────────────────────────────────────
            SongOptionsActionRow(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                label = " Add to playlist",
                onClick = onAddToPlaylist,
                showTrailingArrow = true
            )
            SongOptionsActionRow(
                icon = Icons.Rounded.Album,
                label = " Go to album",
                onClick = onGoToAlbum
            )

            // ── Dynamic More from Artist per artist ──────────────────────────────
            val artistNames = remember(song.artist) {
                val regex = Regex("(,\\s*|\\s+&\\s+|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+x\\s+|\\s+and\\s+|\\s*/\\s*|\\s*;\\s*|\\s*\\|\\s*)", RegexOption.IGNORE_CASE)
                val list = mutableListOf<String>()
                var lastEnd = 0
                regex.findAll(song.artist).forEach { match ->
                    if (match.range.first > lastEnd) {
                        list.add(song.artist.substring(lastEnd, match.range.first).trim())
                    }
                    lastEnd = match.range.last + 1
                }
                if (lastEnd < song.artist.length) {
                    list.add(song.artist.substring(lastEnd).trim())
                }
                list.filter { it.isNotBlank() }
            }
            artistNames.forEachIndexed { idx, artistName ->
                val resolvedId = song.artistId?.split(",")?.getOrNull(idx)?.trim()?.takeIf { it.isNotBlank() }
                SongOptionsActionRow(
                    icon = Icons.Rounded.Person,
                    label = " More from $artistName",
                    onClick = { onMoreFromArtist(artistName, resolvedId) }
                )
            }

            // ── YouTube Links ─────────────────────────────────────────────────────
            SongOptionsActionRow(
                icon = Icons.Rounded.PlayArrow,
                label = " Watch on YouTube",
                onClick = onWatchOnYouTube
            )
            SongOptionsActionRow(
                icon = Icons.Rounded.MusicNote,
                label = " Open in YouTube Music",
                onClick = onOpenInYouTubeMusic
            )

            // ── Caching / Download (Pre cache) ────────────────────────────────────
            val isDownloading = downloadProgress != null &&
                    (downloadProgress.status == DownloadStatus.DOWNLOADING ||
                    downloadProgress.status == DownloadStatus.PENDING)

            if (isDownloading && downloadProgress != null) {
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
                            text = " Caching… ${downloadProgress.progress}%",
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
                SongOptionsActionRow(
                    icon = Icons.Rounded.DownloadDone,
                    label = " Pre cached",
                    onClick = { showDeleteConfirmation = true },
                    tint = colorScheme.primary
                )
            } else {
                SongOptionsActionRow(
                    icon = Icons.Rounded.Download,
                    label = " Pre cache",
                    onClick = onDownloadOffline
                )
            }

            // ── Remove from queue (Queue Sheet Only) ──────────────────────────────
            if (onRemoveFromQueue != null) {
                SongOptionsActionRow(
                    icon = Icons.Rounded.Delete,
                    label = " Remove from queue",
                    onClick = onRemoveFromQueue,
                    tint = colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SongOptionsActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    showTrailingArrow: Boolean = false,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val colorScheme = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    val finalTint = tint.copy(alpha = alpha)
    val finalTextColor = colorScheme.onSurface.copy(alpha = alpha)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = finalTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = finalTextColor
            )
        }

        if (showTrailingArrow) {
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
    }
}
