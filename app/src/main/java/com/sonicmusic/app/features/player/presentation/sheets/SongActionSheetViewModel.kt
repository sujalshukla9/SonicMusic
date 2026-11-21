package com.sonicmusic.app.features.player.presentation.sheets

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.Playlist
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.PlaylistRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.features.downloads.data.DownloadProgress
import com.sonicmusic.app.features.downloads.data.SongDownloadManager
import com.sonicmusic.app.features.player.audio.AudioCapabilityDetector
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import com.sonicmusic.app.features.recommendations.engine.RecommendationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SongActionSheetViewModel @Inject constructor(
    private val playerServiceConnection: PlayerServiceConnection,
    private val songRepository: SongRepository,
    private val playlistRepository: PlaylistRepository,
    private val songDownloadManager: SongDownloadManager,
    private val recommendationEngine: RecommendationEngine,
    val audioCapabilityDetector: AudioCapabilityDetector
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = playlistRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val activeDownloads = songDownloadManager.activeDownloads

    val currentPlayingSong = playerServiceConnection.currentSong
    val currentStreamInfo: StateFlow<AudioStreamInfo?> = playerServiceConnection.currentStreamInfo
    val audioQualityUiState: StateFlow<com.sonicmusic.app.features.player.audio.AudioQualityUiState?> = playerServiceConnection.audioQualityUiState

    fun getStreamInfoForSong(songId: String): Flow<AudioStreamInfo?> = flow {
        if (currentPlayingSong.value?.id == songId) {
            emit(currentStreamInfo.value)
        }
        val result = songRepository.getStreamWithInfo(songId, StreamQuality.BEST)
        emit(result.getOrNull()?.second)
    }.flowOn(Dispatchers.IO)

    fun getDownloadedFileSize(songId: String): Flow<Long?> = observeDownloadedFileSize(songId)

    /**
     * Play selected song immediately.
     * If source is QUEUE and a valid index is given, skip to that position.
     */
    fun play(song: Song, source: SongActionSource, queueIndex: Int?) {
        if (source == SongActionSource.QUEUE && queueIndex != null) {
            playerServiceConnection.skipToQueueItem(queueIndex)
        } else {
            playerServiceConnection.playSongWithRadio(song)
        }
    }

    /**
     * Play this song next in the queue.
     */
    fun playNext(song: Song, context: Context) {
        playerServiceConnection.insertPlayNext(song)
        Toast.makeText(context, "Playing next: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    /**
     * Add this song to the end of the queue.
     */
    fun addToQueue(song: Song, context: Context) {
        playerServiceConnection.addSongToQueue(song)
        Toast.makeText(context, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    /**
     * Remove song from current queue at the specified index.
     */
    fun removeFromQueue(queueIndex: Int, context: Context) {
        playerServiceConnection.removeFromQueue(queueIndex)
        Toast.makeText(context, "Removed from queue", Toast.LENGTH_SHORT).show()
    }

    /**
     * Toggle like state for any song with immediate recommendation signal and repository sync.
     */
    fun toggleLike(song: Song, currentIsLiked: Boolean) {
        viewModelScope.launch {
            if (currentIsLiked) {
                songRepository.unlikeSong(song.id)
                recommendationEngine.onDislike(song)
            } else {
                songRepository.likeSong(song)
                recommendationEngine.onLike(song)
            }
        }
    }

    fun observeIsLiked(songId: String) = songRepository.observeIsLiked(songId)

    fun observeDownloadedFileSize(songId: String) = songDownloadManager.observeDownloadedFileSize(songId)

    fun download(song: Song, context: Context) {
        songDownloadManager.downloadSong(song, StreamQuality.BEST)
        Toast.makeText(context, "Downloading \"${song.title}\"...", Toast.LENGTH_SHORT).show()
    }

    fun removeDownload(songId: String, context: Context) {
        viewModelScope.launch {
            songDownloadManager.deleteDownload(songId)
            Toast.makeText(context, "Removed from downloads", Toast.LENGTH_SHORT).show()
        }
    }

    fun addToPlaylist(playlistId: Long, song: Song, force: Boolean, context: Context) {
        viewModelScope.launch {
            songRepository.cacheSong(song)
            playlistRepository.addSongToPlaylist(playlistId, song.id, force)
            Toast.makeText(context, "Added to playlist", Toast.LENGTH_SHORT).show()
        }
    }

    fun createPlaylistAndAdd(name: String, song: Song, context: Context) {
        viewModelScope.launch {
            val result = playlistRepository.createPlaylist(name)
            result.onSuccess { newId ->
                songRepository.cacheSong(song)
                playlistRepository.addSongToPlaylist(newId, song.id, true)
                Toast.makeText(context, "Created \"$name\" and added song", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Failed to create playlist", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun removeFromPlaylist(playlistId: Long, songId: String, context: Context) {
        viewModelScope.launch {
            playlistRepository.removeSongFromPlaylist(playlistId, songId)
            Toast.makeText(context, "Removed from playlist", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun isSongInPlaylist(playlistId: Long, songId: String): Boolean {
        return playlistRepository.hasSongInPlaylist(playlistId, songId)
    }

    fun shareSong(context: Context, song: Song) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Listen to \"${song.title}\" by ${song.artist} on Sonic Music: https://music.youtube.com/watch?v=${song.id}"
            )
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share song"))
    }

    fun watchOnYouTube(context: Context, song: Song) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${song.id}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openInYouTubeMusic(context: Context, song: Song) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=${song.id}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
