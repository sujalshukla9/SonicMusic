package com.sonicmusic.app.features.player.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.LyricLine
import com.sonicmusic.app.theme.MotionTokens
import com.sonicmusic.app.theme.pressable
import com.sonicmusic.app.theme.shouldReduceMotion
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsOverlay(
    lyricsState: LyricsUiState,
    visible: Boolean,
    onDismiss: () -> Unit,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val reduceMotion = shouldReduceMotion()
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) EnterTransition.None else {
            fadeIn(MotionTokens.effects()) + scaleIn(MotionTokens.spatial(), initialScale = 0.97f)
        },
        exit = if (reduceMotion) ExitTransition.None else fadeOut(MotionTokens.quickEffects()),
        modifier = modifier
    ) {
        val colors = MaterialTheme.colorScheme
        val showOriginal by viewModel.showOriginalScript.collectAsStateWithLifecycle()
        val hasOriginal = when (lyricsState) {
            is LyricsUiState.LoadedSynced -> lyricsState.lines.any { it.originalText != null }
            is LyricsUiState.LoadedPlain -> lyricsState.originalText != null
            else -> false
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(Brush.verticalGradient(listOf(colors.surfaceContainer, colors.surfaceContainerHigh)))
                .semantics { paneTitle = "Lyrics" }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Lyrics",
                    modifier = Modifier.weight(1f).padding(start = 6.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold
                )
                if (hasOriginal) {
                    FilterChip(
                        selected = showOriginal,
                        onClick = viewModel::toggleLyricsLanguage,
                        label = { Text("Original") },
                        leadingIcon = { Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close lyrics")
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (lyricsState) {
                    is LyricsUiState.LoadedSynced -> key(lyricsState.lines) {
                        SyncedLyrics(
                            state = lyricsState,
                            positionFlow = viewModel.lyricsPosition,
                            initialPosition = viewModel.currentPosition.value,
                            original = showOriginal,
                            onSeek = viewModel::seekToPosition,
                            reducedMotion = reduceMotion
                        )
                    }
                    is LyricsUiState.LoadedPlain -> {
                        val text = if (showOriginal) lyricsState.originalText ?: lyricsState.text else lyricsState.text
                        val paragraphs = remember(text) { text.lines() }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 24.dp, top = 14.dp, end = 24.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(paragraphs.size) { index ->
                                val line = paragraphs[index]
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = colors.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            lyricsState.source?.takeIf(String::isNotBlank)?.let { source ->
                                item { LyricsSource(source) }
                            }
                        }
                    }
                    LyricsUiState.Idle, LyricsUiState.Loading -> LoadingIndicator(modifier = Modifier.size(42.dp))
                    LyricsUiState.Unavailable -> Text("Lyrics aren’t available for this track", modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
                    is LyricsUiState.Error -> Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Couldn’t load lyrics", textAlign = TextAlign.Center)
                        FilledTonalButton(onClick = viewModel::fetchLyrics) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SyncedLyrics(
    state: LyricsUiState.LoadedSynced,
    positionFlow: Flow<Long>,
    initialPosition: Long,
    original: Boolean,
    onSeek: (Long) -> Unit,
    reducedMotion: Boolean
) {
    val lines = remember(state.lines) { state.lines.sortedBy { it.timeMs } }
    val position = positionFlow.collectAsStateWithLifecycle(initialValue = initialPosition)
    val active by remember(lines, position) {
        derivedStateOf { activeLyricIndex(lines, position.value) }
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = active.coerceAtLeast(0))
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var following by remember(lines) { mutableStateOf(true) }

    LaunchedEffect(dragging) { if (dragging) following = false }
    LaunchedEffect(active, following, dragging, reducedMotion) {
        if (following && !dragging && active >= 0) {
            if (reducedMotion || kotlin.math.abs(list.firstVisibleItemIndex - active) > 5) list.scrollToItem(active)
            else list.animateScrollToItem(active)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val readingInset = maxHeight * 0.23f
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = readingInset, bottom = maxHeight - readingInset),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(lines) { index, line ->
                SyncedLyricRow(
                    text = if (original) line.originalText ?: line.text else line.text,
                    active = index == active,
                    reducedMotion = reducedMotion,
                    onClick = {
                        onSeek(line.timeMs)
                        following = true
                    }
                )
            }
            state.source?.takeIf(String::isNotBlank)?.let { source -> item { LyricsSource(source) } }
        }
        if (!following) {
            FilledTonalButton(
                onClick = { following = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
            ) { Text("Resume sync") }
        }
    }
}

internal fun activeLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var first = 0
    var end = lines.size
    while (first < end) {
        val middle = (first + end) ushr 1
        if (lines[middle].timeMs <= positionMs) first = middle + 1 else end = middle
    }
    return first - 1
}

@Composable
private fun SyncedLyricRow(text: String, active: Boolean, reducedMotion: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val emphasis by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else MotionTokens.effects(),
        label = "lyric_emphasis"
    )
    val container by animateColorAsState(
        targetValue = if (active) colors.primaryContainer else colors.primaryContainer.copy(alpha = 0f),
        animationSpec = if (reducedMotion) snap() else MotionTokens.effects(),
        label = "lyric_container"
    )
    val foreground by animateColorAsState(
        targetValue = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
        animationSpec = if (reducedMotion) snap() else MotionTokens.effects(),
        label = "lyric_text"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .semantics { selected = active }
            .pressable(onClick = onClick, targetScale = 0.985f)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = text,
            color = foreground,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0.5f)
                scaleX = 0.96f + emphasis * 0.04f
                scaleY = scaleX
            }
        )
    }
}

@Composable
private fun LyricsSource(source: String) {
    Text(
        text = source,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}
