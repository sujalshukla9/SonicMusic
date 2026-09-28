package com.sonicmusic.app.features.player.presentation.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.sonicmusic.app.app.SubScreens
import com.sonicmusic.app.app.navigateToAlbum
import kotlinx.coroutines.flow.flowOf

@Composable
fun GlobalSongActionSheetHost(
    controller: SongActionSheetController = LocalSongActionSheetController.current,
    viewModel: SongActionSheetViewModel = hiltViewModel(),
    navController: NavController,
    onShowFullPlayer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val song = controller.selectedSong
    val isVisible = controller.isVisible && song != null

    val context = LocalContext.current
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }
    var showSongDetailsSheet by remember { mutableStateOf(false) }

    // Preserve active song while sub-sheets are displayed
    var activeSongForSubSheets by remember { mutableStateOf(song) }
    if (song != null) {
        activeSongForSubSheets = song
    }

    if (isVisible && song != null) {
        val isLiked by viewModel.observeIsLiked(song.id)
            .collectAsStateWithLifecycle(initialValue = song.isLiked)

        val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
        val downloadProgress = activeDownloads[song.id]
        val isDownloaded = remember(song.id, activeDownloads) {
            viewModel.isDownloaded(song.id)
        }

        GlobalSongActionSheet(
            isVisible = isVisible,
            song = song,
            source = controller.source,
            isLiked = isLiked,
            isDownloaded = isDownloaded,
            downloadProgress = downloadProgress,
            onDismiss = {
                controller.dismiss()
            },
            onPlay = {
                val currentSong = song
                val source = controller.source
                val qIndex = controller.queueIndex
                controller.dismiss()
                viewModel.play(currentSong, source, qIndex)
                onShowFullPlayer?.invoke()
            },
            onPlayNext = {
                val currentSong = song
                controller.dismiss()
                viewModel.playNext(currentSong, context)
            },
            onAddToQueue = {
                val currentSong = song
                controller.dismiss()
                viewModel.addToQueue(currentSong, context)
            },
            onAddToPlaylist = {
                showAddToPlaylistSheet = true
            },
            onToggleLike = {
                viewModel.toggleLike(song, isLiked)
            },
            onShare = {
                viewModel.shareSong(context, song)
            },
            onGoToAlbum = { albumId, title, artist, thumb ->
                controller.dismiss()
                navigateToAlbum(
                    navController = navController,
                    context = context,
                    browseId = albumId,
                    title = title,
                    artist = artist,
                    thumbnailUrl = thumb
                )
            },
            onMoreFromArtist = { artistName, artistId ->
                controller.dismiss()
                navController.navigate(SubScreens.artistDetail(artistName, artistId))
            },
            onDownloadOffline = {
                viewModel.download(song, context)
            },
            onRemoveDownload = {
                viewModel.removeDownload(song.id, context)
            },
            onSongInfo = {
                showSongDetailsSheet = true
            },
            onWatchOnYouTube = {
                viewModel.watchOnYouTube(context, song)
            },
            onOpenInYouTubeMusic = {
                viewModel.openInYouTubeMusic(context, song)
            },
            onRemoveFromQueue = controller.queueIndex?.let { idx ->
                {
                    controller.dismiss()
                    viewModel.removeFromQueue(idx, context)
                }
            },
            onRemoveFromPlaylist = controller.playlistId?.let { pId ->
                {
                    controller.dismiss()
                    viewModel.removeFromPlaylist(pId, song.id, context)
                }
            },
            modifier = modifier
        )
    }

    // ── Sub-sheet: Add to Playlist ──
    val subSong = activeSongForSubSheets
    if (showAddToPlaylistSheet && subSong != null) {
        val playlists by viewModel.playlists.collectAsStateWithLifecycle()
        AddToPlaylistSheet(
            isVisible = showAddToPlaylistSheet,
            playlists = playlists,
            fallbackArtworkUrl = subSong.thumbnailUrl,
            onDismiss = {
                showAddToPlaylistSheet = false
                controller.dismiss()
            },
            onSelectPlaylist = { playlistId, force ->
                viewModel.addToPlaylist(playlistId, subSong, force, context)
                showAddToPlaylistSheet = false
                controller.dismiss()
            },
            onCreatePlaylistAndAdd = { name ->
                viewModel.createPlaylistAndAdd(name, subSong, context)
                showAddToPlaylistSheet = false
                controller.dismiss()
            },
            checkDuplicate = { playlistId ->
                viewModel.isSongInPlaylist(playlistId, subSong.id)
            }
        )
    }

    // ── Sub-sheet: Song Info / Technical Details ──
    if (showSongDetailsSheet && subSong != null) {
        val currentPlayingSong by viewModel.currentPlayingSong.collectAsStateWithLifecycle(initialValue = null)
        val audioCap by viewModel.audioCapabilityDetector.capability.collectAsStateWithLifecycle()

        SongDetailsSheet(
            isVisible = showSongDetailsSheet,
            song = subSong,
            streamInfo = null,
            downloadedFileSizeBytes = null,
            durationMs = (subSong.duration * 1000L).coerceAtLeast(0L),
            onDismiss = {
                showSongDetailsSheet = false
                controller.dismiss()
            },
            audioCapability = audioCap,
            isPlaying = currentPlayingSong?.id == subSong.id
        )
    }
}
