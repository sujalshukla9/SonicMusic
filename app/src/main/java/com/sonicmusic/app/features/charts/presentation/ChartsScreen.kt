package com.sonicmusic.app.features.charts.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MicExternalOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.R
import com.sonicmusic.app.domain.model.ChartItem
import com.sonicmusic.app.domain.model.ChartKind
import com.sonicmusic.app.domain.model.ChartOrigin
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.features.player.presentation.sheets.SongActionSource
import com.sonicmusic.app.shared.components.SongListSkeleton
import com.sonicmusic.app.shared.components.SongThumbnail
import com.sonicmusic.app.shared.components.songCombinedClickable
import com.sonicmusic.app.theme.Dimens
import com.sonicmusic.app.theme.pressable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartsScreen(
    kind: ChartKind,
    onNavigateBack: () -> Unit,
    bottomPadding: Dp = 0.dp,
    onPlayChart: (List<Song>, Int) -> Unit = { _, _ -> },
    onOpen: (Song) -> Unit = {},
    viewModel: ChartsViewModel = hiltViewModel()
) {
    val states = remember(viewModel, kind) { viewModel.observe(kind) }
    val state by states.collectAsStateWithLifecycle()
    val items = state.page?.items.orEmpty()
    val hasMore = state.page?.hasMore == true
    val isSongs = kind == ChartKind.Songs
    val chartQueue = remember(items, isSongs) { if (isSongs) items.map(ChartItem::media) else emptyList() }
    val title = stringResource(kind.titleResource())
    val (container, onContainer, icon) = when (kind) {
        ChartKind.Songs -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Icons.Rounded.MusicNote)
        ChartKind.Artists -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Icons.Rounded.MicExternalOn)
        ChartKind.Albums -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Icons.Rounded.Album)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chart_back))
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(
                            onClick = viewModel::refresh,
                            enabled = !state.loading && state.issue != ChartIssue.Offline
                        ) {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.chart_refresh))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())
        when {
            items.isEmpty() && !hasMore && state.loading -> SongListSkeleton(
                modifier = content,
                contentPadding = PaddingValues(horizontal = Dimens.screenPadding, vertical = 12.dp)
            )
            items.isEmpty() && !hasMore -> ChartsMessage(
                text = stringResource(when (state.issue) {
                    ChartIssue.Offline -> R.string.chart_offline
                    ChartIssue.RequestFailed -> R.string.chart_load_error
                    null -> R.string.chart_unavailable
                }),
                icon = icon,
                onRetry = viewModel::refresh,
                canRetry = state.issue != ChartIssue.Offline,
                modifier = content.padding(bottom = bottomPadding)
            )
            else -> LazyVerticalGrid(
                columns = if (isSongs) GridCells.Fixed(1) else GridCells.Adaptive(132.dp),
                modifier = content,
                contentPadding = PaddingValues(
                    start = Dimens.screenPadding,
                    end = Dimens.screenPadding,
                    top = 8.dp,
                    bottom = bottomPadding + 24.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(if (isSongs) 6.dp else 12.dp)
            ) {
                item(key = "chart_summary", span = { GridItemSpan(maxLineSpan) }) {
                    val page = state.page
                    val subtitle = when (page?.origin) {
                        ChartOrigin.MusicVideos -> page.sourceTitle ?: stringResource(R.string.chart_music_videos)
                        ChartOrigin.NewReleases -> stringResource(R.string.chart_new_releases)
                        else -> page?.sourceTitle
                    }
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = container,
                        contentColor = onContainer
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                state.region.ifBlank { title },
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                            if (state.issue != null) {
                                Text(
                                    stringResource(if (state.issue == ChartIssue.Offline) R.string.chart_cached_offline else R.string.chart_cached_error),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (chartQueue.isNotEmpty()) {
                                FilledTonalButton(
                                    onClick = { onPlayChart(chartQueue, 0) },
                                    contentPadding = PaddingValues(horizontal = 16.dp)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(20.dp))
                                    Text(stringResource(R.string.chart_play), modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
                itemsIndexed(items, key = { _, item -> item.media.id }, contentType = { _, _ -> kind }) { index, item ->
                    if (isSongs) {
                        ChartSongRow(item) { onPlayChart(chartQueue, index) }
                    } else {
                        ChartArtworkCard(item, artist = kind == ChartKind.Artists) { onOpen(item.media) }
                    }
                }
                if (hasMore) {
                    item(key = "chart_load_more", span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(
                            onClick = viewModel::loadMore,
                            enabled = !state.loading && state.issue != ChartIssue.Offline,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.home_load_more)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartSongRow(item: ChartItem, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .songCombinedClickable(item.media, SongActionSource.SEARCH, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item.rank?.let {
                Text(
                    it.toString(),
                    modifier = Modifier.widthIn(min = 22.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            SongThumbnail(
                artworkUrl = item.media.thumbnailUrl,
                modifier = Modifier.size(54.dp),
                shape = ThumbnailShape.ROUNDED_SQUARE,
                targetSizePx = 180
            )
            Column(Modifier.weight(1f)) {
                Text(item.media.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.media.artist.takeIf(String::isNotBlank)?.let { artist ->
                    Text(artist, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChartArtworkCard(item: ChartItem, artist: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).pressable(onClick, role = androidx.compose.ui.semantics.Role.Button),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                SongThumbnail(
                    artworkUrl = item.media.thumbnailUrl,
                    modifier = Modifier.fillMaxSize(),
                    shape = if (artist) ThumbnailShape.CIRCLE else ThumbnailShape.ROUNDED_SQUARE,
                    targetSizePx = 420
                )
                item.rank?.let { rank ->
                    Text(
                        text = rank.toString(),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.TopStart)
                            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            val subtitle = item.media.artist.takeIf { it.isNotBlank() && it != item.media.title }
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(item.media.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ChartsMessage(
    text: String,
    icon: ImageVector,
    onRetry: () -> Unit,
    canRetry: Boolean,
    modifier: Modifier
) {
    Box(modifier = modifier.padding(horizontal = 26.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
            Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (canRetry) Button(onClick = onRetry) { Text(stringResource(R.string.chart_retry)) }
        }
    }
}

internal fun ChartKind.titleResource(): Int = when (this) {
    ChartKind.Songs -> R.string.chart_top_songs
    ChartKind.Artists -> R.string.chart_top_artists
    ChartKind.Albums -> R.string.chart_top_albums
}

