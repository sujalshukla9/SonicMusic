package com.sonicmusic.app.features.home.data

import android.os.SystemClock
import android.util.Log
import com.sonicmusic.app.data.remote.source.YouTubePlaybackSession
import com.sonicmusic.app.data.remote.source.YouTubePlaybackSessionStore
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.data.repository.RegionRepository
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.HomeContinuation
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.CachedHomeFeed
import com.sonicmusic.app.domain.model.CachedHomePage
import com.sonicmusic.app.domain.repository.HomeFeedScope
import com.sonicmusic.app.domain.repository.HomeRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class InnertubeHomeRepository @Inject constructor(
    private val youtubeMusic: YouTubeiService,
    private val regions: RegionRepository,
    private val sessions: YouTubePlaybackSessionStore,
    private val cache: HomeCacheDataSource
) : HomeRepository {
    private val requests = Mutex()

    private data class Response(
        val scope: HomeFeedScope,
        val token: String?,
        val content: HomeContent,
        val completedAt: Long
    )

    private var lastResponse: Response? = null

    override val scopes = combine(regions.countryCode, sessions.revisions) { country, _ ->
        scopeFor(country, sessions.snapshot())
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun cachedHome(scope: HomeFeedScope): CachedHomeFeed? = cache.read(scope)?.feed

    override suspend fun refreshHome(scope: HomeFeedScope, force: Boolean, usePublicFeed: Boolean): HomeContent {
        val requestedAt = SystemClock.elapsedRealtime()
        return requests.withLock {
            val session = checkScope(scope).takeUnless { usePublicFeed }
            val feedScope = if (usePublicFeed) scope.publicFeed() else scope
            val cached = cache.read(feedScope)
            if (!force && cached != null && cached.isFresh) {
                return@withLock cached.feed.firstPage
            }
            recentResponse(feedScope, null, requestedAt)?.let { return@withLock it }

            val content = request(scope, session)
            if (content.shelves.isEmpty() && content.continuation == null) {
                throw IOException("YouTube Music returned no usable Home sections")
            }
            checkScope(scope)
            val feed = cached?.feed?.takeIf {
                !force && it.schemaVersion == CachedHomeFeed.CURRENT_VERSION &&
                    it.loadedPages.isNotEmpty() && it.firstPage.shelves == content.shelves
            } ?: CachedHomeFeed(content, schemaVersion = CachedHomeFeed.CURRENT_VERSION)
            cache.save(feedScope, feed)
            Log.d(TAG, "Home browse loaded ${content.shelves.size} sections (account=${session != null})")
            lastResponse = Response(feedScope, null, feed.firstPage, SystemClock.elapsedRealtime())
            feed.firstPage
        }
    }

    override suspend fun loadMoreHome(
        scope: HomeFeedScope,
        continuation: HomeContinuation,
        usePublicFeed: Boolean
    ): HomeContent {
        val requestedAt = SystemClock.elapsedRealtime()
        return requests.withLock {
            val session = checkScope(scope).takeUnless { usePublicFeed }
            val feedScope = if (usePublicFeed) scope.publicFeed() else scope
            recentResponse(feedScope, continuation.token, requestedAt)?.let { return@withLock it }
            val content = request(scope, session, continuation)
            checkScope(scope)
            val cached = cache.read(feedScope)
            if (cached != null) {
                val feed = cached.feed
                val tail = feed.loadedPages.lastOrNull()?.content ?: feed.firstPage
                if (tail.continuation?.token == continuation.token &&
                    feed.loadedPages.size < MAX_CACHED_CONTINUATIONS &&
                    feed.loadedPages.none { it.requestToken == continuation.token }
                ) {
                    val page = CachedHomePage(continuation.token, content)
                    cache.save(feedScope, feed.copy(loadedPages = feed.loadedPages + page), cached.cachedAt)
                }
            }
            Log.d(TAG, "Home continuation loaded ${content.shelves.size} sections")
            lastResponse = Response(feedScope, continuation.token, content, SystemClock.elapsedRealtime())
            content
        }
    }

    private fun recentResponse(scope: HomeFeedScope, token: String?, requestedAt: Long): HomeContent? =
        lastResponse?.takeIf {
            it.scope == scope && it.token == token && it.completedAt >= requestedAt
        }?.content

    private suspend fun request(
        scope: HomeFeedScope,
        session: YouTubePlaybackSession?,
        continuation: HomeContinuation? = null
    ): HomeContent {
        return withTimeoutOrNull(12_000L) {
            youtubeMusic.browseHomeFeed(scope.region, scope.language, session, continuation)
        } ?: throw IOException("YouTube Music Home took too long to respond")
    }

    override suspend fun quickPicksFromPlaylist(
        scope: HomeFeedScope,
        playlistId: String,
        usePublicFeed: Boolean
    ): List<Song> {
        val session = checkScope(scope).takeUnless { usePublicFeed }
        val tracks = withTimeoutOrNull(5_000L) {
            youtubeMusic.browseHomePlaylistTracks(playlistId, scope.region, scope.language, session)
        }.orEmpty()
        checkScope(scope)
        return tracks
    }

    private suspend fun checkScope(scope: HomeFeedScope): YouTubePlaybackSession? {
        val country = regions.countryCode.first()
        val snapshot = sessions.snapshot()
        if (scopeFor(country, snapshot) != scope) throw CancellationException("Home scope changed")
        return snapshot.session
    }

    private fun scopeFor(country: String?, snapshot: YouTubePlaybackSessionStore.Snapshot): HomeFeedScope {
        val region = youtubeMusic.homeRegion(country)
        return HomeFeedScope(snapshot.session?.homeCacheKey ?: HomeFeedScope.PUBLIC_ACCOUNT, snapshot.revision, region, youtubeMusic.homeLanguage(region))
    }

    private companion object {
        const val TAG = "HomeFeed"
        const val MAX_CACHED_CONTINUATIONS = 5
    }
}
