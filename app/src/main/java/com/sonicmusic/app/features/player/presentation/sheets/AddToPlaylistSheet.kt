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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.shared.components.SongThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    isVisible: Boolean,
    playlists: List<Playlist>,
    fallbackArtworkUrl: String?,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long, Boolean) -> Unit,
    onCreatePlaylistAndAdd: (String) -> Unit,
    checkDuplicate: (suspend (Long) -> Boolean)? = null,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    val editablePlaylists = playlists.filterNot { it.isYouTubeAccountPlaylist }
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var selectedPlaylistForDuplicate by remember { mutableStateOf<Playlist?>(null) }
    val colorScheme = MaterialTheme.colorScheme

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Playlist") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    singleLine = true,
                    label = { Text("Playlist name") },
                    placeholder = { Text("My Playlist") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = playlistName.trim()
                        if (name.isNotEmpty()) {
                            onCreatePlaylistAndAdd(name)
                            playlistName = ""
                            showCreateDialog = false
                            onDismiss()
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (selectedPlaylistForDuplicate != null) {
        AlertDialog(
            onDismissRequest = { selectedPlaylistForDuplicate = null },
            title = { Text("Duplicate Song") },
            text = { Text("This song is already in \"${selectedPlaylistForDuplicate?.name}\". Do you want to add it anyway?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val playlistId = selectedPlaylistForDuplicate!!.id
                        onSelectPlaylist(playlistId, true)
                        selectedPlaylistForDuplicate = null
                        onDismiss()
                    }
                ) {
                    Text("Add anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPlaylistForDuplicate = null }) {
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
        contentColor = colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = colorScheme.primary
                )
                Text(
                    text = "Add to playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(10.dp))

            if (editablePlaylists.isEmpty()) {
                Text(
                    text = "No playlists yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                editablePlaylists.forEach { playlist ->
                    val artworkUrl = playlist.coverArtUrl?.takeIf { it.isNotBlank() }
                        ?: fallbackArtworkUrl?.takeIf { it.isNotBlank() }
                    val playlistInteractionSource = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .pressScale(playlistInteractionSource, targetScale = 0.98f)
                            .clickable(
                                interactionSource = playlistInteractionSource,
                                indication = ripple(),
                                onClick = {
                                    if (checkDuplicate != null) {
                                        scope.launch {
                                            val isDuplicate = checkDuplicate(playlist.id)
                                            if (isDuplicate) {
                                                selectedPlaylistForDuplicate = playlist
                                            } else {
                                                onSelectPlaylist(playlist.id, false)
                                                onDismiss()
                                            }
                                        }
                                    } else {
                                        onSelectPlaylist(playlist.id, false)
                                        onDismiss()
                                    }
                                }
                            )
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (artworkUrl != null) {
                            SongThumbnail(
                                artworkUrl = artworkUrl,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentDescription = null
                            )
                        } else {
                            Surface(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                color = colorScheme.surfaceContainerHighest
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LibraryMusic,
                                        contentDescription = null,
                                        tint = colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${playlist.songCount} songs",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text("Create playlist")
            }
        }
    }
}
