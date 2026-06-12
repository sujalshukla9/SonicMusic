package com.sonicmusic.app.player.queue

import android.util.Log
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.SongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QueueManager @Inject constructor(
    private val youTubeiService: YouTubeiService,
    private val songRepository: SongRepository
) {
    companion object {
        private const val TAG = "SonicQueueManager"
        const val BATCH_SIZE = 20
        const val PREFETCH_THRESHOLD = 5
        const val MAX_HISTORY_KEEP = 10
    }

    private val _currentQueue = MutableStateFlow<List<QueueSong>>(emptyList())
    val currentQueue: StateFlow<List<QueueSong>> = _currentQueue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isFetchingMore = MutableStateFlow(false)
    val isFetchingMore: StateFlow<Boolean> = _isFetchingMore.asStateFlow()

    // Holds continuation token for fetching subsequent batches
    private var continuationToken: String? = null

    // Track played videoIds to prevent duplicates in radio mode
    private val historySet = Collections.synchronizedSet(LinkedHashSet<String>())

    // Tracks initial request seed to fallback if continuation token fails
    private var initialVideoId: String? = null
    private var initialPlaylistId: String? = null

    /**
     * Loads the initial queue for a given videoId and playlistId.
     * Seed song is loaded instantly from Room cache to avoid network latency.
     */
    suspend fun loadInitialQueue(videoId: String, playlistId: String? = null, seedSong: Song? = null) {
        Log.d(TAG, "loadInitialQueue - videoId: $videoId, playlistId: $playlistId")
        initialVideoId = videoId
        initialPlaylistId = playlistId
        
        _isFetchingMore.value = true
        historySet.add(videoId)

        // 1. Try to get seed song details instantly from Room cache or parameter
        var initialSeed: QueueSong? = seedSong?.toQueueSong()
        if (initialSeed == null) {
            val cached = withContext(Dispatchers.IO) {
                songRepository.getSongById(videoId).getOrNull()
            }
            initialSeed = cached?.toQueueSong()
        }

        if (initialSeed != null) {
            // Seed song details are available locally. Update queue instantly to make start of play lag-free.
            _currentQueue.value = listOf(initialSeed)
            _currentIndex.value = 0
            Log.d(TAG, "Instant queue seed loading successful: ${initialSeed.title}")
        }

        try {
            val result = withContext(Dispatchers.IO) {
                if (playlistId != null && playlistId.startsWith("RD")) {
                    youTubeiService.getRadioMix(videoId)
                } else if (playlistId != null) {
                    youTubeiService.getUpNext(videoId)
                } else {
                    youTubeiService.getRadioMix(videoId)
                }
            }

            result.onSuccess { (songs, nextToken) ->
                val queueSongs = mutableListOf<QueueSong>()
                
                // If seed song was not resolved from cache, extract it from the /next list or last resort API
                if (initialSeed == null) {
                    val matchingSong = songs.firstOrNull { it.id == videoId }
                    if (matchingSong != null) {
                        initialSeed = matchingSong.toQueueSong()
                    } else {
                        val seedSongResult = withContext(Dispatchers.IO) {
                            youTubeiService.getSongDetails(videoId).getOrNull()
                        }
                        initialSeed = seedSongResult?.toQueueSong()
                    }
                }

                initialSeed?.let {
                    queueSongs.add(it)
                }

                songs.forEach { song ->
                    if (song.id != videoId) {
                        queueSongs.add(song.toQueueSong())
                    }
                }

                _currentQueue.value = queueSongs
                continuationToken = nextToken
                
                if (_currentIndex.value == -1 || _currentIndex.value == 0) {
                    _currentIndex.value = if (queueSongs.isNotEmpty()) 0 else -1
                }
                
                Log.d(TAG, "Initial queue loaded: ${queueSongs.size} tracks, continuationToken: ${nextToken != null}")
            }.onFailure { err ->
                Log.e(TAG, "Failed to load initial queue recommendations", err)
                if (_currentQueue.value.isEmpty()) {
                    _currentQueue.value = emptyList()
                    _currentIndex.value = -1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadInitialQueue", e)
        } finally {
            _isFetchingMore.value = false
        }
    }

    /**
     * Checks if queue needs prefetching and triggers background fetch on Dispatchers.IO.
     */
    suspend fun loadMoreIfNeeded(index: Int) {
        val queueSize = _currentQueue.value.size
        
        // If remaining items in queue are less than or equal to PREFETCH_THRESHOLD
        if (index >= queueSize - PREFETCH_THRESHOLD) {
            if (_isFetchingMore.value) return
            
            Log.d(TAG, "Prefetch triggered: index $index of $queueSize. Fetching next batch...")
            fetchNextBatch()
        }
    }

    /**
     * Performs background fetch for continuation or fallback.
     */
    private suspend fun fetchNextBatch() {
        if (_isFetchingMore.value) return
        _isFetchingMore.value = true

        withContext(Dispatchers.IO) {
            var success = false
            var retryCount = 0
            var delayMs = 1000L
            var resultSongs: List<Song> = emptyList()
            var nextContinuation: String? = null

            // Retry loop with exponential backoff
            while (retryCount < 3 && !success) {
                val token = continuationToken
                val fetchResult = if (token != null) {
                    youTubeiService.getQueueContinuation(token)
                } else {
                    // Fallback to fresh seed request using current playing or last song in queue
                    val currentSongId = _currentQueue.value.getOrNull(_currentIndex.value)?.videoId ?: initialVideoId
                    if (currentSongId != null) {
                        Log.d(TAG, "Continuation token missing, falling back to fresh Radio Mix for $currentSongId")
                        youTubeiService.getRadioMix(currentSongId)
                    } else {
                        Result.failure(Exception("No seed song available for fallback"))
                    }
                }

                fetchResult.onSuccess { (songs, token) ->
                    resultSongs = songs
                    nextContinuation = token
                    success = true
                }.onFailure { err ->
                    retryCount++
                    Log.w(TAG, "Fetch failed (attempt $retryCount/3): ${err.message}. Retrying in ${delayMs}ms...")
                    delay(delayMs)
                    delayMs *= 2
                }
            }

            if (success) {
                continuationToken = nextContinuation
                val filteredSongs = resultSongs.map { it.toQueueSong() }
                    .filter { newSong ->
                        !_currentQueue.value.any { it.videoId == newSong.videoId } &&
                        !historySet.contains(newSong.videoId)
                    }

                if (filteredSongs.isNotEmpty()) {
                    _currentQueue.update { current -> current + filteredSongs }
                    Log.d(TAG, "Appended ${filteredSongs.size} new tracks to queue. New size: ${_currentQueue.value.size}")
                } else {
                    Log.d(TAG, "Fetched ${resultSongs.size} tracks, but all were filtered as duplicates.")
                }
            } else {
                Log.e(TAG, "Max retries reached. Next batch load failed.")
            }
            _isFetchingMore.value = false
        }
    }

    /**
     * Updates current queue playback index, triggering history trimming.
     */
    fun updateIndex(index: Int) {
        val size = _currentQueue.value.size
        if (index in 0 until size) {
            _currentIndex.value = index
            val song = _currentQueue.value[index]
            historySet.add(song.videoId)
            trimHistory()
        }
    }

    /**
     * Trims historical played tracks to keep memory usage minimal.
     */
    private fun trimHistory() {
        val index = _currentIndex.value
        if (index > MAX_HISTORY_KEEP) {
            val dropCount = index - MAX_HISTORY_KEEP
            _currentQueue.update { queue ->
                if (queue.size > dropCount) {
                    queue.drop(dropCount)
                } else {
                    queue
                }
            }
            _currentIndex.value = MAX_HISTORY_KEEP
            Log.d(TAG, "Trimmed queue history: dropped $dropCount items, index reset to $MAX_HISTORY_KEEP")
        }
    }

    /**
     * Remove a song from the queue.
     */
    fun removeSong(index: Int) {
        _currentQueue.update { queue ->
            if (index in queue.indices) {
                queue.toMutableList().apply { removeAt(index) }
            } else {
                queue
            }
        }
        val curIndex = _currentIndex.value
        if (curIndex >= index) {
            _currentIndex.value = (curIndex - 1).coerceAtLeast(0)
        }
    }

    /**
     * Reorders a song within the queue.
     */
    fun reorderSongs(fromIndex: Int, toIndex: Int) {
        _currentQueue.update { queue ->
            if (fromIndex in queue.indices && toIndex in queue.indices) {
                queue.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
            } else {
                queue
            }
        }
        val curIndex = _currentIndex.value
        if (curIndex == fromIndex) {
            _currentIndex.value = toIndex
        } else if (curIndex in (fromIndex + 1)..toIndex) {
            _currentIndex.value = curIndex - 1
        } else if (curIndex in toIndex..<fromIndex) {
            _currentIndex.value = curIndex + 1
        }
    }

    /**
     * Updates the resolved stream URL status of a track.
     */
    fun setStreamUrlResolved(videoId: String, resolved: Boolean) {
        _currentQueue.update { list ->
            list.map { song ->
                if (song.videoId == videoId) {
                    song.copy(isStreamUrlResolved = resolved)
                } else {
                    song
                }
            }
        }
    }

    /**
     * Clears all queue states.
     */
    fun clear() {
        _currentQueue.value = emptyList()
        _currentIndex.value = -1
        continuationToken = null
        historySet.clear()
        initialVideoId = null
        initialPlaylistId = null
        _isFetchingMore.value = false
        Log.d(TAG, "QueueManager cleared")
    }
}
