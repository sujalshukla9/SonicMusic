package com.sonicmusic.app.presentation.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.domain.repository.SongRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val musicController: MusicController,
    private val songRepository: SongRepository
) : ViewModel() {

    val playerState: StateFlow<PlaybackState> = musicController.playbackState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            PlaybackState.Idle
        )

    fun playSong(id: String) {
        viewModelScope.launch {
            // First fetch detail/url
            songRepository.getSong(id)
                .onSuccess { song ->
                    song.streamUrl?.let { url ->
                         musicController.play(id, url)
                    } ?: run {
                        // TODO: Emit error event to UI (e.g. snackbar)
                        android.util.Log.e("PlayerViewModel", "Stream URL missing")
                    }
                }
                .onFailure {
                    // TODO: Emit error event to UI
                    android.util.Log.e("PlayerViewModel", "Failed to fetch song", it)
                }
        }
    }

    fun togglePlayPause() {
        val state = playerState.value
        if (state is PlaybackState.State) {
            if (state.isPlaying) {
                musicController.pause()
            } else {
                musicController.resume()
            }
        }
    }
}
