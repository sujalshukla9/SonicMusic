package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.CachedHomeFeed
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.HomeContinuation
import com.sonicmusic.app.domain.model.Song
import java.io.IOException
import kotlinx.coroutines.flow.Flow

data class HomeFeedScope(
    val accountKey: String,
    val sessionRevision: Long,
    val region: String,
    val language: String
) {
    val isPersonalized: Boolean get() = accountKey != PUBLIC_ACCOUNT

    fun publicFeed(): HomeFeedScope = copy(accountKey = PUBLIC_ACCOUNT)

    companion object {
        const val PUBLIC_ACCOUNT = "guest"
    }
}

internal class HomeSessionRejectedException : IOException("YouTube Music did not authorize this Home session")

interface HomeRepository {
    val scopes: Flow<HomeFeedScope>
    suspend fun cachedHome(scope: HomeFeedScope): CachedHomeFeed?
    suspend fun refreshHome(scope: HomeFeedScope, force: Boolean, usePublicFeed: Boolean = false): HomeContent
    suspend fun loadMoreHome(scope: HomeFeedScope, continuation: HomeContinuation, usePublicFeed: Boolean = false): HomeContent
    suspend fun quickPicksFromPlaylist(scope: HomeFeedScope, playlistId: String, usePublicFeed: Boolean = false): List<Song>
}
