package com.sonicmusic.app.presentation.ui.library

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.viewmodel.PlaylistDetailViewModel
import com.sonicmusic.app.presentation.ui.components.SongListSkeleton
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onNavigateBack: () -> Unit,
    onShowFullPlayer: () -> Unit = {},
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val playlist by viewModel.playlist.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCoverArtDialog by remember { mutableStateOf(false) }

    // Inline edit states
    val focusManager = LocalFocusManager.current
    var isEditingName by remember { mutableStateOf(false) }
    var isEditingDescription by remember { mutableStateOf(false) }
    val nameFocusRequester = remember { FocusRequester() }
    val descriptionFocusRequester = remember { FocusRequester() }

    var nameText by remember(playlist?.name) { mutableStateOf(playlist?.name ?: "") }
    var descriptionText by remember(playlist?.description) { mutableStateOf(playlist?.description ?: "") }

    val isLocal = playlist?.isLocal == true

    // Synchronize local songs for drag-and-drop visual updates
    var localSongs by remember(songs) { mutableStateOf(songs) }

    // Track drag start position
    var dragFromIndex by remember { mutableStateOf(-1) }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Songs start at index 1 due to header item at index 0
        val fromRelative = from.index - 1
        val toRelative = to.index - 1
        if (fromRelative !in localSongs.indices || toRelative !in localSongs.indices) {
            return@rememberReorderableLazyListState
        }
        if (dragFromIndex == -1) {
            dragFromIndex = fromRelative
        }
        localSongs = localSongs.toMutableList().apply {
            add(toRelative, removeAt(fromRelative))
        }
    }

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
                state = lazyListState,
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
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = isLocal) {
                                    showCoverArtDialog = true
                                },
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

                        if (isLocal) {
                            Text(
                                text = "Tap cover to change",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Playlist Name Inline Edit
                        if (isLocal && isEditingName) {
                            TextField(
                                value = nameText,
                                onValueChange = { nameText = it },
                                textStyle = MaterialTheme.typography.headlineSmall,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (nameText.isNotBlank()) {
                                        viewModel.updatePlaylistMetadata(nameText, descriptionText)
                                    } else {
                                        nameText = playlist?.name ?: ""
                                    }
                                    isEditingName = false
                                    focusManager.clearFocus()
                                }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(nameFocusRequester)
                                    .onFocusChanged { focusState ->
                                        if (!focusState.isFocused && isEditingName) {
                                            if (nameText.isNotBlank()) {
                                                viewModel.updatePlaylistMetadata(nameText, descriptionText)
                                            } else {
                                                nameText = playlist?.name ?: ""
                                            }
                                            isEditingName = false
                                        }
                                    },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                    unfocusedIndicatorColor = Color.Transparent
                                )
                            )
                            LaunchedEffect(Unit) {
                                nameFocusRequester.requestFocus()
                            }
                        } else {
                            Text(
                                text = playlist!!.name,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.clickable(enabled = isLocal) {
                                    isEditingName = true
                                }
                            )
                        }

                        // Playlist Description Inline Edit
                        Spacer(modifier = Modifier.height(4.dp))
                        if (isLocal && isEditingDescription) {
                            TextField(
                                value = descriptionText,
                                onValueChange = { descriptionText = it },
                                textStyle = MaterialTheme.typography.bodyMedium,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    viewModel.updatePlaylistMetadata(nameText, descriptionText.takeIf { it.isNotBlank() })
                                    isEditingDescription = false
                                    focusManager.clearFocus()
                                }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(descriptionFocusRequester)
                                    .onFocusChanged { focusState ->
                                        if (!focusState.isFocused && isEditingDescription) {
                                            viewModel.updatePlaylistMetadata(nameText, descriptionText.takeIf { it.isNotBlank() })
                                            isEditingDescription = false
                                        }
                                    },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                    unfocusedIndicatorColor = Color.Transparent
                                )
                            )
                            LaunchedEffect(Unit) {
                                descriptionFocusRequester.requestFocus()
                            }
                        } else {
                            val descText = playlist!!.description ?: if (isLocal) "Add description" else ""
                            if (descText.isNotEmpty()) {
                                Text(
                                    text = descText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable(enabled = isLocal) {
                                        isEditingDescription = true
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
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
                itemsIndexed(localSongs, key = { index, song -> "${song.id}_$index" }) { index, song ->
                    if (isLocal) {
                        ReorderableItem(reorderableState, key = "${song.id}_$index") { isDragging ->
                            val elevation by animateDpAsState(
                                targetValue = if (isDragging) 8.dp else 0.dp,
                                label = "DragElevation"
                            )
                            val scale by animateFloatAsState(
                                targetValue = if (isDragging) 1.02f else 1f,
                                label = "DragScale"
                            )

                            Surface(
                                modifier = Modifier
                                    .shadow(elevation, shape = RoundedCornerShape(12.dp))
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = androidx.compose.animation.core.spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                        )
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
                            ) {
                                PlaylistSongItem(
                                    song = song,
                                    onClick = { 
                                        viewModel.playSong(song)
                                        onShowFullPlayer()
                                    },
                                    onRemoveClick = { 
                                        viewModel.removeSongFromPlaylist(song.id)
                                    },
                                    showRemove = true,
                                    dragModifier = Modifier.draggableHandle(
                                        onDragStopped = {
                                            if (dragFromIndex != -1) {
                                                val finalIndex = localSongs.indexOfFirst { it.id == song.id }
                                                if (finalIndex != -1 && finalIndex != dragFromIndex) {
                                                    viewModel.updatePlaylistOrder(localSongs.map { it.id })
                                                }
                                                dragFromIndex = -1
                                            }
                                        }
                                    )
                                )
                            }
                        }
                    } else {
                        // Cloud remote playlist is read-only
                        PlaylistSongItem(
                            song = song,
                            onClick = { 
                                viewModel.playSong(song)
                                onShowFullPlayer()
                            },
                            onRemoveClick = {},
                            showRemove = false,
                            dragModifier = null
                        )
                    }
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

    // Cover Art Picker Dialog
    if (showCoverArtDialog && isLocal) {
        val downloadedSongsList by viewModel.downloadedSongs.collectAsState()
        AlertDialog(
            onDismissRequest = { showCoverArtDialog = false },
            title = { Text("Select Cover Art") },
            text = {
                if (downloadedSongsList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No downloaded songs available")
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(downloadedSongsList) { downloadedSong ->
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.updatePlaylistCover(downloadedSong.localThumbnailPath)
                                        showCoverArtDialog = false
                                        Toast.makeText(context, "Cover art updated", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                SongThumbnail(
                                    artworkUrl = downloadedSong.localThumbnailPath,
                                    modifier = Modifier.fillMaxSize(),
                                    contentDescription = downloadedSong.title
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCoverArtDialog = false }) {
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
    onRemoveClick: () -> Unit,
    showRemove: Boolean,
    dragModifier: Modifier?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dragModifier != null) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = dragModifier
                    .size(24.dp)
                    .padding(end = 4.dp)
            )
        }

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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        if (showRemove) {
            IconButton(onClick = onRemoveClick) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Remove from playlist"
                )
            }
        }
    }
}
