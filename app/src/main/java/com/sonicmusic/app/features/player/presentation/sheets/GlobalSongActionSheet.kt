package com.sonicmusic.app.features.player.presentation.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import com.sonicmusic.app.theme.pressScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.rounded.Info
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
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.features.downloads.data.DownloadProgress
import com.sonicmusic.app.shared.components.SongThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSongActionSheet(
    isVisible: Boolean,
    song: Song?,
    source: SongActionSource,
    isLiked: Boolean,
    isDownloaded: Boolean,
    downloadProgress: DownloadProgress?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleLike: () -> Unit,
    onShare: () -> Unit,
    onGoToAlbum: (albumId: String, title: String, artist: String, thumbnailUrl: String?) -> Unit,
    onMoreFromArtist: (artistName: String, artistId: String?) -> Unit,
    onDownloadOffline: () -> Unit,
    onRemoveDownload: () -> Unit,
    onSongInfo: () -> Unit,
    onWatchOnYouTube: () -> Unit,
    onOpenInYouTubeMusic: () -> Unit,
    onRemoveFromQueue: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (!isVisible || song == null) return

    val colorScheme = MaterialTheme.colorScheme
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Download removal confirmation dialog
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
            title = { Text("Remove from Downloads?") },
            text = {
                Text("\"${song.title}\" will be removed from your offline cache.")
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
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            // ── Header: Thumbnail + Song Title + Artists + Like + Share ──
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
                            contentDescription = song.title
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

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onToggleLike) {
                            Icon(
                                imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (isLiked) "Unlike" else "Like",
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

            // ── Play & Queue Actions ─────────────────────────────────────
            GlobalActionRow(
                icon = Icons.Rounded.PlayArrow,
                label = "Play",
                onClick = onPlay
            )

            GlobalActionRow(
                icon = Icons.Rounded.PlayArrow,
                label = "Play next",
                onClick = onPlayNext
            )

            // Add to Queue (hidden when in QUEUE context)
            if (source != SongActionSource.QUEUE) {
                GlobalActionRow(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    label = "Add to queue",
                    onClick = onAddToQueue
                )
            }

            // Remove from queue (only shown when in QUEUE context)
            if (source == SongActionSource.QUEUE && onRemoveFromQueue != null) {
                GlobalActionRow(
                    icon = Icons.Rounded.Delete,
                    label = "Remove from queue",
                    onClick = onRemoveFromQueue,
                    tint = colorScheme.error
                )
            }

            // ── Playlist Actions ─────────────────────────────────────────
            GlobalActionRow(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                label = "Add to playlist",
                onClick = onAddToPlaylist,
                showTrailingArrow = true
            )

            if (onRemoveFromPlaylist != null) {
                GlobalActionRow(
                    icon = Icons.Rounded.Delete,
                    label = "Remove from playlist",
                    onClick = onRemoveFromPlaylist,
                    tint = colorScheme.error
                )
            }

            // ── Album Navigation ─────────────────────────────────────────
            val resolvedAlbumId = song.albumBrowseId?.takeIf { it.isNotBlank() }
                ?: song.albumId?.takeIf { it.isNotBlank() }

            if (source != SongActionSource.ALBUM && resolvedAlbumId != null) {
                GlobalActionRow(
                    icon = Icons.Rounded.Album,
                    label = "Go to album",
                    onClick = {
                        onGoToAlbum(
                            resolvedAlbumId,
                            song.albumName ?: song.album.orEmpty(),
                            song.artist,
                            song.thumbnailUrl
                        )
                    }
                )
            }

            // ── Artist Navigation ────────────────────────────────────────
            val artistList = song.resolvedArtists
            if (artistList.isNotEmpty()) {
                artistList.forEach { artistRef ->
                    GlobalActionRow(
                        icon = Icons.Rounded.Person,
                        label = "More from ${artistRef.name}",
                        onClick = { onMoreFromArtist(artistRef.name, artistRef.browseId) }
                    )
                }
            } else if (song.artist.isNotBlank()) {
                GlobalActionRow(
                    icon = Icons.Rounded.Person,
                    label = "More from ${song.artist}",
                    onClick = { onMoreFromArtist(song.artist, song.artistId) }
                )
            }

            // ── Download ─────────────────────────────────────
            val isDownloading = !isDownloaded && downloadProgress?.isTransferring == true

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
                GlobalActionRow(
                    icon = Icons.Rounded.DownloadDone,
                    label = "Remove download",
                    onClick = { showDeleteConfirmation = true },
                    tint = colorScheme.primary
                )
            } else {
                GlobalActionRow(
                    icon = Icons.Rounded.Download,
                    label = "Download",
                    onClick = onDownloadOffline
                )
            }

            // ── Song Information ─────────────────────────────────────────
            GlobalActionRow(
                icon = Icons.Rounded.Info,
                label = "Song information",
                onClick = onSongInfo
            )

            // ── External YouTube Links ───────────────────────────────────
            GlobalActionRow(
                icon = Icons.Rounded.PlayArrow,
                label = "Watch on YouTube",
                onClick = onWatchOnYouTube
            )
            GlobalActionRow(
                icon = Icons.Rounded.MusicNote,
                label = "Open in YouTube Music",
                onClick = onOpenInYouTubeMusic
            )
        }
    }
}

@Composable
private fun GlobalActionRow(
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
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .pressScale(interactionSource, targetScale = 0.97f, enabled = enabled)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = enabled,
                onClick = onClick
            )
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
