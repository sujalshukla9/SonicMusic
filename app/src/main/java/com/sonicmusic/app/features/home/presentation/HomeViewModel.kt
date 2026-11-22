package com.sonicmusic.app.features.home.presentation

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.R
import com.sonicmusic.app.core.network.NetworkMonitor
import com.sonicmusic.app.data.local.dao.DownloadedSongDao
import com.sonicmusic.app.data.local.dao.PlaylistDao
import com.sonicmusic.app.domain.model.CachedHomeFeed
import com.sonicmusic.app.domain.model.CommunityPlaylist
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.HomeShelf
import com.sonicmusic.app.domain.model.HomeShelfItem
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.deviceLocalSongId
import com.sonicmusic.app.domain.repository.BrowseRepository
import com.sonicmusic.app.domain.repository.HomeFeedScope
import com.sonicmusic.app.domain.repository.HomeSessionRejectedException
import com.sonicmusic.app.domain.repository.PlaylistRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.features.home.domain.GetHomeContentUseCase
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import com.sonicmusic.app.features.queue.data.QueueRepositoryImpl
import com.sonicmusic.app.shared.components.pullrefresh.RefreshResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getHomeContentUseCase: GetHomeContentUseCase,
    private val playerServiceConnection: PlayerServiceConnection,
    private val songRepository: SongRepository,
    private val queueRepository: QueueRepositoryImpl,
    private val browseRepository: BrowseRepository,
    networkMonitor: NetworkMonitor,
    downloadedSongDao: DownloadedSongDao,
    playlistDao: PlaylistDao,
    private val playlistRepository: PlaylistRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    data class AccountNotice(val reconnect: Boolean, val showingPublicFeed: Boolean)
    data class HomeUiState(
        val content: HomeContent = HomeContent(),
        val recentlyPlayed: List<Song> = emptyList(),
        val isLoading: Boolean = true
    )

    private val loadedFeed = MutableStateFlow<HomeContent?>(null)
    private val playlistPicks = MutableStateFlow<List<Song>>(emptyList())
    private val _feedUnavailable = MutableStateFlow(false)
    val feedUnavailable = _feedUnavailable.asStateFlow()
    private val _accountNotice = MutableStateFlow<AccountNotice?>(null)
    val accountNotice = _accountNotice.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()
    private val _refreshResult = MutableStateFlow<RefreshResult?>(null)
    val refreshResult = _refreshResult.asStateFlow()
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()
    private val _manualPagination = MutableStateFlow(false)
    val manualPagination = _manualPagination.asStateFlow()

    private var scope: HomeFeedScope? = null
    private var firstPage: HomeContent? = null
    private var showingPublicFeed = false
    private var deferredRefresh: HomeContent? = null
    private var scrolling = false
    private var atTop = true
    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var picksJob: Job? = null
    private var picksSourceId: String? = null
    private val consumedTokens = mutableSetOf<String>()

    val isOnline = networkMonitor.isOnlineState
    val playbackCompleted = getHomeContentUseCase.playbackCompleted
    private val recentlyPlayed: StateFlow<List<Song>> = getHomeContentUseCase.recentlyPlayed
        .catch {
            Log.w(TAG, "Unable to read listening history", it)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val allowGuestPicks = MutableStateFlow(false)
    val homeUiState = combine(loadedFeed, recentlyPlayed, playlistPicks, allowGuestPicks) { feed, history, picks, guest ->
        if (feed == null) HomeUiState()
        else HomeUiState(if (guest) withQuickPicks(feed, history, picks) else feed, history, isLoading = false)
    }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    private val completedSongIds = downloadedSongDao.observeCompletedSongIds()
    val downloadedSongIds = completedSongIds
        .map { it.toSet() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val savedPlaylistRemoteIds = playlistDao.observeRemoteIds()
        .map { it.toSet() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        viewModelScope.launch {
            getHomeContentUseCase.scopes.collect { newScope ->
                loadJob?.cancel()
                pageJob?.cancel()
                loadedFeed.value = null
                resetFallbackPicks()
                loadJob = null
                pageJob = null
                scope = newScope
                firstPage = null
                showingPublicFeed = false
                _accountNotice.value = null
                deferredRefresh = null
                consumedTokens.clear()
                allowGuestPicks.value = !newScope.isPersonalized
                _isLoadingMore.value = false
                _manualPagination.value = false
                _isRefreshing.value = false
                _refreshResult.value = null
                _error.value = null
                loadHomeContent()
            }
        }
        viewModelScope.launch {
            isOnline.drop(1).collect { if (it) loadHomeContent() }
        }
    }

    fun onFeedScrollChanged(isScrolling: Boolean, isAtTop: Boolean) {
        scrolling = isScrolling
        atTop = isAtTop
        if (canApplyRefresh()) deferredRefresh?.let { publishFirstPage(it) }
    }

    private fun canApplyRefresh() = !scrolling && (atTop || consumedTokens.isEmpty())

    fun loadHomeContent() = load(force = false)

    fun refreshHomeContent() {
        if (_isRefreshing.value) return
        _refreshResult.value = null
        _isRefreshing.value = true
        if (scope == null) {
            _isRefreshing.value = false
            _refreshResult.value = RefreshResult.ERROR
        } else {
            load(force = true)
        }
    }

    private fun load(force: Boolean) {
        val activeScope = scope ?: return
        if (loadJob?.isActive == true || (!force && pageJob?.isActive == true)) return
        val previousPageJob = pageJob
        loadJob = viewModelScope.launch {
            _feedUnavailable.value = false
            var success = false
            try {
                previousPageJob?.cancelAndJoin()
                if (firstPage == null) {
                    getHomeContentUseCase.cached(activeScope)?.let { cached ->
                        restoreCachedFeed(cached)
                        if (!activeScope.isPersonalized && isOnline.value) {
                            loadFallbackPicks(cached.firstPage, activeScope, force)
                        }
                    }
                }
                if (!isOnline.value) {
                    if (activeScope.isPersonalized && firstPage == null) showAccountFallback(activeScope, reconnect = false)
                    return@launch
                }

                val fresh = getHomeContentUseCase.refresh(activeScope, force || _isRefreshing.value)
                if (scope != activeScope) return@launch
                success = fresh.shelves.isNotEmpty() || fresh.continuation != null
                _feedUnavailable.value = !success
                if (success) {
                    val switchingToAccount = showingPublicFeed
                    val previous = firstPage.takeUnless { switchingToAccount }
                    val reconciled = withContext(Dispatchers.Default) {
                        if (!force && fresh == previous) null else reuseShelves(fresh, previous)
                    }
                    if (reconciled != null) {
                        if (!switchingToAccount && previous != null && previous.shelves == reconciled.shelves &&
                            (force || previous.continuation != reconciled.continuation)
                        ) {
                            // Restart the cursor chain without removing shelves the user can already see.
                            publishFirstPage(reconciled, forcePicks = force, shelves = loadedFeed.value?.shelves ?: reconciled.shelves)
                        } else if (!switchingToAccount && !canApplyRefresh() && !loadedFeed.value?.shelves.isNullOrEmpty()) {
                            deferredRefresh = reconciled
                        } else {
                            if (switchingToAccount) {
                                resetFallbackPicks()
                                showingPublicFeed = false
                            }
                            publishFirstPage(reconciled, forcePicks = force)
                        }
                    } else {
                        deferredRefresh = null
                        if (!activeScope.isPersonalized) loadFallbackPicks(fresh, activeScope, force)
                    }
                    _accountNotice.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeScope.isPersonalized) showAccountFallback(activeScope, e is HomeSessionRejectedException)
                _feedUnavailable.value = loadedFeed.value?.shelves.isNullOrEmpty()
                Log.w(TAG, "Unable to refresh Home", e)
            } finally {
                if (scope == activeScope) {
                    if (loadedFeed.value == null) loadedFeed.value = HomeContent()
                    if (_isRefreshing.value) {
                        _refreshResult.value = if (success) RefreshResult.SUCCESS else RefreshResult.ERROR
                        _isRefreshing.value = false
                    }
                }
            }
        }
    }

    private suspend fun showAccountFallback(activeScope: HomeFeedScope, reconnect: Boolean) {
        if (scope != activeScope) return
        // A cached account page still belongs to the connected account, even if refresh fails.
        if (loadedFeed.value?.shelves.isNullOrEmpty() && loadedFeed.value?.continuation == null) {
            try {
                val cached = getHomeContentUseCase.cached(activeScope.publicFeed())
                val publicFeed = cached?.takeIf {
                    it.firstPage.shelves.isNotEmpty() || it.firstPage.continuation != null
                } ?: if (isOnline.value) {
                    CachedHomeFeed(getHomeContentUseCase.refresh(activeScope, force = false, usePublicFeed = true))
                } else null
                if (scope != activeScope) return
                if (publicFeed != null) {
                    resetFallbackPicks()
                    showingPublicFeed = true
                    restoreCachedFeed(publicFeed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Public Home fallback unavailable", e)
            }
        }
        _accountNotice.value = AccountNotice(reconnect, showingPublicFeed)
    }

    private fun resetFallbackPicks() {
        picksJob?.cancel()
        picksJob = null
        picksSourceId = null
        playlistPicks.value = emptyList()
    }

    private fun withQuickPicks(feed: HomeContent, history: List<Song>, fallback: List<Song>): HomeContent {
        if (feed.shelves.any { it.kind == HomeShelf.Kind.QuickPicks }) return feed
        val heard = history.mapTo(hashSetOf(), Song::id)
        val seen = hashSetOf<String>()
        val candidates = feed.shelves.asSequence()
            .flatMap { it.items.asSequence().mapNotNull(HomeShelfItem::playableSong) }
            .plus(fallback.asSequence())
            .filter { seen.add(it.id) }
            .take(40)
            .toList()
        val fresh = candidates.filterNot { it.id in heard }
        val songs = fresh.ifEmpty { candidates }.take(16)
        if (songs.isEmpty()) return feed
        val shelf = HomeShelf(
            id = "home_quick_picks",
            title = context.getString(R.string.home_quick_picks),
            subtitle = context.getString(R.string.home_quick_picks_subtitle),
            items = songs.map { HomeShelfItem.Media(it) },
            kind = HomeShelf.Kind.QuickPicks
        )
        return feed.copy(shelves = listOf(shelf) + feed.shelves)
    }

    private fun loadFallbackPicks(feed: HomeContent, activeScope: HomeFeedScope, force: Boolean) {
        val shelves = feed.shelves
        if (shelves.any { shelf -> shelf.kind == HomeShelf.Kind.QuickPicks ||
            shelf.items.any { it.playableSong != null }
        }) {
            resetFallbackPicks()
            return
        }
        val playlistId = shelves.asSequence().flatMap { it.items.asSequence() }
            .filterIsInstance<HomeShelfItem.Playlist>()
            .firstOrNull()?.playlist?.browseId ?: run {
                resetFallbackPicks()
                return
            }
        if (picksSourceId == playlistId && (picksJob?.isActive == true || !force)) return
        resetFallbackPicks()
        picksSourceId = playlistId
        val usePublicFeed = showingPublicFeed
        picksJob = viewModelScope.launch {
            try {
                val tracks = getHomeContentUseCase.quickPicksFromPlaylist(activeScope, playlistId, usePublicFeed)
                if (scope == activeScope && showingPublicFeed == usePublicFeed && tracks.isNotEmpty()) {
                    playlistPicks.value = tracks.take(40)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Unable to load tracks for Quick Picks", e)
            }
        }
    }

    private fun reuseShelves(fresh: HomeContent, previous: HomeContent?): HomeContent {
        if (previous == null) return fresh
        val oldShelves = previous.shelves.associateBy { it.id }
        return fresh.copy(shelves = fresh.shelves.map { shelf -> oldShelves[shelf.id]?.takeIf { it == shelf } ?: shelf })
    }

    private fun publishFirstPage(
        content: HomeContent,
        forcePicks: Boolean = false,
        shelves: List<HomeShelf> = content.shelves
    ) {
        firstPage = content
        deferredRefresh = null
        consumedTokens.clear()
        _manualPagination.value = false
        loadedFeed.value = if (shelves === content.shelves) content else content.copy(shelves = shelves)
        scope?.takeUnless { it.isPersonalized }?.let { loadFallbackPicks(content, it, forcePicks) }
    }

    private suspend fun restoreCachedFeed(cached: CachedHomeFeed) {
        val (content, tokens) = withContext(Dispatchers.Default) {
            val visited = mutableSetOf<String>()
            var visible = cached.firstPage
            for (page in cached.loadedPages) {
                if (visible.continuation?.token != page.requestToken || !visited.add(page.requestToken)) break
                visible = appendPage(visible, page.content)
            }
            val next = visible.continuation?.takeUnless { it.token in visited }
            visible.copy(continuation = next) to visited
        }
        firstPage = cached.firstPage
        consumedTokens.clear()
        consumedTokens.addAll(tokens)
        _manualPagination.value = false
        loadedFeed.value = content
    }

    fun loadMoreHomeContent(userRequested: Boolean = false) {
        val activeScope = scope ?: return
        if (pageJob?.isActive == true || _isRefreshing.value || !isOnline.value) return
        if (_manualPagination.value && !userRequested) return
        if (loadedFeed.value?.continuation == null) return
        val initialLoad = loadJob

        pageJob = viewModelScope.launch {
            initialLoad?.join()
            if (scope != activeScope || !isOnline.value) return@launch
            deferredRefresh?.let { refresh ->
                if (!userRequested) return@launch
                publishFirstPage(refresh)
            }
            _isLoadingMore.value = true
            try {
                repeat(MAX_PAGES_PER_LOAD) {
                    val current = loadedFeed.value ?: return@launch
                    val cursor = current.continuation ?: return@launch
                    if (cursor.token in consumedTokens) return@launch

                    val page = getHomeContentUseCase.loadMore(activeScope, cursor, showingPublicFeed)
                    val (merged, changed) = withContext(Dispatchers.Default) {
                        val result = appendPage(current, page)
                        result to (result.shelves != current.shelves)
                    }
                    if (scope != activeScope || loadedFeed.value !== current) return@launch
                    consumedTokens += cursor.token
                    val next = merged.continuation?.takeUnless { it.token in consumedTokens }
                    loadedFeed.value = merged.copy(continuation = next)
                    if (changed || next == null) {
                        _manualPagination.value = false
                        return@launch
                    }
                }
                _manualPagination.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _manualPagination.value = true
                if (e is HomeSessionRejectedException) {
                    _accountNotice.value = AccountNotice(reconnect = true, showingPublicFeed = false)
                }
                if (userRequested) _error.value = context.getString(R.string.home_load_more_error)
                Log.w(TAG, "Unable to load the next Home page", e)
            } finally {
                if (scope == activeScope) _isLoadingMore.value = false
            }
        }
    }

    private fun appendPage(current: HomeContent, page: HomeContent): HomeContent {
        val shelves = current.shelves.associateByTo(linkedMapOf()) { it.id }
        for (shelf in page.shelves) {
            val oldShelf = shelves[shelf.id]
            if (oldShelf == null) {
                shelves[shelf.id] = shelf
            } else {
                val knownItems = oldShelf.items.mapTo(hashSetOf(), HomeShelfItem::key)
                val additions = shelf.items.filter { knownItems.add(it.key) }
                if (additions.isNotEmpty()) shelves[shelf.id] = oldShelf.copy(items = oldShelf.items + additions)
            }
        }
        return page.copy(shelves = shelves.values.toList())
    }

    companion object {
        private const val TAG = "SonicHome"
        private const val MAX_PAGES_PER_LOAD = 3
        const val SECTION_RECENTLY_PLAYED = "recently_played"
    }


    fun onSongClick(song: Song) {
        playerServiceConnection.playSongWithRadio(song)
    }

    /**
     * Play a song with YouTube Music Radio-style queue
     * When user clicks a song, it plays that song and generates fresh recommendations based on it.
     */
    fun onSongClickWithRadioQueue(song: Song) {
        playerServiceConnection.playSongWithRadio(song)
    }

    /**
     * Play All songs from a section - ViTune Style
     *
     * @param songs List of songs to play
     * @param shuffle Whether to shuffle before playing
     */
    fun playAllSongs(songs: List<Song>, shuffle: Boolean = false) {
        if (songs.isEmpty()) return

        viewModelScope.launch {
            try {
                // Prepare list
                val songsToPlay = if (shuffle) songs.shuffled() else songs

                // Mark all songs as queued
                songsToPlay.forEach { queueRepository.markSongAsQueued(it.id) }

                // Play lazy
                playerServiceConnection.playSongsLazy(
                    songs = songsToPlay,
                    startIndex = 0, // Always start at beginning for Play All
                    songRepository = songRepository
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error playing all songs", e)
                _error.value = "Failed to play: ${e.message}"
            }
        }
    }

    /**
     * Add a song to play next (YouTube Music style)
     */
    fun playNext(song: Song) {
        viewModelScope.launch {
            try {
                playerServiceConnection.insertPlayNextLazy(song, songRepository)
                Log.d(TAG, "Added play next: ${song.title}")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding song to play next", e)
            }
        }
    }

    /**
     * Add single song to queue
     */
    fun addToQueue(song: Song) {
        playerServiceConnection.addSongToQueue(song)
    }

    /**
     * Play a community playlist.
     * Loads the selected playlist's actual tracks, starts playback via Media3 ExoPlayer pipeline,
     * and updates queue.
     */
    fun playCommunityPlaylist(playlist: CommunityPlaylist, shuffle: Boolean = false) {
        viewModelScope.launch {
            try {
                val remoteId = playlist.browseId.ifBlank { playlist.id }
                val fetched = browseRepository.getPlaylistSongs(remoteId).getOrNull()
                val songsToUse = fetched?.takeIf { it.isNotEmpty() }
                    ?: playlist.previewTracks.takeIf { it.isNotEmpty() }

                if (songsToUse.isNullOrEmpty()) {
                    _error.value = "No playable tracks found in playlist"
                    return@launch
                }

                val songsToPlay = if (shuffle) songsToUse.shuffled() else songsToUse
                songsToPlay.forEach { queueRepository.markSongAsQueued(it.id) }
                playerServiceConnection.playSongsLazy(
                    songs = songsToPlay,
                    startIndex = 0,
                    songRepository = songRepository
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error playing community playlist", e)
                _error.value = "Failed to play: ${e.message}"
            }
        }
    }

    /**
     * Insert community playlist tracks to play next.
     */
    fun playCommunityPlaylistNext(playlist: CommunityPlaylist) {
        viewModelScope.launch {
            try {
                val remoteId = playlist.browseId.ifBlank { playlist.id }
                val fetched = browseRepository.getPlaylistSongs(remoteId).getOrNull()
                val songsToUse = fetched?.takeIf { it.isNotEmpty() }
                    ?: playlist.previewTracks.takeIf { it.isNotEmpty() }

                if (songsToUse.isNullOrEmpty()) {
                    _error.value = "No tracks available to play next"
                    return@launch
                }

                songsToUse.asReversed().forEach { song ->
                    playerServiceConnection.insertPlayNextLazy(song, songRepository)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding playlist to play next", e)
            }
        }
    }

    /**
     * Add community playlist tracks to the end of the queue.
     */
    fun addCommunityPlaylistToQueue(playlist: CommunityPlaylist) {
        viewModelScope.launch {
            try {
                val remoteId = playlist.browseId.ifBlank { playlist.id }
                val fetched = browseRepository.getPlaylistSongs(remoteId).getOrNull()
                val songsToUse = fetched?.takeIf { it.isNotEmpty() }
                    ?: playlist.previewTracks.takeIf { it.isNotEmpty() }

                if (songsToUse.isNullOrEmpty()) {
                    _error.value = "No tracks available to queue"
                    return@launch
                }

                songsToUse.forEach { song ->
                    playerServiceConnection.addSongToQueue(song)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding playlist to queue", e)
            }
        }
    }

    /**
     * Save community playlist to local library.
     */
    fun toggleSaveCommunityPlaylist(playlist: CommunityPlaylist) {
        viewModelScope.launch {
            try {
                val remoteId = playlist.browseId.ifBlank { playlist.id }
                val isSaved = remoteId in savedPlaylistRemoteIds.value
                if (isSaved) {
                    Log.d(TAG, "Playlist ${playlist.title} already saved in library")
                    return@launch
                }

                val fetchResult = playlistRepository.fetchRemotePlaylist(remoteId)
                fetchResult.onSuccess { (remotePlaylist, songs) ->
                    playlistRepository.saveRemotePlaylist(
                        remotePlaylist.copy(
                            name = playlist.title.ifBlank { remotePlaylist.name },
                            coverArtUrl = playlist.thumbnailUrl.ifBlank { remotePlaylist.coverArtUrl }
                        ),
                        songs
                    )
                    Log.d(TAG, "Saved community playlist ${playlist.title} with ${songs.size} songs")
                }.onFailure {
                    val domainPlaylist = com.sonicmusic.app.domain.model.Playlist(
                        id = 0,
                        name = playlist.title,
                        description = "Curated by ${playlist.creator}",
                        coverArtUrl = playlist.thumbnailUrl,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        songCount = playlist.previewTracks.size,
                        songs = playlist.previewTracks,
                        isLocal = false,
                        remoteId = remoteId
                    )
                    playlistRepository.saveRemotePlaylist(domainPlaylist, playlist.previewTracks)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save community playlist", e)
                _error.value = "Failed to save playlist: ${e.message}"
            }
        }
    }

    /**
     * Validate section before navigating to section detail.
     *
     * @return section key if it can be opened, otherwise null.
     */
    fun onSectionSeeAll(section: String): String? {
        if (section.isBlank()) {
            _error.value = "Section unavailable"
            return null
        }

        val items = getItemsForSection(section)
        if (items.isEmpty()) {
            _error.value = "Nothing is available in this section"
            return null
        }

        Log.d(TAG, "See all clicked for section: $section (${items.size} items)")
        return section
    }

    /**
     * Play all songs for a home section key.
     *
     * @return true if a section had songs and playback was started.
     */
    suspend fun playSection(section: String, shuffle: Boolean = false): Boolean {
        val sectionSongs = getSongsForSection(section)
        val songs = if (isOnline.value) sectionSongs else {
            val downloaded = readCompletedSongIds()
            sectionSongs.filter { it.id.deviceLocalSongId() != null || it.id in downloaded }
        }
        if (songs.isEmpty()) {
            _error.value = "No playable songs available in this section"
            return false
        }
        playAllSongs(songs, shuffle = shuffle)
        return true
    }

    suspend fun onSectionSongClick(song: Song): Boolean {
        if (!isOnline.value && song.id.deviceLocalSongId() == null && song.id !in readCompletedSongIds()) {
            _error.value = "This song is unavailable offline"
            return false
        }
        // Play only the clicked song + fresh radio recommendations (not the entire section)
        playerServiceConnection.playSongWithRadio(song)
        return true
    }

    private suspend fun readCompletedSongIds(): Set<String> {
        return try {
            completedSongIds.first().toHashSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to check completed downloads", e)
            emptySet()
        }
    }

    fun getItemsForSection(section: String): List<HomeShelfItem> =
        if (section == SECTION_RECENTLY_PLAYED) homeUiState.value.recentlyPlayed.map { HomeShelfItem.Media(it) }
        else homeUiState.value.content.shelves.firstOrNull { it.id == section }?.items.orEmpty()

    fun getSongsForSection(section: String): List<Song> =
        if (section == SECTION_RECENTLY_PLAYED) homeUiState.value.recentlyPlayed
        else homeUiState.value.content.shelves.firstOrNull { it.id == section }?.songs().orEmpty()

    fun getSectionTitle(section: String): String =
        if (section == SECTION_RECENTLY_PLAYED) {
            if (homeUiState.value.content.shelves.any { shelf ->
                shelf.kind == HomeShelf.Kind.ListenAgain && shelf.items.any { it.playableSong != null }
            }) {
                context.getString(R.string.home_recently_played)
            } else context.getString(R.string.home_listen_again)
        } else homeUiState.value.content.shelves.firstOrNull { it.id == section }?.title.orEmpty()


    fun clearError(message: String) {
        _error.compareAndSet(message, null)
    }

    fun retryHomeError(message: String) {
        if (message == context.getString(R.string.home_load_more_error)) {
            loadMoreHomeContent(userRequested = true)
        } else {
            loadHomeContent()
        }
    }

    fun clearRefreshResult() {
        _refreshResult.value = null
    }
}
