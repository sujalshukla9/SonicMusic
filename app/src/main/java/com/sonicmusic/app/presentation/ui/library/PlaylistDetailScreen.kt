package com.sonicmusic.app.presentation.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.viewmodel.PlaylistDetailViewModel
import com.sonicmusic.app.presentation.ui.components.SongListSkeleton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onNavigateBack: () -> Unit,
    onShowFullPlayer: () -> Unit = {},
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val playlist by viewModel.playlist.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Load playlist data
    LaunchedEffect(playlistId) {
        viewModel.loadPlaylist(playlistId)
    }

    // Show errors as snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "Playlist") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Delete playlist"
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
        } else if (playlist == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("Playlist not found")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
                contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
            ) {
                // Playlist header
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(16.dp)),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            tonalElevation = 4.dp
                        ) {
                            if (!playlist!!.coverArtUrl.isNullOrBlank()) {
                                SongThumbnail(
                                    artworkUrl = playlist!!.coverArtUrl,
                                    modifier = Modifier.fillMaxSize(),
                                    contentDescription = null,
                                    highQuality = true,
                                    targetSizePx = 480
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = playlist!!.name.take(2).uppercase(),
                                        style = MaterialTheme.typography.displayLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = playlist!!.name,
                            style = MaterialTheme.typography.headlineSmall
                        )

                        if (playlist!!.description != null) {
                            Text(
                                text = playlist!!.description!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = "${songs.size} songs",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { 
                                    if (songs.isNotEmpty()) {
                                        viewModel.playAll(songs)
                                        onShowFullPlayer()
                                    }
                                },
                                enabled = songs.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Play All")
                            }

                            OutlinedButton(
                                onClick = { 
                                    if (songs.isNotEmpty()) {
                                        viewModel.shufflePlay(songs)
                                        onShowFullPlayer()
                                    }
                                },
                                enabled = songs.isNotEmpty()
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
                }

                // Songs list
                items(songs, key = { it.id }) { song ->
                    PlaylistSongItem(
                        song = song,
                        onClick = { 
                            viewModel.playSong(song)
                            onShowFullPlayer()
                        },
                        onRemoveClick = { 
                            viewModel.removeSongFromPlaylist(song.id)
                        }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Playlist") },
            text = { Text("Are you sure you want to delete \"${playlist?.name}\"? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist()
                        showDeleteDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PlaylistSongItem(
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

        // Only show duration if it's not zero (songs cached without duration)
        val duration = song.formattedDuration()
        if (song.duration > 0) {
            Text(
                text = duration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onRemoveClick) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "Remove from playlist"
            )
        }
    }
}
