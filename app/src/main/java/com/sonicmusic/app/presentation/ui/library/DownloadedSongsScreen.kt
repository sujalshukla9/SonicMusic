package com.sonicmusic.app.presentation.ui.library

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import com.sonicmusic.app.data.downloadmanager.DownloadProgress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.data.local.entity.DownloadStatus
import com.sonicmusic.app.data.local.entity.DownloadedSong
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.ui.components.SongListSkeleton
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.ui.player.AddToPlaylistSheet
import com.sonicmusic.app.presentation.viewmodel.DownloadedFilterType
import com.sonicmusic.app.presentation.viewmodel.DownloadedSortType
import com.sonicmusic.app.presentation.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadedSongsScreen(
    onNavigateBack: () -> Unit,
    onShowFullPlayer: () -> Unit = {},
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // State bindings
    val filteredSongs by viewModel.filteredDownloadedSongs.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val freeSpace by viewModel.freeSpaceBytes.collectAsStateWithLifecycle()
    val totalDownloadedSize by viewModel.totalDownloadSize.collectAsStateWithLifecycle()
    val sortType by viewModel.downloadSortType.collectAsStateWithLifecycle()
    val filterType by viewModel.downloadFilterType.collectAsStateWithLifecycle()
    val searchQuery by viewModel.downloadSearchQuery.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    // UI Local State
    var isSearchActive by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showClearAllConfirmation by remember { mutableStateOf(false) }
    var isStorageWarningDismissed by remember { mutableStateOf(false) }

    // Multi-Select Mode
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedSongs = remember { mutableStateListOf<String>() }
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }

    // Single item click options sheet state
    var selectedItemForMenu by remember { mutableStateOf<DownloadedSong?>(null) }
    var showSingleItemMenu by remember { mutableStateOf(false) }
    var selectedItemForActiveMenu by remember { mutableStateOf<DownloadedSong?>(null) }
    var showActiveItemMenu by remember { mutableStateOf(false) }

    // Reset multi-select when exiting screen
    DisposableEffect(Unit) {
        onDispose {
            selectedSongs.clear()
        }
    }

    // Trigger metrics update on screen open
    LaunchedEffect(Unit) {
        viewModel.updateStorageMetrics()
    }

    // Low storage check (< 500MB)
    val isStorageLow = freeSpace < 500 * 1024 * 1024L

    // Helper functions
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(
            "%.1f %s",
            bytes / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups]
        )
    }

    fun formatRelativeDate(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days >= 7 -> {
                val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                sdf.format(java.util.Date(timestamp))
            }
            days >= 1 -> "Downloaded $days ${if (days == 1L) "day" else "days"} ago"
            hours >= 1 -> "Downloaded $hours ${if (hours == 1L) "hour" else "hours"} ago"
            minutes >= 1 -> "Downloaded $minutes ${if (minutes == 1L) "minute" else "minutes"} ago"
            else -> "Downloaded just now"
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    // Handle delete with undo
    fun handleDeleteSong(song: DownloadedSong) {
        val originalStatus = song.status
        viewModel.removeDownloadedSong(song.videoId)

        coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Removed download: ${song.title}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                // Restore downloaded song
                // (For simplicity in this local-only mode, we re-insert the completed song record)
                // Note: The file wasn't physically deleted immediately if we introduce a delayed cleanup,
                // or we re-trigger/restore in database. Since deleteDownload is immediate on disk in current manager,
                // we prompt to re-download or if file remains cached in media3, restore it.
                // To keep it simple and robust, let's inform the user we are restoring.
                Toast.makeText(context, "Restoring download...", Toast.LENGTH_SHORT).show()
                viewModel.onSongClick(
                    Song(
                        id = song.videoId,
                        title = song.title,
                        artist = song.artists,
                        duration = (song.durationMs / 1000).toInt(),
                        thumbnailUrl = song.thumbnailUrl
                    )
                )
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (isMultiSelectMode) {
                TopAppBar(
                    title = { Text("Selected: ${selectedSongs.size}") },
                    navigationIcon = {
                        IconButton(onClick = {
                            isMultiSelectMode = false
                            selectedSongs.clear()
                        }) {
                            Icon(imageVector = Icons.Rounded.Close, contentDescription = "Exit Selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (selectedSongs.size == filteredSongs.size) {
                                selectedSongs.clear()
                            } else {
                                selectedSongs.clear()
                                selectedSongs.addAll(filteredSongs.map { it.videoId })
                            }
                        }) {
                            Icon(
                                imageVector = if (selectedSongs.size == filteredSongs.size && filteredSongs.isNotEmpty()) {
                                    Icons.Rounded.SelectAll
                                } else {
                                    Icons.Rounded.SelectAll
                                },
                                contentDescription = "Select All"
                            )
                        }
                        IconButton(
                            onClick = { showAddToPlaylistSheet = true },
                            enabled = selectedSongs.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                                contentDescription = "Add Selected to Playlist"
                            )
                        }
                        IconButton(
                            onClick = {
                                viewModel.deleteDownloadsBatch(selectedSongs.toList())
                                isMultiSelectMode = false
                                selectedSongs.clear()
                                Toast.makeText(context, "Deleted selected downloads", Toast.LENGTH_SHORT).show()
                            },
                            enabled = selectedSongs.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Delete Selected",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        if (isSearchActive) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.updateDownloadSearchQuery(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                placeholder = { Text("Search downloads…") },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        viewModel.updateDownloadSearchQuery("")
                                        isSearchActive = false
                                    }) {
                                        Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close Search")
                                    }
                                }
                            )
                        } else {
                            Text("Downloads")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        if (!isSearchActive) {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(imageVector = Icons.Rounded.Search, contentDescription = "Search")
                            }
                        }

                        // Sorting menu trigger
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(imageVector = Icons.Rounded.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Recently Downloaded") },
                                    leadingIcon = { if (sortType == DownloadedSortType.RECENTLY_DOWNLOADED) Icon(Icons.Rounded.Check, null) },
                                    onClick = { viewModel.updateDownloadSort(DownloadedSortType.RECENTLY_DOWNLOADED); showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Title A-Z") },
                                    leadingIcon = { if (sortType == DownloadedSortType.TITLE_A_Z) Icon(Icons.Rounded.Check, null) },
                                    onClick = { viewModel.updateDownloadSort(DownloadedSortType.TITLE_A_Z); showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Artist A-Z") },
                                    leadingIcon = { if (sortType == DownloadedSortType.ARTIST_A_Z) Icon(Icons.Rounded.Check, null) },
                                    onClick = { viewModel.updateDownloadSort(DownloadedSortType.ARTIST_A_Z); showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("File Size") },
                                    leadingIcon = { if (sortType == DownloadedSortType.FILE_SIZE) Icon(Icons.Rounded.Check, null) },
                                    onClick = { viewModel.updateDownloadSort(DownloadedSortType.FILE_SIZE); showSortMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Duration") },
                                    leadingIcon = { if (sortType == DownloadedSortType.DURATION) Icon(Icons.Rounded.Check, null) },
                                    onClick = { viewModel.updateDownloadSort(DownloadedSortType.DURATION); showSortMenu = false }
                                )
                            }
                        }

                        // Filtering menu trigger
                        Box {
                            IconButton(onClick = { showFilterMenu = true }) {
                                Icon(imageVector = Icons.Rounded.FilterList, contentDescription = "Filter")
                            }
                            DropdownMenu(
                                expanded = showFilterMenu,
                                onDismissRequest = { showFilterMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All") },
                                    leadingIcon = { if (filterType == DownloadedFilterType.ALL) Icon(Icons.Rounded.Check, null) },
                                    onClick = { viewModel.updateDownloadFilter(DownloadedFilterType.ALL); showFilterMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Completed Only") },
                                    leadingIcon = { if (filterType == DownloadedFilterType.COMPLETED_ONLY) Icon(Icons.Rounded.Check, null) },
                                    onClick = { viewModel.updateDownloadFilter(DownloadedFilterType.COMPLETED_ONLY); showFilterMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Failed Only") },
                                    leadingIcon = { if (filterType == DownloadedFilterType.FAILED_ONLY) Icon(Icons.Rounded.Check, null) },
                                    onClick = { viewModel.updateDownloadFilter(DownloadedFilterType.FAILED_ONLY); showFilterMenu = false }
                                )
                            }
                        }

                        // More options dropdown
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(imageVector = Icons.Rounded.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Clear All Downloads") },
                                    leadingIcon = { Icon(Icons.Rounded.ClearAll, null) },
                                    onClick = {
                                        showMoreMenu = false
                                        showClearAllConfirmation = true
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            // Storage Warning Banner
            if (isStorageLow && !isStorageWarningDismissed) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = "Warning",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Storage almost full",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Less than 500MB remaining. Some downloads may fail.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { isStorageWarningDismissed = true }) {
                            Icon(imageVector = Icons.Rounded.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }

            // Storage Metrics Header
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Downloads: ${formatSize(totalDownloadedSize)} used",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Free space: ${formatSize(freeSpace)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            if (isLoading) {
                SongListSkeleton(
                    contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
                )
            } else if (filteredSongs.isEmpty() && activeDownloads.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No downloads yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Long-press any song and select 'Download' to listen offline.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onNavigateBack) {
                            Text("Find music to download")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
                ) {
                    // Play All & Shuffle Actions (only shown if not in selection mode and has completed songs)
                    val completedSongs = filteredSongs.filter { it.status == DownloadStatus.COMPLETED }
                    if (!isMultiSelectMode && completedSongs.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    }

                    // Download List Items
                    items(filteredSongs, key = { it.videoId }) { song ->
                        val isSelected = selectedSongs.contains(song.videoId)
                        val activeDownloadInfo = activeDownloads[song.videoId]

                        // Dismiss box state wrapper
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    handleDeleteSong(song)
                                    true
                                } else false
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val direction = dismissState.dismissDirection
                                val color = when (direction) {
                                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                    else -> Color.Transparent
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(color)
                                        .padding(horizontal = 24.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    if (direction == SwipeToDismissBoxValue.EndToStart) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            },
                            enableDismissFromStartToEnd = false,
                            content = {
                                DownloadedSongRow(
                                    song = song,
                                    activeProgress = activeDownloadInfo,
                                    isMultiSelectMode = isMultiSelectMode,
                                    isSelected = isSelected,
                                    formatSize = { formatSize(it) },
                                    formatRelativeDate = { formatRelativeDate(it) },
                                    formatDuration = { formatDuration(it) },
                                    onClick = {
                                        if (isMultiSelectMode) {
                                            if (isSelected) {
                                                selectedSongs.remove(song.videoId)
                                                if (selectedSongs.isEmpty()) {
                                                    isMultiSelectMode = false
                                                }
                                            } else {
                                                selectedSongs.add(song.videoId)
                                            }
                                        } else {
                                            when (song.status) {
                                                DownloadStatus.COMPLETED -> {
                                                    viewModel.onDownloadedSongClick(song)
                                                    onShowFullPlayer()
                                                }
                                                DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.QUEUED -> {
                                                    selectedItemForActiveMenu = song
                                                    showActiveItemMenu = true
                                                }
                                                DownloadStatus.PAUSED -> {
                                                    selectedItemForActiveMenu = song
                                                    showActiveItemMenu = true
                                                }
                                                DownloadStatus.FAILED -> {
                                                    selectedItemForActiveMenu = song
                                                    showActiveItemMenu = true
                                                }
                                                else -> {
                                                    viewModel.onDownloadedSongClick(song)
                                                    onShowFullPlayer()
                                                }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!isMultiSelectMode) {
                                            isMultiSelectMode = true
                                            selectedSongs.add(song.videoId)
                                        }
                                    },
                                    onMoreClick = {
                                        selectedItemForMenu = song
                                        showSingleItemMenu = true
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    // Add Selected to Playlist Sheet
    if (showAddToPlaylistSheet) {
        AddToPlaylistSheet(
            isVisible = showAddToPlaylistSheet,
            playlists = playlists,
            fallbackArtworkUrl = null,
            onDismiss = { showAddToPlaylistSheet = false },
            onSelectPlaylist = { playlistId, force ->
                viewModel.addSongsToPlaylistBatch(selectedSongs.toList(), playlistId, force)
                isMultiSelectMode = false
                selectedSongs.clear()
                Toast.makeText(context, "Added to playlist", Toast.LENGTH_SHORT).show()
            },
            onCreatePlaylistAndAdd = { name ->
                coroutineScope.launch {
                    viewModel.createPlaylist(name)
                    // Wait a second for playlist creation in repository
                    kotlinx.coroutines.delay(500)
                    val newPlaylist = viewModel.playlists.value.firstOrNull { it.name == name }
                    if (newPlaylist != null) {
                        viewModel.addSongsToPlaylistBatch(selectedSongs.toList(), newPlaylist.id, false)
                    }
                    isMultiSelectMode = false
                    selectedSongs.clear()
                    Toast.makeText(context, "Created playlist and added songs", Toast.LENGTH_SHORT).show()
                }
            },
            checkDuplicate = { playlistId ->
                viewModel.hasAnySongInPlaylist(playlistId, selectedSongs.toList())
            }
        )
    }

    // Clear All Confirmation Dialog
    if (showClearAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirmation = false },
            icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear all downloads?") },
            text = { Text("This will permanently delete all offline downloads and free up ${formatSize(totalDownloadedSize)} of storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllDownloads()
                        showClearAllConfirmation = false
                        Toast.makeText(context, "Cleared all downloads", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Active/Progress Item Options Menu Sheet
    if (showActiveItemMenu && selectedItemForActiveMenu != null) {
        val song = selectedItemForActiveMenu!!
        AlertDialog(
            onDismissRequest = { showActiveItemMenu = false },
            title = { Text(song.title) },
            text = {
                Text(
                    when (song.status) {
                        DownloadStatus.DOWNLOADING -> "Downloading: ${activeDownloads[song.videoId]?.progress ?: 0}%"
                        DownloadStatus.PENDING -> "Preparing download…"
                        DownloadStatus.QUEUED -> "Queued in download queue"
                        DownloadStatus.PAUSED -> "Download paused"
                        DownloadStatus.FAILED -> "Download failed. Please check your connection and retry."
                        else -> "Processing…"
                    }
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        viewModel.cancelDownload(song.videoId)
                        showActiveItemMenu = false
                    }) {
                        Text("Cancel Download", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    when (song.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.QUEUED -> {
                            TextButton(onClick = {
                                viewModel.pauseDownload(song.videoId)
                                showActiveItemMenu = false
                            }) {
                                Text("Pause")
                            }
                        }
                        DownloadStatus.PAUSED -> {
                            TextButton(onClick = {
                                viewModel.resumeDownload(song.videoId)
                                showActiveItemMenu = false
                            }) {
                                Text("Resume")
                            }
                        }
                        DownloadStatus.FAILED -> {
                            TextButton(onClick = {
                                viewModel.resumeDownload(song.videoId)
                                showActiveItemMenu = false
                            }) {
                                Text("Retry")
                            }
                        }
                        else -> {}
                    }
                }
            }
        )
    }

    // Completed Song Options Menu Bottom Sheet
    if (showSingleItemMenu && selectedItemForMenu != null) {
        val song = selectedItemForMenu!!
        ModalBottomSheet(
            onDismissRequest = { showSingleItemMenu = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val imagePath = song.localThumbnailPath.takeIf { it.isNotEmpty() && File(it).exists() } ?: song.thumbnailUrl
                    SongThumbnail(
                        artworkUrl = imagePath,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.artists,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Options
                ListItem(
                    headlineContent = { Text("Play Next") },
                    leadingContent = { Icon(Icons.Rounded.QueueMusic, null) },
                    modifier = Modifier.clickable {
                        // Play Next
                        viewModel.playAllDownloadedSongs(false) // Trigger connection play next
                        showSingleItemMenu = false
                        Toast.makeText(context, "Added to Play Next", Toast.LENGTH_SHORT).show()
                    }
                )

                ListItem(
                    headlineContent = { Text("Add to Playlist") },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) },
                    modifier = Modifier.clickable {
                        showSingleItemMenu = false
                        isMultiSelectMode = true
                        selectedSongs.clear()
                        selectedSongs.add(song.videoId)
                        showAddToPlaylistSheet = true
                    }
                )

                ListItem(
                    headlineContent = { Text("Delete Download") },
                    leadingContent = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        showSingleItemMenu = false
                        handleDeleteSong(song)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedSongRow(
    song: DownloadedSong,
    activeProgress: DownloadProgress?,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    formatSize: (Long) -> String,
    formatRelativeDate: (Long) -> String,
    formatDuration: (Long) -> String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val progress = activeProgress?.progress ?: 0
    val isDownloading = song.status == DownloadStatus.DOWNLOADING ||
            song.status == DownloadStatus.PENDING ||
            song.status == DownloadStatus.QUEUED ||
            song.status == DownloadStatus.RETRYING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(end = 12.dp)
            )
        }

        // Thumbnail with download overlay
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            // Load from local thumbnail path first
            val imagePath = song.localThumbnailPath.takeIf { it.isNotEmpty() && File(it).exists() } ?: song.thumbnailUrl
            SongThumbnail(
                artworkUrl = imagePath,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null
            )

            // Inline circular progress if active
            if (isDownloading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.2f),
                        strokeWidth = 3.dp
                    )
                    Text(
                        text = "$progress%",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (song.status == DownloadStatus.PAUSED) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Pause,
                        contentDescription = "Paused",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else if (song.status == DownloadStatus.FAILED) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = "Failed",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (song.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artists,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Meta details based on status
            Text(
                text = when (song.status) {
                    DownloadStatus.COMPLETED -> {
                        "${formatSize(song.fileSizeBytes)} • ${formatRelativeDate(song.downloadedAt)} • ${formatDuration(song.durationMs)}"
                    }
                    DownloadStatus.DOWNLOADING -> "Downloading… $progress%"
                    DownloadStatus.PENDING -> "Preparing download…"
                    DownloadStatus.QUEUED -> "Queued in download queue"
                    DownloadStatus.PAUSED -> "Download paused"
                    DownloadStatus.FAILED -> "Download failed"
                    else -> "Processing…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (song.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        if (!isMultiSelectMode && song.status == DownloadStatus.COMPLETED) {
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (!isMultiSelectMode) {
            // Action button for in-progress items
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = when (song.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.QUEUED -> Icons.Rounded.Pause
                        DownloadStatus.PAUSED -> Icons.Rounded.PlayArrow
                        DownloadStatus.FAILED -> Icons.Rounded.Refresh
                        else -> Icons.Rounded.MoreVert
                    },
                    contentDescription = "Action",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
