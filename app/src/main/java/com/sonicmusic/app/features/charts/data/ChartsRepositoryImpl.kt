package com.sonicmusic.app.features.charts.data

import android.util.LruCache
import com.sonicmusic.app.data.local.cache.YtmJsonCache
import com.sonicmusic.app.data.remote.source.YouTubePlaybackSession
import com.sonicmusic.app.data.remote.source.YouTubePlaybackSessionStore
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.ChartItem
import com.sonicmusic.app.domain.model.ChartKind
import com.sonicmusic.app.domain.model.ChartOrigin
import com.sonicmusic.app.domain.model.ChartPage
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.HomeShelfItem
import com.sonicmusic.app.domain.repository.ChartsRepository
import com.sonicmusic.app.domain.repository.HomeFeedScope
import com.sonicmusic.app.domain.repository.HomeSessionRejectedException
import com.sonicmusic.app.features.library.data.YouTubeMusicLibrary
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class ChartsRepositoryImpl @Inject constructor(
    private val service: YouTubeiService,
    private val sessions: YouTubePlaybackSessionStore,
    private val accountLibrary: YouTubeMusicLibrary,
    private val cache: YtmJsonCache
) : ChartsRepository {
    private val locks = ChartKind.entries.associateWith { Mutex() }
    private val feedLock = Mutex()
    private val feeds = LruCache<HomeFeedScope, YtmJsonCache.Entry<YouTubeiService.RegionalCharts>>(4)
    private val pages = LruCache<Pair<HomeFeedScope, ChartKind>, YtmJsonCache.Entry<ChartPage>>(12)

    override suspend fun cachedChart(kind: ChartKind, scope: HomeFeedScope): ChartPage? =
        read(kind, scope)?.data

    override suspend fun refreshChart(kind: ChartKind, scope: HomeFeedScope, force: Boolean): ChartPage =
        withContext(Dispatchers.Default) {
            locks.getValue(kind).withLock {
                val cached = read(kind, scope)
                if (!force && cached?.isFresh(CACHE_TTL) == true) return@withLock cached.data
                val session = activeSession(scope)
                val regional = regionalFeed(scope, session, force)
                val result = when (kind) {
                    ChartKind.Songs -> songs(regional, scope, session)
                    ChartKind.Artists -> ChartPage(itemsOf(regional, ContentType.ARTIST, "Top artists"))
                    ChartKind.Albums -> albums(regional, scope)
                }
                activeSession(scope)
                save(kind, scope, result, System.currentTimeMillis())
                result
            }
        }

    override suspend fun loadMoreAlbums(scope: HomeFeedScope): ChartPage? = withContext(Dispatchers.Default) {
        val kind = ChartKind.Albums
        locks.getValue(kind).withLock {
            val cached = read(kind, scope) ?: return@withLock null
            if (!cached.data.hasMore) return@withLock cached.data
            val releases = accountLibrary.newReleases(scope, append = true)
            val page = cached.data.copy(items = releaseAlbums(releases.items).ifEmpty { cached.data.items }, hasMore = releases.hasMore)
            save(kind, scope, page, cached.checkedAt)
            page
        }
    }

    private suspend fun activeSession(scope: HomeFeedScope): YouTubePlaybackSession? {
        if (!scope.isPersonalized) return null
        val snapshot = sessions.snapshot()
        return snapshot.session?.takeIf { snapshot.revision == scope.sessionRevision && it.homeCacheKey == scope.accountKey }
            ?: throw CancellationException("YouTube Music account changed")
    }

    private suspend fun regionalFeed(
        scope: HomeFeedScope,
        session: YouTubePlaybackSession?,
        force: Boolean
    ): YouTubeiService.RegionalCharts = feedLock.withLock {
        val cached = feeds.get(scope)
        if (!force && cached?.isFresh(CACHE_TTL) == true) return@withLock cached.data
        val result = if (session == null) {
            service.browseRegionalCharts(scope.region, null)
        } else {
            try {
                service.browseRegionalCharts(scope.region, session)
            } catch (_: HomeSessionRejectedException) {
                service.browseRegionalCharts(scope.region, null)
            }
        }
        feeds.put(scope, YtmJsonCache.Entry(result, System.currentTimeMillis()))
        result
    }

    private fun itemsOf(regional: YouTubeiService.RegionalCharts, type: ContentType, shelfTitle: String? = null): List<ChartItem> {
        val shelves = regional.feed.shelves
        val selected = shelves.filter { it.title.equals(shelfTitle, ignoreCase = true) }.ifEmpty { shelves }
        return selected.flatMap { shelf ->
            shelf.items.mapNotNull { item ->
                val entry = (item as? HomeShelfItem.Media)?.song?.takeIf { it.contentType == type }
                    ?: return@mapNotNull null
                ChartItem(entry, regional.ranks[item.key])
            }
        }.distinctBy { it.media.id }
    }

    private suspend fun songs(
        regional: YouTubeiService.RegionalCharts,
        scope: HomeFeedScope,
        session: YouTubePlaybackSession?
    ): ChartPage {
        val direct = itemsOf(regional, ContentType.SONG)
        if (direct.isNotEmpty()) return ChartPage(direct)

        val playlists = regional.feed.shelves.flatMap { it.items }.filterIsInstance<HomeShelfItem.Playlist>()
        val audioChart = playlists.firstOrNull { audioChartTitle.containsMatchIn(it.title) }
        if (audioChart != null) {
            try {
                val tracks = playlistTracks(audioChart, scope, session, allowMusicVideos = false)
                if (tracks.isNotEmpty()) return ChartPage(tracks, sourceTitle = audioChart.title)
            } catch (_: HomeSessionRejectedException) {
                // A guest chart is available even after an authenticated session expires.
            }
        }

        val publicChart = playlists.firstOrNull { weeklyVideoTitle.containsMatchIn(it.title) }
            ?: playlists.firstOrNull { dailyVideoTitle.containsMatchIn(it.title) }
            ?: return ChartPage(emptyList())
        val tracks = playlistTracks(publicChart, scope, null, allowMusicVideos = true)
        return ChartPage(tracks, ChartOrigin.MusicVideos, publicChart.title)
    }

    private suspend fun playlistTracks(
        playlist: HomeShelfItem.Playlist,
        scope: HomeFeedScope,
        session: YouTubePlaybackSession?,
        allowMusicVideos: Boolean
    ): List<ChartItem> = service.browseChartCollection(
        function = "playlist",
        region = scope.region,
        language = scope.language,
        playlistId = playlist.playlist.id,
        session = session,
        allowMusicVideos = allowMusicVideos
    ).mapNotNull { (item, rank) ->
        val song = (item as? HomeShelfItem.Media)?.song ?: return@mapNotNull null
        if (song.contentType == ContentType.SONG || (allowMusicVideos && song.contentType == ContentType.VIDEO)) {
            ChartItem(song, rank)
        } else null
    }.distinctBy { it.media.id }

    private suspend fun albums(regional: YouTubeiService.RegionalCharts, scope: HomeFeedScope): ChartPage {
        val ranked = itemsOf(regional, ContentType.ALBUM)
        if (ranked.isNotEmpty()) return ChartPage(ranked)
        val saved = accountLibrary.newReleases(scope)
        val releases = saved.items.ifEmpty {
            service.browseChartCollection("new_release_albums", scope.region, scope.language).map { it.first }
        }
        return ChartPage(releaseAlbums(releases), ChartOrigin.NewReleases, hasMore = saved.hasMore)
    }

    private fun releaseAlbums(entries: List<HomeShelfItem>): List<ChartItem> =
        entries.mapNotNull { entry ->
            val album = (entry as? HomeShelfItem.Media)?.song?.takeIf { it.contentType == ContentType.ALBUM }
            album?.let { ChartItem(it) }
        }.distinctBy { it.media.id }

    private suspend fun save(kind: ChartKind, scope: HomeFeedScope, page: ChartPage, checkedAt: Long) {
        pages.put(scope to kind, YtmJsonCache.Entry(page, checkedAt))
        try {
            cache.write(scope, fileName(kind), ChartPage.serializer(), page, checkedAt)
        } catch (_: IOException) {
            // The screen can still display the fetched chart if the disk is unavailable.
        }
    }

    private suspend fun read(kind: ChartKind, scope: HomeFeedScope): YtmJsonCache.Entry<ChartPage>? {
        val key = scope to kind
        pages.get(key)?.let { return it }
        return try {
            cache.read(scope, fileName(kind), ChartPage.serializer())?.also { pages.put(key, it) }
        } catch (_: IOException) {
            null
        }
    }

    private fun fileName(kind: ChartKind) = "chart_${kind.name.lowercase(Locale.ROOT)}"

    private companion object {
        const val CACHE_TTL = 60 * 60_000L
        val audioChartTitle = Regex("^(?:(?:weekly|daily) )?top (?:\\d+ )?songs(?:$| [-–—]| (?!on shorts))", RegexOption.IGNORE_CASE)
        val weeklyVideoTitle = Regex("^(?:weekly )?top (?:\\d+ )?music videos(?:$| )", RegexOption.IGNORE_CASE)
        val dailyVideoTitle = Regex("^daily top (?:\\d+ )?music videos(?:$| )", RegexOption.IGNORE_CASE)
    }
}

