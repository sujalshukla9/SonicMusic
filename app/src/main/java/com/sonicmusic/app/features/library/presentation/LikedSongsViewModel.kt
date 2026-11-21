package com.sonicmusic.app.features.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.HomeShelfItem
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import com.sonicmusic.app.features.library.data.MusicLibraryPage
import com.sonicmusic.app.features.library.data.MusicLibrarySection
import com.sonicmusic.app.features.library.data.YouTubeMusicLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LikedSongsViewModel @Inject constructor(
    private val songRepository: SongRepository,
    private val playerServiceConnection: PlayerServiceConnection,
    private val accountLibrary: YouTubeMusicLibrary
) : ViewModel() {

    private val _likedSongs = MutableStateFlow<List<Song>>(emptyList())
    val likedSongs: StateFlow<List<Song>> = _likedSongs.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val accountLikes = accountLibrary.observe(MusicLibrarySection.Likes)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), MusicLibraryPage())

    init {
        loadLikedSongs()
    }

    private fun loadLikedSongs() {
        viewModelScope.launch {
            _isLoading.value = true
            combine(songRepository.getLikedSongs(), accountLikes) { local, remote ->
                (local + remote.items.mapNotNull(HomeShelfItem::playableSong)).distinctBy(Song::id)
            }.flowOn(Dispatchers.Default).collect { songs ->
                _likedSongs.value = songs
                _isLoading.value = false
            }
        }
    }

    fun loadMoreLikes() {
        viewModelScope.launch { accountLibrary.loadMore(MusicLibrarySection.Likes) }
    }

    fun playSong(song: Song) {
        // Play only the clicked song + fresh radio recommendations (not all liked songs)
        playerServiceConnection.playSongWithRadio(song)
    }

    fun playAll() {
        val songs = _likedSongs.value
        if (songs.isNotEmpty()) {
            playerServiceConnection.playSongsLazy(songs, 0, songRepository)
        }
    }

    fun shufflePlay() {
        val songs = _likedSongs.value
        if (songs.isNotEmpty()) {
            val shuffledSongs = songs.shuffled()
            playerServiceConnection.playSongsLazy(shuffledSongs, 0, songRepository)
        }
    }

    fun playNext(song: Song) {
        playerServiceConnection.insertPlayNextLazy(song, songRepository)
    }
}
