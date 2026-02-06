package com.sonicmusic.app.data.service

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sonicmusic.app.domain.service.PlayerController
import com.sonicmusic.app.service.MediaPlaybackService
import com.sonicmusic.app.domain.model.Song
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PlayerController {

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private val mediaController: MediaController?
        get() = if (mediaControllerFuture?.isDone == true) mediaControllerFuture?.get() else null

    init {
        val sessionToken = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
        mediaControllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        mediaControllerFuture?.addListener({
            // Controller connected
        }, MoreExecutors.directExecutor())
    }

    override suspend fun playNow(song: Song, streamUrl: String) {
        val controller = mediaControllerFuture?.await() ?: return
        
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(android.net.Uri.parse(song.thumbnailUrl))
                    .build()
            )
            .build()

        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()
    }

    override suspend fun addToQueue(song: Song, streamUrl: String) {
        val controller = mediaControllerFuture?.await() ?: return
        
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(android.net.Uri.parse(song.thumbnailUrl))
                    .build()
            )
            .build()
            
        controller.addMediaItem(mediaItem)
    }
}

private suspend fun <T> ListenableFuture<T>.await(): T {
    return suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            MoreExecutors.directExecutor()
        )
        continuation.invokeOnCancellation {
            cancel(false)
        }
    }
}
