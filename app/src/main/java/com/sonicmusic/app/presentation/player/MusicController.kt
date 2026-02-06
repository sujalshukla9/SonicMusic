package com.sonicmusic.app.presentation.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sonicmusic.app.service.MediaPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
        mediaControllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        mediaControllerFuture?.addListener({
            mediaController = mediaControllerFuture?.get()
            setupListener()
        }, MoreExecutors.directExecutor())
    }

    private fun setupListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState()
            }
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateState()
            }
        })
        updateState()
    }

    private fun updateState() {
        mediaController?.let { player ->
            _playbackState.value = PlaybackState.State(
                isPlaying = player.isPlaying,
                currentMediaId = player.currentMediaItem?.mediaId
            )
        }
    }

    fun play(mediaId: String, url: String) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(url)
            .build()
        
        mediaController?.setMediaItem(mediaItem)
        mediaController?.prepare()
        mediaController?.play()
    }

    fun pause() {
        mediaController?.pause()
    }

    fun resume() {
        mediaController?.play()
    }
}

sealed class PlaybackState {
    data object Idle : PlaybackState()
    data class State(
        val isPlaying: Boolean,
        val currentMediaId: String?
    ) : PlaybackState()
}
