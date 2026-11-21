package com.sonicmusic.app.features.home.data

import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.sonicmusic.app.data.local.dao.BrowseSectionDao
import com.sonicmusic.app.data.local.cache.YtmJsonCache
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.CachedHomeFeed
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.HomeShelf
import com.sonicmusic.app.domain.model.HomeShelfItem
import com.sonicmusic.app.domain.repository.HomeFeedScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeCacheDataSource @Inject constructor(
    private val dao: BrowseSectionDao,
    private val youtubeMusic: YouTubeiService,
    private val files: YtmJsonCache
) {
    internal data class Snapshot(val feed: CachedHomeFeed, val cachedAt: Long) {
        val isFresh get() = feed.schemaVersion == CachedHomeFeed.CURRENT_VERSION &&
            System.currentTimeMillis() - cachedAt in 0L until FRESHNESS_MS
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val memory = LruCache<String, Snapshot>(2)

    private fun key(scope: HomeFeedScope) = "home_feed_v2:${scope.region}:${scope.language}:${scope.accountKey}"

    internal suspend fun read(scope: HomeFeedScope): Snapshot? {
        val key = key(scope)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val saved = files.read(scope, "home", CachedHomeFeed.serializer())
                if (saved != null) {
                    val restored = Snapshot(restoreFeed(saved.data), saved.checkedAt)
                    return@withContext keepNewest(key, restored)
                }
                val row = dao.getCache(key)?.takeIf { it.expiresAt > System.currentTimeMillis() }
                    ?: return@withContext null
                val feed = try {
                    json.decodeFromString(CachedHomeFeed.serializer(), row.jsonData)
                } catch (_: SerializationException) {
                    // The previous cache format stored only the first page.
                    val firstPage = json.decodeFromString(HomeContent.serializer(), row.jsonData)
                    if (firstPage.shelves.isEmpty() && firstPage.continuation == null) return@withContext null
                    CachedHomeFeed(firstPage)
                }
                val restored = Snapshot(restoreFeed(feed), row.cachedAt)
                try {
                    files.write(scope, "home", CachedHomeFeed.serializer(), restored.feed, row.cachedAt)
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Home cache migration could not be saved")
                }
                keepNewest(key, restored)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Home cache could not be read", e)
                null
            }
        }
    }

    private fun keepNewest(key: String, restored: Snapshot): Snapshot = synchronized(memory) {
        memory.get(key)?.takeIf { it.cachedAt >= restored.cachedAt }
            ?: restored.also { memory.put(key, it) }
    }

    private fun restoreFeed(feed: CachedHomeFeed): CachedHomeFeed {
        if (feed.schemaVersion == CachedHomeFeed.CURRENT_VERSION) return feed
        fun HomeContent.keepAudioTracks() = copy(shelves = shelves.mapNotNull { shelf ->
            val items = if (feed.schemaVersion >= 1) shelf.items else shelf.items.filter { item ->
                val song = (item as? HomeShelfItem.Media)?.song ?: return@filter true
                when (song.contentType) {
                    // Legacy caches did not retain the source track type.
                    ContentType.SONG -> song.albumBrowseId?.startsWith("MPRE") == true ||
                        Uri.parse(song.thumbnailUrl).host in LEGACY_ARTWORK_HOSTS
                    ContentType.ARTIST, ContentType.ALBUM -> true
                    else -> false
                }
            }
            items.takeIf { it.isNotEmpty() }?.let {
                val kind = youtubeMusic.homeShelfKind(shelf.title)
                val layout = if (kind == HomeShelf.Kind.ListenAgain) HomeShelf.Layout.Grid else shelf.layout
                shelf.copy(items = it, kind = kind, layout = layout)
            }
        })
        return feed.copy(
            firstPage = feed.firstPage.keepAudioTracks(),
            loadedPages = feed.loadedPages.map { it.copy(content = it.content.keepAudioTracks()) }
        )
    }

    internal suspend fun save(scope: HomeFeedScope, feed: CachedHomeFeed, cachedAt: Long = System.currentTimeMillis()) {
        val key = key(scope)
        synchronized(memory) { memory.put(key, Snapshot(feed, cachedAt)) }
        withContext(Dispatchers.IO) {
            try {
                files.write(scope, "home", CachedHomeFeed.serializer(), feed, cachedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Home cache could not be saved", e)
            }
        }
    }

    private companion object {
        const val TAG = "HomeCache"
        const val FRESHNESS_MS = 20 * 60 * 1000L
        val LEGACY_ARTWORK_HOSTS = setOf("lh3.googleusercontent.com", "yt3.googleusercontent.com")
    }
}
