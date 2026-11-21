package com.sonicmusic.app.features.library.presentation

import com.sonicmusic.app.theme.pressScale
import com.sonicmusic.app.theme.pressable
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.shared.components.PlaylistCoverCollage
import com.sonicmusic.app.shared.components.SongListSkeleton
import com.sonicmusic.app.shared.components.pullrefresh.M3ExpressivePullToRefresh
import com.sonicmusic.app.features.library.presentation.PlaylistsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlaylistsScreen(
    onNavigateBack: () -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onAccountPlaylistClick: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val localPlaylists by viewModel.localPlaylists.collectAsStateWithLifecycle()
    val remotePlaylists by viewModel.remotePlaylists.collectAsStateWithLifecycle()
    val accountPage by viewModel.accountPlaylists.collectAsStateWithLifecycle()
    val accountPlaylists = remember(accountPage.items) {
        accountPage.items.filterIsInstance<com.sonicmusic.app.domain.model.HomeShelfItem.Playlist>()
    }
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var newPlaylistDesc by remember { mutableStateOf("") }

    var showImportDialog by remember { mutableStateOf(false) }
    var importUrlOrId by remember { mutableStateOf("") }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Playlists") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Import playlist"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            val presses = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.padding(bottom = bottomPadding).pressScale(presses),
                interactionSource = presses
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create playlist")
            }
        }
    ) { paddingValues ->
        if (isLoading && localPlaylists.isEmpty() && remotePlaylists.isEmpty() && accountPlaylists.isEmpty()) {
            SongListSkeleton(
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = bottomPadding + 16.dp
                )
            )
        } else {
            M3ExpressivePullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = {
                    viewModel.syncAllRemotePlaylists {
                        Toast.makeText(context, "Remote playlists synced", Toast.LENGTH_SHORT).show()
                    }
                },
                indicatorPadding = WindowInsets.statusBars.asPaddingValues(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (localPlaylists.isEmpty() && remotePlaylists.isEmpty() && accountPlaylists.isEmpty() && !accountPage.hasMore) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No playlists created",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Create a playlist or import a YouTube Music playlist to get started.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 32.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = { showCreateDialog = true }) {
                                    Text("Create Playlist")
                                }
                                TextButton(onClick = { showImportDialog = true }) {
                                    Text("Import Playlist")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 16.dp + bottomPadding + 72.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // My Playlists Section
                        if (localPlaylists.isNotEmpty()) {
                            item(key = "local_playlists_header", contentType = "header") {
                                Text(
                                    text = "My Playlists",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(localPlaylists, key = { "local_${it.id}" }, contentType = { "playlist" }) { playlist ->
                                PlaylistCard(
                                    playlist = playlist,
                                    onClick = { onPlaylistClick(playlist.id) }
                                )
                            }
                        }

                        // Saved from YTM Section
                        if (remotePlaylists.isNotEmpty()) {
                            item(key = "remote_playlists_header", contentType = "header") {
                                if (localPlaylists.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                                Text(
                                    text = "Saved from YouTube Music",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(remotePlaylists, key = { "remote_${it.id}" }, contentType = { "playlist" }) { playlist ->
                                PlaylistCard(
                                    playlist = playlist,
                                    onClick = { onPlaylistClick(playlist.id) }
                                )
                            }
                        }
                        if (accountPlaylists.isNotEmpty() || accountPage.hasMore) {
                            item(key = "account_playlists_header") {
                                Text("YouTube Music", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            items(accountPlaylists, key = { "account_${it.key}" }, contentType = { "account_playlist" }) { entry ->
                                AccountMusicRow(entry) {
                                    val playlist = entry.playlist
                                    onAccountPlaylistClick(
                                        accountPage.playlistRoute(playlist.browseId), playlist.title, playlist.creator, playlist.thumbnailUrl
                                    )
                                }
                            }
                            if (accountPage.hasMore) {
                                item(key = "more_account_playlists") {
                                    TextButton(onClick = viewModel::loadMoreAccountPlaylists) { Text("Load more") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Create Local Playlist Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                newPlaylistName = ""
                newPlaylistDesc = ""
            },
            title = { Text("Create Playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Playlist name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPlaylistDesc,
                        onValueChange = { newPlaylistDesc = it },
                        label = { Text("Description (Optional)") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.createPlaylist(newPlaylistName, newPlaylistDesc.takeIf { it.isNotBlank() })
                            showCreateDialog = false
                            newPlaylistName = ""
                            newPlaylistDesc = ""
                            Toast.makeText(context, "Playlist created", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = newPlaylistName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateDialog = false
                    newPlaylistName = ""
                    newPlaylistDesc = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Import Remote Playlist Dialog
    if (showImportDialog) {
        var isImporting by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {
                if (!isImporting) {
                    showImportDialog = false
                    importUrlOrId = ""
                }
            },
            title = { Text("Import YTM Playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Paste a YouTube Music playlist ID or URL (e.g. music.youtube.com/playlist?list=...).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = importUrlOrId,
                        onValueChange = { importUrlOrId = it },
                        label = { Text("Playlist URL or ID") },
                        singleLine = true,
                        enabled = !isImporting,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (importUrlOrId.isNotBlank()) {
                            isImporting = true
                            viewModel.importRemotePlaylist(
                                playlistIdString = importUrlOrId,
                                onSuccess = {
                                    isImporting = false
                                    showImportDialog = false
                                    importUrlOrId = ""
                                    Toast.makeText(context, "Playlist imported successfully!", Toast.LENGTH_LONG).show()
                                },
                                onFailure = { errorMsg ->
                                    isImporting = false
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    enabled = importUrlOrId.isNotBlank() && !isImporting
                ) {
                    Text(if (isImporting) "Importing..." else " Import")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        importUrlOrId = ""
                    },
                    enabled = !isImporting
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlaylistCoverCollage(
                playlist = playlist,
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!playlist.isLocal) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Read-only YTM Playlist",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val subtitle = buildString {
                    append("${playlist.songCount} songs")
                    if (!playlist.description.isNullOrBlank()) {
                        append(" • ")
                        append(playlist.description)
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
