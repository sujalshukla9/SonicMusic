package com.sonicmusic.app.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.SongRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val songRepository: SongRepository
) : ViewModel() {

    private val _likedSongs = MutableStateFlow<List<Song>>(emptyList())
    val likedSongs: StateFlow<List<Song>> = _likedSongs.asStateFlow()

    init {
        songRepository.getLikedSongs()
            .onEach { _likedSongs.value = it }
            .launchIn(viewModelScope)
    }
}
