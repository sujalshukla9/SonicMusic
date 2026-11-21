package com.sonicmusic.app.features.home.domain

import com.sonicmusic.app.domain.model.CachedHomeFeed
import com.sonicmusic.app.domain.model.HomeContinuation
import com.sonicmusic.app.domain.model.toSong
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.HomeFeedScope
import com.sonicmusic.app.domain.repository.HomeRepository
import com.sonicmusic.app.domain.repository.PlayerRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class GetHomeContentUseCase @Inject constructor(
    private val homeRepository: HomeRepository,
    historyRepository: HistoryRepository,
    playerRepository: PlayerRepository
) {
    val scopes = homeRepository.scopes
    val recentlyPlayed = historyRepository.getRecentlyPlayedSongs(20)
        .map { history -> history.distinctBy { it.songId }.map { it.toSong() } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
    val playbackCompleted = playerRepository.songCompletedEvents

    suspend fun cached(scope: HomeFeedScope): CachedHomeFeed? = homeRepository.cachedHome(scope)
    suspend fun refresh(scope: HomeFeedScope, force: Boolean, usePublicFeed: Boolean = false) =
        homeRepository.refreshHome(scope, force, usePublicFeed)
    suspend fun loadMore(scope: HomeFeedScope, continuation: HomeContinuation, usePublicFeed: Boolean) =
        homeRepository.loadMoreHome(scope, continuation, usePublicFeed)

    suspend fun quickPicksFromPlaylist(scope: HomeFeedScope, playlistId: String, usePublicFeed: Boolean) =
        homeRepository.quickPicksFromPlaylist(scope, playlistId, usePublicFeed)
}
