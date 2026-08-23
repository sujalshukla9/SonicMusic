package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.domain.model.HomeSection
import com.sonicmusic.app.domain.repository.HomeRepository
import com.sonicmusic.app.domain.repository.PlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeRefreshManager @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val homeRepository: HomeRepository
) {
    companion object {
        private const val TAG = "HomeRefreshManager"
        private const val SONG_COUNT_THRESHOLD = 1
        private const val TIME_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var songsPlayedSinceRefresh = 0
    private var lastRefreshTimestamp = System.currentTimeMillis()

    private val _refreshEvents = MutableSharedFlow<Pair<String, HomeSection>>(extraBufferCapacity = 8)
    val refreshEvents: SharedFlow<Pair<String, HomeSection>> = _refreshEvents.asSharedFlow()

    private var isRefreshing = false
    private var pendingRefresh = false
    private var refreshJob: Job? = null

    init {
        scope.launch {
            playerRepository.songCompletedEvents.collect { event ->
                Log.d(TAG, "Song completed: ${event.videoId}")
                handlePlaybackEvent()
            }
        }

        // Observe subscription count changes to trigger pending refreshes or cancel in-flight ones
        scope.launch {
            _refreshEvents.subscriptionCount.collect { count ->
                if (count > 0) {
                    if (pendingRefresh) {
                        Log.d(TAG, "Subscribers active. Executing pending refresh.")
                        pendingRefresh = false
                        if (!isRefreshing) {
                            isRefreshing = true
                            refreshJob?.cancel()
                            refreshJob = scope.launch {
                                triggerRefresh()
                            }
                        }
                    }
                } else {
                    // Cancel in-flight refresh when there are no active subscribers
                    if (isRefreshing) {
                        Log.d(TAG, "No active subscribers, cancelling in-flight refresh")
                        refreshJob?.cancel()
                        isRefreshing = false
                    }
                }
            }
        }
    }

    private suspend fun handlePlaybackEvent() = mutex.withLock {
        songsPlayedSinceRefresh++
        val now = System.currentTimeMillis()
        val timeElapsed = now - lastRefreshTimestamp

        Log.d(TAG, "Songs played since refresh: $songsPlayedSinceRefresh, Time elapsed: ${timeElapsed / 1000}s")

        if (songsPlayedSinceRefresh >= SONG_COUNT_THRESHOLD || timeElapsed >= TIME_THRESHOLD_MS) {
            if (_refreshEvents.subscriptionCount.value > 0) {
                if (!isRefreshing) {
                    isRefreshing = true
                    refreshJob?.cancel()
                    refreshJob = scope.launch {
                        triggerRefresh()
                    }
                }
            } else {
                Log.d(TAG, "App is in background/Home not active. Queueing refresh.")
                pendingRefresh = true
            }
        }
    }

    private suspend fun triggerRefresh() {
        try {
            Log.d(TAG, "Triggering background refresh for Quick Picks, Made for You, Listen Again, and Forgotten Favorites")
            coroutineScope {
                val quickPicksDeferred = async { homeRepository.refreshQuickPicks() }
                val madeForYouDeferred = async { homeRepository.refreshMadeForYou() }
                val listenAgainDeferred = async { homeRepository.refreshListenAgain() }
                val forgottenFavoritesDeferred = async { homeRepository.refreshForgottenFavorites() }

                val quickPicks = quickPicksDeferred.await()
                val madeForYou = madeForYouDeferred.await()
                val listenAgain = listenAgainDeferred.await()
                val forgottenFavorites = forgottenFavoritesDeferred.await()

                _refreshEvents.emit(Pair("Quick Picks", quickPicks))
                _refreshEvents.emit(Pair("Made for You", madeForYou))
                _refreshEvents.emit(Pair("Listen Again", listenAgain))
                _refreshEvents.emit(Pair("Forgotten Favorites", forgottenFavorites))
            }
            Log.d(TAG, "Background refresh completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error performing background home refresh", e)
        } finally {
            mutex.withLock {
                songsPlayedSinceRefresh = 0
                lastRefreshTimestamp = System.currentTimeMillis()
                isRefreshing = false
            }
        }
    }
}
