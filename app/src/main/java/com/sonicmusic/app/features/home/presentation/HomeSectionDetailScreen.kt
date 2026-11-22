package com.sonicmusic.app.features.home.presentation

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.R
import com.sonicmusic.app.domain.model.CommunityPlaylist
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.HomeShelfItem
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.features.player.presentation.sheets.SongActionSource
import com.sonicmusic.app.shared.components.ArtworkCardContent
import com.sonicmusic.app.shared.components.ExpressiveNotice
import com.sonicmusic.app.shared.components.ExpressiveSnackbarHost
import com.sonicmusic.app.shared.components.ReleaseAlbumCard
import com.sonicmusic.app.shared.components.SongListSkeleton
import com.sonicmusic.app.shared.components.SongThumbnail
import com.sonicmusic.app.shared.components.songPressable
import com.sonicmusic.app.theme.Dimens
import com.sonicmusic.app.theme.pressScale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSectionDetailScreen(
    sectionKey: String,
    onNavigateBack: () -> Unit,
    onShowFullPlayer: () -> Unit = {},
    onOpenAlbum: (Song) -> Unit,
    onOpenArtist: (String, String?) -> Unit,
    onOpenPlaylist: (CommunityPlaylist) -> Unit,
    bottomPadding: Dp = 0.dp,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val home by viewModel.homeUiState.collectAsStateWithLifecycle()
    val actionScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val gridState = rememberLazyGridState()
    val showToolbarTitle by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 } }
    ShowHomeErrors(viewModel, snackbarHostState)

    val items = remember(home.content, home.recentlyPlayed, sectionKey) {
        viewModel.getItemsForSection(sectionKey)
    }
    val trackCount = remember(items) { items.count { it.playableSong != null } }
    val sectionTitle = viewModel.getSectionTitle(sectionKey).ifBlank { stringResource(R.string.home_section_title) }
    val subtitle = remember(home.content, sectionKey) {
        home.content.shelves.firstOrNull { it.id == sectionKey }?.subtitle
    }
    val screenPadding = Dimens.screenPadding
    val maxWidth = Dimens.maxContentWidth

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = Color.Transparent,
        snackbarHost = { ExpressiveSnackbarHost(snackbarHostState, Modifier.padding(bottom = bottomPadding)) },
        topBar = {
            TopAppBar(
                title = {
                    if (showToolbarTitle || items.isEmpty()) {
                        Text(sectionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.home_section_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { paddingValues ->
        when {
            home.isLoading && items.isEmpty() -> {
                SongListSkeleton(
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding(),
                        bottom = bottomPadding + 20.dp
                    )
                )
            }

            items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = screenPadding),
                    contentAlignment = Alignment.Center
                ) {
                    ExpressiveNotice(
                        icon = Icons.Rounded.LibraryMusic,
                        title = stringResource(R.string.home_section_empty),
                        message = stringResource(R.string.home_section_empty_message),
                        modifier = Modifier.widthIn(max = 480.dp)
                    )
                }
            }

            else -> Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.TopCenter) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(144.dp),
                    state = gridState,
                    modifier = Modifier.widthIn(max = maxWidth).fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = screenPadding,
                        end = screenPadding,
                        top = 8.dp,
                        bottom = bottomPadding + 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "section_summary", span = { GridItemSpan(maxLineSpan) }) {
                        HomeSectionSummary(
                            title = sectionTitle,
                            subtitle = subtitle,
                            count = if (trackCount == items.size) {
                                pluralStringResource(R.plurals.home_section_track_count, trackCount, trackCount)
                            } else {
                                pluralStringResource(R.plurals.home_section_item_count, items.size, items.size)
                            },
                            hasTracks = trackCount > 0,
                            onPlay = { shuffle ->
                                actionScope.launch {
                                    if (viewModel.playSection(sectionKey, shuffle)) onShowFullPlayer()
                                }
                            }
                        )
                    }

                    items(
                        items = items,
                        key = { it.key },
                        span = { item -> GridItemSpan(if (item.playableSong != null) maxLineSpan else 1) },
                        contentType = { item -> (item as? HomeShelfItem.Media)?.song?.contentType ?: ContentType.PLAYLIST }
                    ) { item ->
                        val openItem: () -> Unit = {
                            when (item) {
                                is HomeShelfItem.Playlist -> onOpenPlaylist(item.playlist)
                                is HomeShelfItem.Media -> when (item.song.contentType) {
                                    ContentType.ALBUM -> onOpenAlbum(item.song)
                                    ContentType.ARTIST -> onOpenArtist(item.song.title, item.song.artistId)
                                    else -> actionScope.launch {
                                        if (viewModel.onSectionSongClick(item.song)) onShowFullPlayer()
                                    }
                                }
                            }
                        }
                        val song = item.playableSong
                        if (song != null) {
                            SectionSongRow(song, openItem)
                        } else if (item is HomeShelfItem.Media && item.song.contentType == ContentType.ALBUM) {
                            ReleaseAlbumCard(item.song, openItem, artworkCrossfade = false)
                        } else {
                            SectionArtworkCard(item, openItem)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeSectionSummary(
    title: String,
    subtitle: String?,
    count: String,
    hasTracks: Boolean,
    onPlay: (shuffle: Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            Text(count, style = MaterialTheme.typography.labelLarge)
            if (hasTracks) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val playInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = { onPlay(false) },
                        interactionSource = playInteraction,
                        modifier = Modifier.pressScale(playInteraction)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_section_play_all))
                    }
                    val shuffleInteraction = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = { onPlay(true) },
                        interactionSource = shuffleInteraction,
                        modifier = Modifier.pressScale(shuffleInteraction)
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_section_shuffle))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionSongRow(song: Song, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(shape).songPressable(song, SongActionSource.RECOMMENDATION, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongThumbnail(
                artworkUrl = song.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(MaterialTheme.shapes.medium),
                crossfade = false,
                targetSizePx = with(LocalDensity.current) { 52.dp.roundToPx() }
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (song.artist.isNotBlank()) {
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (song.duration > 0) {
                Text(song.formattedDuration(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionArtworkCard(item: HomeShelfItem, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().pressScale(interaction)
    ) {
        ArtworkCardContent(item.artwork, item.title, item.subtitle, artworkCrossfade = false, targetSizePx = 440)
    }
}
