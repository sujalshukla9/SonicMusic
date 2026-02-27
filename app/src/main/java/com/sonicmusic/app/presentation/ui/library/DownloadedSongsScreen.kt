package com.sonicmusic.app.presentation.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.sonicmusic.app.presentation.ui.components.SongListSkeleton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicmusic.app.data.downloadmanager.DownloadProgress
import com.sonicmusic.app.data.downloadmanager.DownloadStatus
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.viewmodel.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedSongsScreen(
    onNavigateBack: () -> Unit,
    onShowFullPlayer: () -> Unit = {},
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val downloadedSongs by viewModel.downloadedSongs.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()

    // Confirmation dialog state
    var songToDelete by remember { mutableStateOf<Song?>(null) }

    // Delete confirmation dialog
    songToDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { songToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DownloadDone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("Remove download?") },
            text = {
                Text("\"${song.title}\" will be removed from your downloads. You can download it again anytime.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeDownloadedSong(song.id)
                    songToDelete = null
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { songToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            SongListSkeleton(
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = bottomPadding + 16.dp
                )
            )
        } else if (downloadedSongs.isEmpty() && activeDownloads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No downloads yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Use Download from full player menu",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
                contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
            ) {
                // Show active downloads at the top
                if (activeDownloads.isNotEmpty()) {
                    item {
                        Text(
                            text = "Downloading",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(activeDownloads.entries.toList(), key = { "downloading_${it.key}" }) { (_, progress) ->
                        ActiveDownloadItem(progress = progress)
                    }
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                if (downloadedSongs.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.playAllDownloadedSongs(shuffle = false)
                                    onShowFullPlayer()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Play All")
                            }

                            FilledTonalButton(
                                onClick = {
                                    viewModel.playAllDownloadedSongs(shuffle = true)
                                    onShowFullPlayer()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Shuffle")
                            }
                        }
                    }

                    item {
                        Text(
                            text = "${downloadedSongs.size} songs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(downloadedSongs, key = { it.id }) { song ->
                        DownloadedSongItem(
                            song = song,
                            onClick = {
                                viewModel.onDownloadedSongClick(song)
                                onShowFullPlayer()
                            },
                            onRemoveClick = { songToDelete = song }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadItem(progress: DownloadProgress) {
    val colorScheme = MaterialTheme.colorScheme
    val isFailed = progress.status == DownloadStatus.FAILED
    val isCompleted = progress.status == DownloadStatus.COMPLETED

    val statusText = when (progress.status) {
        DownloadStatus.PENDING -> "Preparing…"
        DownloadStatus.DOWNLOADING -> "Downloading… ${progress.progress}%"
        DownloadStatus.COMPLETED -> "Download complete ✓"
        DownloadStatus.FAILED -> "Download failed"
        DownloadStatus.PAUSED -> "Paused"
        DownloadStatus.UNKNOWN -> "Waiting…"
    }

    val accentColor = when {
        isFailed -> colorScheme.error
        isCompleted -> colorScheme.primary
        else -> colorScheme.primary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isCompleted) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(40.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = progress.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = accentColor
            )
            if (!isFailed) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { if (isCompleted) 1f else progress.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = accentColor,
                    trackColor = colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun DownloadedSongItem(
    song: Song,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongThumbnail(
            artworkUrl = song.thumbnailUrl,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentDescription = null
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onRemoveClick) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = "Remove download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
