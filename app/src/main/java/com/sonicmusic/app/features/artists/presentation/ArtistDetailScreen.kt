package com.sonicmusic.app.features.artists.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.theme.MotionTokens
import com.sonicmusic.app.theme.pressable
import com.sonicmusic.app.theme.shouldReduceMotion
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.sonicmusic.app.domain.model.ArtistProfileSectionType
import com.sonicmusic.app.domain.model.PlaybackHistory
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.shared.components.SongThumbnail
import com.sonicmusic.app.features.player.presentation.sheets.SongActionSource
import com.sonicmusic.app.shared.components.songPressable
import com.sonicmusic.app.shared.components.songCombinedClickable

private enum class ArtistTab(val label: String, val section: ArtistProfileSectionType? = null) {
    Overview("Overview"),
    Songs("Songs", ArtistProfileSectionType.TopSongs),
    Albums("Albums", ArtistProfileSectionType.Albums),
    Singles("Singles", ArtistProfileSectionType.Singles),
    Videos("Videos", ArtistProfileSectionType.Videos),
    Featured("Featured on", ArtistProfileSectionType.FeaturedOn),
    Related("Related artists", ArtistProfileSectionType.RelatedArtists),
    Library("Library")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    browseId: String? = null,
    onNavigateBack: () -> Unit,
    onShowFullPlayer: () -> Unit = {},
    onNavigateToArtist: (String, String?) -> Unit = { _, _ -> },
    onNavigateToAlbum: (String, String, String, String?) -> Unit = { _, _, _, _ -> },
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val history by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
    val followed by viewModel.isFollowed.collectAsStateWithLifecycle()
    val loading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val loadingSections by viewModel.loadingMoreSections.collectAsStateWithLifecycle()
    val failedSections by viewModel.failedSections.collectAsStateWithLifecycle()

    val tabs = ArtistTab.entries
    val pager = rememberPagerState(pageCount = { tabs.size })
    val tabList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduceMotion = shouldReduceMotion()

    fun openTab(tab: ArtistTab) {
        scope.launch {
            if (reduceMotion) pager.scrollToPage(tab.ordinal)
            else pager.animateScrollToPage(tab.ordinal, animationSpec = MotionTokens.standardTween())
        }
    }
    fun openRelease(song: Song) {
        val id = if (song.contentType == ContentType.PLAYLIST) song.id else {
            song.albumBrowseId?.takeIf(String::isNotBlank) ?: song.albumId?.takeIf(String::isNotBlank) ?: song.id
        }
        onNavigateToAlbum(id, song.title, song.artist, song.thumbnailUrl)
    }
    fun playSong(song: Song) {
        viewModel.playSong(song)
        onShowFullPlayer()
    }
    fun playHistory(entry: PlaybackHistory) {
        viewModel.playHistorySong(entry)
        onShowFullPlayer()
    }

    LaunchedEffect(artistName, browseId) { viewModel.loadArtist(artistName, browseId) }
    LaunchedEffect(pager.settledPage, loading, profile != null, profile?.browseId) {
        if (!loading && profile != null) tabs[pager.settledPage].section?.let { viewModel.loadMoreSection(it) }
    }
    LaunchedEffect(pager.currentPage, reduceMotion) {
        if (tabList.layoutInfo.visibleItemsInfo.none { it.index == pager.currentPage }) {
            if (reduceMotion) tabList.scrollToItem(pager.currentPage)
            else tabList.animateScrollToItem(pager.currentPage)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(profile?.name ?: artistName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                state = tabList,
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tabs, key = { it.name }) { tab ->
                    val selected = pager.currentPage == tab.ordinal
                    val container by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                        animationSpec = if (reduceMotion) snap() else MotionTokens.fastTween(),
                        label = "artist_tab_color"
                    )
                    Surface(
                        color = container,
                        shape = CircleShape,
                        modifier = Modifier.clip(CircleShape).selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { openTab(tab) }
                        )
                    ) {
                        Text(
                            text = tab.label,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                val tab = tabs[page]
                val current = profile
                val pageModifier = Modifier.fillMaxSize().graphicsLayer {
                    if (!reduceMotion) {
                        val offset = kotlin.math.abs(pager.currentPage - page + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                        alpha = 1f - offset * 0.25f
                        scaleX = 1f - offset * 0.025f
                        scaleY = scaleX
                    } else {
                        alpha = 1f
                        scaleX = 1f
                        scaleY = 1f
                    }
                }
                val section = tab.section
                val pending = (loading && current == null) || (section != null && section in loadingSections)
                val sectionFailed = section != null && section in failedSections
                val loadError = if (sectionFailed) "Couldn't load all " + tab.label.lowercase() else error
                val retry: () -> Unit = if (sectionFailed) {
                    { section?.let { viewModel.loadMoreSection(it, retry = true) } }
                } else viewModel::retryArtist

                val bottom = bottomPadding + 24.dp
                when (tab) {
                    ArtistTab.Overview -> LazyColumn(
                        modifier = pageModifier,
                        contentPadding = PaddingValues(bottom = bottom),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item(key = "hero") {
                            ArtistHeroHeader(
                                artistName = current?.name ?: artistName,
                                imageUrl = current?.imageUrl,
                                bannerUrl = current?.bannerUrl,
                                subscribersText = current?.subscribersText,
                                songCount = current?.topSongs?.size ?: 0,
                                onPlayAll = { if (viewModel.playAll()) onShowFullPlayer() },
                                onShuffle = { if (viewModel.playAll(shuffle = true)) onShowFullPlayer() },
                                onRadio = { if (viewModel.startRadio()) onShowFullPlayer() },
                                hasRadio = !current?.topSongs.isNullOrEmpty() || !current?.videos.isNullOrEmpty(),
                                isFollowed = followed,
                                onToggleFollow = viewModel::toggleFollow,
                                hasSongs = !current?.topSongs.isNullOrEmpty() || history.isNotEmpty()
                            )
                        }
                        if (loading || error != null) item(key = "overview_status") {
                            ArtistTabStatus(loading, error, "", viewModel::retryArtist)
                        }
                        val topSongs = current?.topSongs.orEmpty()
                        if (topSongs.isNotEmpty()) {
                            item(key = "top_songs") {
                                SectionHeader("Songs", onSeeAllClick = { openTab(ArtistTab.Songs) },
                                    modifier = Modifier.padding(horizontal = 20.dp))
                            }
                            itemsIndexed(topSongs.take(5), key = { _, song -> "song_" + song.id }) { index, song ->
                                ArtistTopSongItem(song, index + 1, onClick = { playSong(song) })
                            }
                        }
                        for (releaseTab in listOf(ArtistTab.Albums, ArtistTab.Singles)) {
                            val releases = if (releaseTab == ArtistTab.Albums) current?.albums.orEmpty()
                                else current?.singles.orEmpty()
                            if (releases.isNotEmpty()) item(key = releaseTab.name) {
                                ArtistShelf(releaseTab.label, releases, { openTab(releaseTab) }) {
                                    AlbumCard(it, onClick = { openRelease(it) })
                                }
                            }
                        }
                        val videos = current?.videos.orEmpty()
                        if (videos.isNotEmpty()) item(key = "videos") {
                            ArtistShelf("Videos", videos, { openTab(ArtistTab.Videos) }) {
                                VideoCard(it, onClick = { playSong(it) })
                            }
                        }
                        val playlists = current?.featuredOn.orEmpty()
                        if (playlists.isNotEmpty()) item(key = "featured") {
                            ArtistShelf("Featured on", playlists, { openTab(ArtistTab.Featured) }) {
                                AlbumCard(it, onClick = { openRelease(it) })
                            }
                        }
                        val related = current?.relatedArtists.orEmpty()
                        if (related.isNotEmpty()) item(key = "related") {
                            ArtistShelf("Related artists", related, { openTab(ArtistTab.Related) }) { artist ->
                                RelatedArtistChip(artist, onClick = { onNavigateToArtist(artist.title, artist.id) })
                            }
                        }
                        current?.description?.takeIf(String::isNotBlank)?.let { description ->
                            item(key = "bio") { ArtistBioSection(description) }
                        }
                        if (history.isNotEmpty()) {
                            item(key = "history") {
                                SectionHeader("Recently Played", onSeeAllClick = { openTab(ArtistTab.Library) },
                                    modifier = Modifier.padding(horizontal = 20.dp))
                            }
                            items(history.take(3), key = { "history_" + it.id }) {
                                RecentlyPlayedItem(it, onClick = { playHistory(it) })
                            }
                        }
                    }

                    ArtistTab.Songs, ArtistTab.Library -> LazyColumn(
                        modifier = pageModifier,
                        contentPadding = PaddingValues(bottom = bottom)
                    ) {
                        val songs = if (tab == ArtistTab.Songs) current?.topSongs.orEmpty() else emptyList()
                        if (songs.isNotEmpty()) {
                            itemsIndexed(songs, key = { _, song -> song.id }, contentType = { _, _ -> "song" }) { index, song ->
                                ArtistTopSongItem(song, index + 1, onClick = { playSong(song) })
                            }
                        } else if (history.isNotEmpty() && tab == ArtistTab.Library) {
                            item(key = "history_header") {
                                SectionHeader("Recently Played", count = history.size,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                            }
                            items(history, key = { "history_" + it.id }) {
                                RecentlyPlayedItem(it, onClick = { playHistory(it) })
                            }
                        }
                        if (tab == ArtistTab.Songs && (pending || loadError != null || songs.isEmpty())) {
                            item(key = "songs_status") { ArtistTabStatus(pending, loadError, "No songs available", retry) }
                        } else if (tab == ArtistTab.Library && history.isEmpty()) {
                            item(key = "library_empty") {
                                ArtistTabStatus(false, null, "No listening history for this artist", {})
                            }
                        }
                    }

                    ArtistTab.Albums, ArtistTab.Singles, ArtistTab.Featured -> {
                        val releases = when (tab) {
                            ArtistTab.Albums -> current?.albums.orEmpty()
                            ArtistTab.Singles -> current?.singles.orEmpty()
                            else -> current?.featuredOn.orEmpty()
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(140.dp),
                            modifier = pageModifier,
                            contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = bottom),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            gridItems(releases, key = { it.id }, contentType = { "release" }) { release ->
                                AlbumCard(release, modifier = Modifier.fillMaxWidth(), onClick = { openRelease(release) })
                            }
                            if (pending || loadError != null || releases.isEmpty()) {
                                item(key = "release_status", span = { GridItemSpan(maxLineSpan) }) {
                                    ArtistTabStatus(pending, loadError, "No " + tab.label.lowercase() + " available", retry)
                                }
                            }
                        }
                    }

                    ArtistTab.Related -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        modifier = pageModifier,
                        contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = bottom),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val related = current?.relatedArtists.orEmpty()
                        gridItems(related, key = { it.id }, contentType = { "artist" }) { artist ->
                            RelatedArtistChip(artist, onClick = { onNavigateToArtist(artist.title, artist.id) })
                        }
                        if (pending || loadError != null || related.isEmpty()) {
                            item(key = "related_status", span = { GridItemSpan(maxLineSpan) }) {
                                ArtistTabStatus(pending, loadError, "No related artists available", retry)
                            }
                        }
                    }

                    ArtistTab.Videos -> {
                        val videos = current?.videos.orEmpty()
                        LazyColumn(
                            modifier = pageModifier,
                            contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = bottom),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            items(videos, key = { it.id }, contentType = { "video" }) { video ->
                                VideoCard(video, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(), onClick = { playSong(video) })
                            }
                            if (pending || loadError != null || videos.isEmpty()) item(key = "video_status") {
                                ArtistTabStatus(pending, loadError, "No videos available", retry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistShelf(
    title: String,
    songs: List<Song>,
    onSeeAll: (() -> Unit)? = null,
    card: @Composable (Song) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(title, onSeeAllClick = onSeeAll, modifier = Modifier.padding(horizontal = 20.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(songs, key = { it.id }) { card(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArtistTabStatus(loading: Boolean, error: String?, emptyMessage: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (loading) {
            LoadingIndicator(modifier = Modifier.size(42.dp))
        } else {
            Text(error ?: emptyMessage, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int? = null,
    onSeeAllClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (onSeeAllClick != null) {
            TextButton(onClick = onSeeAllClick) { Text("See all") }
        } else if (count != null && count > 0) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Hero header with large artist image, name, subscribers, follow button, and action buttons
 */
@Composable
private fun ArtistHeroHeader(
    artistName: String,
    imageUrl: String?,
    bannerUrl: String?,
    subscribersText: String?,
    songCount: Int,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onRadio: () -> Unit,
    hasRadio: Boolean,
    isFollowed: Boolean,
    onToggleFollow: () -> Unit,
    hasSongs: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        if (bannerUrl != null) {
            SongThumbnail(
                artworkUrl = bannerUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                highQuality = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                colorScheme.background
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = if (bannerUrl != null) 32.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Artist image — large circular
            Surface(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape),
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            if (imageUrl != null) {
                SongThumbnail(
                    artworkUrl = imageUrl,
                    contentDescription = artistName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    highQuality = true
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Artist name
        Text(
            text = artistName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Subscriber count (from YTMusic)
        if (!subscribersText.isNullOrBlank()) {
            Text(
                text = subscribersText,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Song count
        if (songCount > 0) {
            Text(
                text = "$songCount songs",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Follow Button
        if (isFollowed) {
            FilledTonalButton(
                onClick = onToggleFollow,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colorScheme.surfaceVariant,
                    contentColor = colorScheme.onSurfaceVariant
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Following", fontWeight = FontWeight.Medium)
            }
        } else {
            OutlinedButton(
                onClick = onToggleFollow,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, colorScheme.outline
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colorScheme.onSurface
                )
            ) {
                Text("Follow", fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onPlayAll,
                enabled = hasSongs,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play All", fontWeight = FontWeight.SemiBold)
            }

            FilledTonalButton(
                onClick = onShuffle,
                enabled = hasSongs,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Shuffle", fontWeight = FontWeight.SemiBold)
            }
        }

        if (hasRadio) {
            TextButton(onClick = onRadio, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Start radio")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    }
}

/**
 * Top song item — numbered track with thumbnail, title, artist, duration, and play count
 */
@Composable
private fun ArtistTopSongItem(
    song: Song,
    trackNumber: Int,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .songCombinedClickable(
                song = song,
                source = SongActionSource.ARTIST,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track number
        Text(
            text = "$trackNumber",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Center
        )

        // Thumbnail
        SongThumbnail(
            artworkUrl = song.thumbnailUrl,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Title + Artist + View count
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (song.viewCount != null && song.viewCount > 0) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = formatViewCount(song.viewCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Duration
        if (song.duration > 0) {
            Text(
                text = song.formattedDuration(),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * Album / Single card for horizontal scroll — artwork + title + subtitle
 */
@Composable
private fun AlbumCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(140.dp)
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .pressable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            if (song.thumbnailUrl.isNotBlank()) {
                SongThumbnail(
                    artworkUrl = song.thumbnailUrl,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val subtitle = listOfNotNull(
            song.artist.takeIf { it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true) },
            song.year?.toString()
        ).joinToString(" • ")
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Video card — wider 16:9 aspect ratio
 */
@Composable
private fun VideoCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(200.dp)
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .songPressable(
                song = song,
                source = SongActionSource.ARTIST,
                onClick = onClick
            )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = RoundedCornerShape(12.dp),
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            if (song.thumbnailUrl.isNotBlank()) {
                SongThumbnail(
                    artworkUrl = song.thumbnailUrl,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VideoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (song.viewCount != null && song.viewCount > 0) {
            Text(
                text = formatViewCount(song.viewCount),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Related artist chip — circular avatar + name
 */
@Composable
private fun RelatedArtistChip(
    song: Song,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val displayName = song.title.ifBlank { song.artist }
    val subtitle = song.artist.takeIf {
        it.isNotBlank() && !it.equals(displayName, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .width(90.dp)
            .pressable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape),
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            if (song.thumbnailUrl.isNotBlank()) {
                SongThumbnail(
                    artworkUrl = song.thumbnailUrl,
                    contentDescription = displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Recently played item — simpler row from local history
 */
@Composable
private fun RecentlyPlayedItem(
    history: PlaybackHistory,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = history.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = history.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = history.artist,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── Utility ────────────────────────────────────────────────────────

private fun formatViewCount(count: Long): String {
    return when {
        count >= 1_000_000_000 -> String.format("%.1fB plays", count / 1_000_000_000.0)
        count >= 1_000_000 -> String.format("%.1fM plays", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK plays", count / 1_000.0)
        else -> "$count plays"
    }
}

/**
 * Collapsible bio section for the artist description
 */
@Composable
private fun ArtistBioSection(
    description: String,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "About",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (isExpanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}
