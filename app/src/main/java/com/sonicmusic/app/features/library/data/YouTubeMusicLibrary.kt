package com.sonicmusic.app.features.library.data

import android.os.SystemClock
import android.util.Log
import com.sonicmusic.app.core.network.HttpStatusException
import com.sonicmusic.app.core.network.NetworkMonitor
import com.sonicmusic.app.data.local.cache.YtmJsonCache
import com.sonicmusic.app.data.remote.source.YouTubePlaybackSessionStore
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.data.remote.source.canonicalPublicPlaylistId
import com.sonicmusic.app.data.remote.source.homeDigest
import com.sonicmusic.app.domain.model.HomeContinuation
import com.sonicmusic.app.domain.model.HomeShelfItem
import com.sonicmusic.app.domain.repository.HomeFeedScope
import com.sonicmusic.app.domain.repository.HomeRepository
import com.sonicmusic.app.domain.repository.HomeSessionRejectedException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

enum class MusicLibrarySection(val fileName: String, val ttlMs: Long) {
    SavedSongs("library", 60 * 60_000L),
    Playlists("playlists", 30 * 60_000L),
    Likes("liked_songs", 20 * 60_000L),
    History("history", 10 * 60_000L),
    Albums("albums", 60 * 60_000L),
    Artists("artists", 60 * 60_000L),
    NewReleases("new_release_albums", 6 * 60 * 60_000L)
}

data class MusicLibraryPage(
    val items: List<HomeShelfItem> = emptyList(),
    val hasMore: Boolean = false,
    val accountMarker: String = "",
    val isLoading: Boolean = false
) {
    fun playlistRoute(playlistId: String) = "$ACCOUNT_PLAYLIST_ROUTE$accountMarker:$playlistId"
}

internal const val ACCOUNT_PLAYLIST_ROUTE = "ytm-account:"

@Singleton
class YouTubeMusicLibrary @Inject constructor(
    private val home: HomeRepository,
    private val sessions: YouTubePlaybackSessionStore,
    private val files: YtmJsonCache,
    private val service: YouTubeiService,
    private val network: NetworkMonitor
) {
    @Serializable
    private data class Page(
        val items: List<HomeShelfItem>,
        val continuation: HomeContinuation?,
        val firstPageSize: Int = items.size,
        val firstToken: String? = null,
        val fetchedTokens: Set<String> = emptySet()
    )

    private data class ScopedPage(val scope: HomeFeedScope, val entry: YtmJsonCache.Entry<Page>)
    private val updates = MutableStateFlow(emptyMap<MusicLibrarySection, ScopedPage>())
    private val requests = MusicLibrarySection.entries.associateWith { Mutex() }
    private val detailRequests = Mutex()
    private val detailMemory = android.util.LruCache<Pair<HomeFeedScope, String>, YtmJsonCache.Entry<Page>>(2)
    private val permits = Semaphore(2)
    private val retryAfter = mutableMapOf<MusicLibrarySection, Pair<HomeFeedScope, Long>>()
    private val rejected = ConcurrentHashMap<MusicLibrarySection, Pair<HomeFeedScope, Long>>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(section: MusicLibrarySection) = home.scopes.flatMapLatest { scope ->
        if (!scope.isPersonalized) flowOf(MusicLibraryPage()) else channelFlow {
            send(MusicLibraryPage(accountMarker = scope.accountMarker(), isLoading = true))
            val cached = read(scope, section)
            if (cached != null) send(cached.data.toView(scope))
            launch {
                network.isOnlineState.collectLatest { online ->
                    if (online) runRefresh(scope, section)
                    if (read(scope, section) == null) send(MusicLibraryPage(accountMarker = scope.accountMarker()))
                }
            }
            updates.map { it[section]?.takeIf { update -> update.scope == scope }?.entry?.data }
                .filterNotNull().collect { send(it.toView(scope)) }
        }
    }.distinctUntilChanged()

    suspend fun loadMore(section: MusicLibrarySection) = withContext(Dispatchers.Default) {
        val scope = home.scopes.first().takeIf { it.isPersonalized } ?: return@withContext
        if (!network.isOnlineState.value || isRejectionRecent(section, scope)) return@withContext
        val lock = requests.getValue(section)
        if (!lock.tryLock()) return@withContext
        try {
            val current = read(scope, section) ?: return@withContext
            val cursor = current.data.continuation ?: return@withContext
            try {
                val (items, next) = fetch(scope, section.fileName, cursor)
                val consumed = current.data.fetchedTokens + cursor.token
                val page = current.data.copy(
                    items = (current.data.items + items).distinctBy(HomeShelfItem::key),
                    continuation = next?.takeUnless { it.token in consumed },
                    fetchedTokens = consumed
                )
                publish(scope, section, page, current.checkedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (_: HomeSessionRejectedException) {
                markRejected(section, scope)
            } catch (_: Exception) {
                Log.d(TAG, "Collection continuation unavailable for ${section.fileName}")
            }
        } finally {
            lock.unlock()
        }
    }

    // A network-constrained worker uses the same locks and freshness rules as visible screens.
    suspend fun syncActiveAccount(): Boolean {
        val scope = home.scopes.first().takeIf { it.isPersonalized } ?: return true
        var success = true
        try {
            home.refreshHome(scope, force = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: HomeSessionRejectedException) {
            Log.d(TAG, "Account Home unavailable")
        } catch (_: Exception) {
            success = false
        }
        for (section in MusicLibrarySection.entries) {
            if (!runRefresh(scope, section)) success = false
        }
        return success
    }

    suspend fun refreshPlaylists() {
        refreshNow(MusicLibrarySection.Playlists)
    }

    internal suspend fun newReleases(scope: HomeFeedScope, append: Boolean = false): MusicLibraryPage {
        if (!scope.isPersonalized) return MusicLibraryPage()
        if (append) loadMore(MusicLibrarySection.NewReleases)
        else runRefresh(scope, MusicLibrarySection.NewReleases)
        val snapshot = read(scope, MusicLibrarySection.NewReleases)
        return checkedScope(scope) { snapshot?.data.toView(scope) }
    }

    suspend fun refreshNow(section: MusicLibrarySection) {
        val scope = home.scopes.first().takeIf { it.isPersonalized } ?: return
        runRefresh(scope, section, force = true)
    }

    internal fun playlistVisible(route: String) = home.scopes.map { scope ->
        scope.isPersonalized && route.startsWith("$ACCOUNT_PLAYLIST_ROUTE${scope.accountMarker()}:")
    }.distinctUntilChanged()

    internal suspend fun cachedPlaylistPage(route: String): MusicLibraryPage? {
        val (scope, playlist) = playlistScope(route)
        val file = "playlist_${homeDigest(playlist).take(24)}"
        val cached = detailMemory.get(scope to file) ?: readFile(scope, file) ?: return null
        return checkedScope(scope) { cached.data.toView(scope) }
    }

    internal suspend fun playlistPage(route: String, force: Boolean, append: Boolean): MusicLibraryPage =
        withContext(Dispatchers.Default) { detailRequests.withLock {
            val (scope, playlist) = playlistScope(route)
            val file = "playlist_${homeDigest(playlist).take(24)}"
            val cached = detailMemory.get(scope to file) ?: readFile(scope, file)
            if (!append && !force && cached?.isFresh(MusicLibrarySection.Playlists.ttlMs) == true) {
                return@withLock checkedScope(scope) { cached.data.toView(scope) }
            }
            try {
                val current = cached?.data
                val cursor = if (append) current?.continuation else null
                if (append && cursor == null) return@withLock checkedScope(scope) { current.toView(scope) }
                val (items, next) = fetch(scope, "playlist", cursor, mapOf("playlistId" to playlist))
                val snapshot = if (current != null && cursor != null) {
                    val consumed = current.fetchedTokens + cursor.token
                    current.copy(items = (current.items + items).distinctBy(HomeShelfItem::key),
                        continuation = next?.takeUnless { it.token in consumed }, fetchedTokens = consumed)
                } else {
                    reconcile(current, items, next)
                }
                val checkedAt = if (append) checkNotNull(cached).checkedAt else System.currentTimeMillis()
                detailMemory.put(scope to file, YtmJsonCache.Entry(snapshot, checkedAt))
                write(scope, file, snapshot, checkedAt)
                checkedScope(scope) { snapshot.toView(scope) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cached == null || append) throw e
                checkedScope(scope) { cached.data.toView(scope) }
            }
        } }

    private suspend fun playlistScope(route: String): Pair<HomeFeedScope, String> {
        val routeParts = route.removePrefix(ACCOUNT_PLAYLIST_ROUTE).split(':', limit = 2)
        val scope = home.scopes.first()
        if (!scope.isPersonalized || routeParts.size != 2 || scope.accountMarker() != routeParts[0]) {
            throw HomeSessionRejectedException()
        }
        val playlist = canonicalPublicPlaylistId(routeParts[1]) ?: throw IOException("Invalid playlist")
        return scope to playlist
    }

    private suspend fun runRefresh(scope: HomeFeedScope, section: MusicLibrarySection, force: Boolean = false): Boolean =
        withContext(Dispatchers.Default) { refresh(scope, section, force) }

    private suspend fun refresh(scope: HomeFeedScope, section: MusicLibrarySection, force: Boolean): Boolean =
        requests.getValue(section).withLock {
            if (!force && isRejectionRecent(section, scope)) return@withLock true
            val retry = synchronized(retryAfter) { retryAfter[section] }
            if (!force && retry?.first == scope && SystemClock.elapsedRealtime() < retry.second) return@withLock false
            val cached = read(scope, section)
            if (!force && cached?.isFresh(section.ttlMs) == true) return@withLock true
            try {
                val (items, next) = fetch(scope, section.fileName)
                val page = reconcile(cached?.data, items, next)
                publish(scope, section, page, System.currentTimeMillis())
                synchronized(retryAfter) { retryAfter.remove(section) }
                rejected.remove(section)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: HomeSessionRejectedException) {
                markRejected(section, scope)
                true
            } catch (e: Exception) {
                val transient = e is IOException || (e is HttpStatusException && (e.code == 429 || e.code >= 500))
                synchronized(retryAfter) { retryAfter[section] = scope to (SystemClock.elapsedRealtime() + RETRY_DELAY_MS) }
                Log.d(TAG, "Collection refresh unavailable for ${section.fileName}")
                !transient
            }
        }

    private fun isRejectionRecent(section: MusicLibrarySection, scope: HomeFeedScope): Boolean {
        val result = rejected[section] ?: return false
        return result.first == scope && SystemClock.elapsedRealtime() - result.second < SESSION_RETRY_MS
    }

    private fun markRejected(section: MusicLibrarySection, scope: HomeFeedScope) {
        rejected[section] = scope to SystemClock.elapsedRealtime()
    }

    private fun reconcile(previous: Page?, items: List<HomeShelfItem>, next: HomeContinuation?): Page {
        if (previous != null && previous.items.take(previous.firstPageSize) == items && previous.firstToken == next?.token) {
            return previous
        }
        return Page(items, next, firstToken = next?.token)
    }

    private suspend fun fetch(
        scope: HomeFeedScope,
        name: String,
        continuation: HomeContinuation? = null,
        bindings: Map<String, String> = emptyMap()
    ): Pair<List<HomeShelfItem>, HomeContinuation?> = permits.withPermit {
        val snapshot = sessions.snapshot()
        val session = snapshot.session
        if (session == null || snapshot.revision != scope.sessionRevision || session.homeCacheKey != scope.accountKey) {
            throw CancellationException("YouTube Music account changed")
        }
        val result = service.browseAccountCollection(name, scope.region, scope.language, session, continuation, bindings)
        checkedScope(scope) { result }
    }

    private suspend fun <T> checkedScope(scope: HomeFeedScope, result: () -> T): T {
        if (home.scopes.first() != scope) throw CancellationException("YouTube Music account or region changed")
        return result()
    }

    private suspend fun read(scope: HomeFeedScope, section: MusicLibrarySection): YtmJsonCache.Entry<Page>? =
        updates.value[section]?.takeIf { it.scope == scope }?.entry ?: readFile(scope, section.fileName)

    private suspend fun readFile(scope: HomeFeedScope, name: String): YtmJsonCache.Entry<Page>? =
        try {
            files.read(scope, name, Page.serializer())
        } catch (_: IOException) {
            null
        }

    private suspend fun publish(scope: HomeFeedScope, section: MusicLibrarySection, page: Page, checkedAt: Long) {
        checkedScope(scope) {
            updates.update { it + (section to ScopedPage(scope, YtmJsonCache.Entry(page, checkedAt))) }
        }
        write(scope, section.fileName, page, checkedAt)
    }

    private suspend fun write(scope: HomeFeedScope, name: String, page: Page, checkedAt: Long) {
        try {
            files.write(scope, name, Page.serializer(), page, checkedAt)
        } catch (_: IOException) {
            Log.d(TAG, "Collection cache write unavailable")
        }
    }

    private fun HomeFeedScope.accountMarker() = homeDigest(accountKey).take(24)
    private fun Page?.toView(scope: HomeFeedScope) =
        MusicLibraryPage(this?.items.orEmpty(), this?.continuation != null, scope.accountMarker())

    private companion object {
        const val TAG = "YouTubeMusicLibrary"
        const val RETRY_DELAY_MS = 60_000L
        const val SESSION_RETRY_MS = 15 * 60_000L
    }
}
