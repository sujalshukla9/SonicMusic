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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.ui.components.SongThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerMoreSheet(
    isVisible: Boolean,
    song: Song?,
    isLiked: Boolean,
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
    modifier: Modifier = Modifier
) {
    if (!isVisible || song == null) return

    val colorScheme = MaterialTheme.colorScheme

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
                                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Like",
                                tint = if (isLiked) colorScheme.error else colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onShare) {
                            Icon(
                                imageVector = Icons.Default.Share,
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
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = "Start radio",
                onClick = onStartRadio
            )
            FullPlayerMoreActionRow(
                icon = Icons.Default.LibraryMusic,
                label = "Add to playlist",
                onClick = onAddToPlaylist,
                showTrailingArrow = true
            )
            FullPlayerMoreActionRow(
                icon = Icons.Default.GraphicEq,
                label = "Equalizer",
                onClick = onEqualizer
            )
            FullPlayerMoreActionRow(
                icon = Icons.Default.Speed,
                label = "Speed & pitch",
                onClick = onSpeedAndPitch
            )
            FullPlayerMoreActionRow(
                icon = Icons.Outlined.Bedtime,
                label = "Sleep timer",
                onClick = onSleepTimer
            )
            FullPlayerMoreActionRow(
                icon = Icons.Outlined.Album,
                label = "Go to album",
                onClick = onGoToAlbum
            )
            FullPlayerMoreActionRow(
                icon = Icons.Default.Person,
                label = "More from ${song.artist}",
                onClick = onMoreFromArtist
            )
            FullPlayerMoreActionRow(
                icon = Icons.Default.PlayArrow,
                label = "Watch on YouTube",
                onClick = onWatchOnYouTube
            )
            FullPlayerMoreActionRow(
                icon = Icons.Outlined.MusicNote,
                label = "Open in YouTube Music",
                onClick = onOpenInYouTubeMusic
            )
            FullPlayerMoreActionRow(
                icon = Icons.Default.Download,
                label = "Download offline",
                onClick = onDownloadOffline
            )
        }
    }
}

@Composable
private fun FullPlayerMoreActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    showTrailingArrow: Boolean = false
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
                tint = colorScheme.onSurfaceVariant,
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
