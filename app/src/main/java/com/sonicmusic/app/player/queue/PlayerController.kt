package com.sonicmusic.app.player.queue

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.service.PlayerServiceConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerController @Inject constructor(
    val queueManager: QueueManager,
    val streamUrlCache: StreamUrlCache,
    val playerServiceConnection: PlayerServiceConnection
) {
    companion object {
        private const val TAG = "SonicPlayerController"
        private const val SKIP_DEBOUNCE_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastSkipTime = 0L
    private var isPlayingListenerAdded = false

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val playingId = mediaItem?.mediaId ?: return
            Log.d(TAG, "onMediaItemTransition: $playingId, reason: $reason")
            
            // Sync with QueueManager index
            val queue = queueManager.currentQueue.value
            val curIdx = queueManager.currentIndex.value
            
            if (curIdx + 1 in queue.indices && queue[curIdx + 1].videoId == playingId) {
                queueManager.updateIndex(curIdx + 1)
            } else {
                val foundIdx = queue.indexOfFirst { it.videoId == playingId }
                if (foundIdx != -1 && foundIdx != curIdx) {
                    queueManager.updateIndex(foundIdx)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error occurred: ${error.message}", error)
            // Skip to next automatically if stream resolution or playing failed
            scope.launch {
                val nextIdx = queueManager.currentIndex.value + 1
                if (nextIdx < queueManager.currentQueue.value.size) {
                    Log.w(TAG, "Skipping failed song to next index: $nextIdx")
                    playQueueItem(nextIdx)
                } else {
                    Log.e(TAG, "No more songs to skip to.")
                }
            }
        }
    }

    init {
        // Observe connection state and register player listener
        scope.launch {
            while (true) {
                val controller = playerServiceConnection.getMediaController()
                if (controller != null && !isPlayingListenerAdded) {
                    controller.addListener(playerListener)
                    isPlayingListenerAdded = true
                    Log.d(TAG, "Registered Player.Listener on MediaController")
                    break
                }
                delay(500)
            }
        }

        // Observe queue changes to pre-resolve next URLs and sync playlist
        scope.launch {
            queueManager.currentQueue.collect { queue ->
                val index = queueManager.currentIndex.value
                if (queue.isNotEmpty() && index >= 0) {
                    streamUrlCache.preResolveAhead(queue, index, scope) { videoId ->
                        queueManager.setStreamUrlResolved(videoId, true)
                    }
                    syncPlaylist()
                }
            }
        }

        // Observe index changes to trigger prefetch and sync playlist
        scope.launch {
            queueManager.currentIndex.collect { index ->
                if (index >= 0) {
                    queueManager.loadMoreIfNeeded(index)
                    syncPlaylist()
                }
            }
        }
    }

    /**
     * Start playing a seed song, loading its radio/continuation queue.
     */
    fun playSong(videoId: String, playlistId: String? = null, seedSong: Song? = null) {
        scope.launch {
            streamUrlCache.clear()
            queueManager.clear()
            queueManager.loadInitialQueue(videoId, playlistId, seedSong)
        }
    }

    /**
     * Skips to the next song in the queue with a debounce safety.
     */
    fun skipToNext() {
        val now = System.currentTimeMillis()
        if (now - lastSkipTime < SKIP_DEBOUNCE_MS) {
            Log.d(TAG, "skipToNext ignored - debounced")
            return
        }
        lastSkipTime = now

        val controller = playerServiceConnection.getMediaController()
        if (controller != null && controller.hasNextMediaItem()) {
            controller.seekToNext()
        } else {
            val nextIdx = queueManager.currentIndex.value + 1
            if (nextIdx < queueManager.currentQueue.value.size) {
                playQueueItem(nextIdx)
            }
        }
    }

    /**
     * Skips to the previous song in the queue.
     */
    fun skipToPrevious() {
        val controller = playerServiceConnection.getMediaController()
        if (controller != null && controller.currentPosition > 3000) {
            controller.seekTo(0)
        } else {
            val prevIdx = queueManager.currentIndex.value - 1
            if (prevIdx >= 0) {
                playQueueItem(prevIdx)
            }
        }
    }

    /**
     * Plays a specific queue index.
     */
    fun playQueueItem(index: Int) {
        queueManager.updateIndex(index)
        syncPlaylist()
    }

    /**
     * Remove item from queue.
     */
    fun removeSong(index: Int) {
        queueManager.removeSong(index)
        syncPlaylist()
    }

    /**
     * Drag to reorder items in the queue.
     */
    fun reorderSongs(fromIndex: Int, toIndex: Int) {
        queueManager.reorderSongs(fromIndex, toIndex)
        syncPlaylist()
    }

    /**
     * Toggle play/pause.
     */
    fun togglePlayPause() {
        playerServiceConnection.togglePlayPause()
    }

    /**
     * Synchronize QueueManager state to ExoPlayer's playlist.
     * Keeps ExoPlayer populated with current playing song + next 2 songs ahead.
     */
    private fun syncPlaylist() {
        val controller = playerServiceConnection.getMediaController() ?: return
        val queue = queueManager.currentQueue.value
        val index = queueManager.currentIndex.value
        
        if (queue.isEmpty() || index !in queue.indices) return

        scope.launch {
            val desiredSongs = mutableListOf<QueueSong>()
            desiredSongs.add(queue[index])
            
            if (index + 1 in queue.indices && queue[index + 1].isStreamUrlResolved) {
                desiredSongs.add(queue[index + 1])
            }
            if (index + 2 in queue.indices && queue[index + 2].isStreamUrlResolved) {
                desiredSongs.add(queue[index + 2])
            }

            // Resolve and construct media items on IO dispatcher
            val desiredMediaItems = withContext(Dispatchers.IO) {
                desiredSongs.mapNotNull { song ->
                    val url = streamUrlCache.getStreamUrl(song.videoId)
                    if (url != null) {
                        MediaItem.Builder()
                            .setUri(url)
                            .setMediaId(song.videoId)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(song.title)
                                    .setArtist(song.artists)
                                    .setArtworkUri(android.net.Uri.parse(song.thumbnailUrl))
                                    .build()
                            )
                            .build()
                    } else null
                }
            }

            withContext(Dispatchers.Main) {
                if (desiredMediaItems.isEmpty()) return@withContext

                val currentCount = controller.mediaItemCount
                val currentItems = (0 until currentCount).map { controller.getMediaItemAt(it) }
                val currentIds = currentItems.map { it.mediaId }
                val desiredIds = desiredMediaItems.map { it.mediaId }

                if (currentIds != desiredIds) {
                    val currentPlayingIdx = controller.currentMediaItemIndex
                    val currentPlayingId = currentItems.getOrNull(currentPlayingIdx)?.mediaId
                    val desiredCurrentId = desiredIds.firstOrNull()

                    if (currentPlayingId == desiredCurrentId && currentPlayingId != null) {
                        // Current song matches desired song, just sync the next items to avoid buffering
                        while (controller.mediaItemCount > 1) {
                            controller.removeMediaItem(1)
                        }
                        for (i in 1 until desiredMediaItems.size) {
                            controller.addMediaItem(desiredMediaItems[i])
                        }
                        Log.d(TAG, "ExoPlayer playlist synced ahead (size: ${controller.mediaItemCount})")
                    } else {
                        // Rebuild the playlist from scratch
                        controller.setMediaItems(desiredMediaItems, 0, 0)
                        controller.prepare()
                        controller.play()
                        Log.d(TAG, "ExoPlayer playlist rebuilt completely (size: ${controller.mediaItemCount})")
                    }
                }
            }
        }
    }
}
