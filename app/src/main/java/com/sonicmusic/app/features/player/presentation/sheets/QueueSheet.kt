package com.sonicmusic.app.features.player.presentation.sheets

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.shared.components.SongThumbnail
import com.sonicmusic.app.theme.LocalThumbnailShape
import com.sonicmusic.app.theme.MotionTokens
import com.sonicmusic.app.theme.shouldReduceMotion
import com.sonicmusic.app.theme.toComposeShape
import com.sonicmusic.app.shared.components.songPressable
import com.sonicmusic.app.shared.components.songCombinedClickable
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private data class QueueItemEntry(
    val stableId: String,
    val song: Song
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    onDismiss: () -> Unit,
    queue: List<Song>,
    currentIndex: Int,
    onRemove: (Int) -> Unit,
    onReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
    onClearQueue: () -> Unit,
    onPlay: (Int) -> Unit,
    infiniteModeEnabled: Boolean,
    onToggleInfiniteMode: (Boolean) -> Unit,
    shuffleEnabled: Boolean,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier
) {

    var showHistory by remember { mutableStateOf(false) }
    val safeIndex = currentIndex.takeIf { it in queue.indices } ?: -1
    val nowPlayingSong = queue.getOrNull(safeIndex)
    val upNextSongs = if (queue.size > safeIndex + 1) queue.subList(safeIndex + 1, queue.size) else emptyList()
    val historySongs = if (safeIndex > 0) queue.subList(0, safeIndex) else emptyList()
    val upNextEntries = remember(upNextSongs) {
        val occurrences = mutableMapOf<String, Int>()
        upNextSongs.map { song ->
            val occurrence = (occurrences[song.id] ?: 0) + 1
            occurrences[song.id] = occurrence
            QueueItemEntry("up_${song.id}_$occurrence", song)
        }
    }

    val haptic = LocalHapticFeedback.current

    // Local mutable copy of the up-next list with stable IDs so drag-and-drop reflects instantly without key collisions
    var localUpNext by remember { mutableStateOf(upNextEntries) }
    var dragSnapshot by remember { mutableStateOf<Pair<List<Song>, Int>?>(null) }
    val reduceMotion = shouldReduceMotion()

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromRelative = localUpNext.indexOfFirst { it.stableId == from.key }
        val toRelative = localUpNext.indexOfFirst { it.stableId == to.key }
        if (fromRelative < 0 || toRelative < 0 || fromRelative == toRelative) {
            return@rememberReorderableLazyListState
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

        // Update local list instantly for visual feedback (no player IPC here)
        localUpNext = localUpNext.toMutableList().apply {
            add(toRelative, removeAt(fromRelative))
        }
    }

    LaunchedEffect(upNextEntries) {
        if (dragSnapshot == null) localUpNext = upNextEntries
    }

    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxSize().semantics { paneTitle = "Queue" },
        color = colorScheme.surfaceContainerLow,
        contentColor = colorScheme.onSurface,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(
                modifier = handleModifier.fillMaxWidth().height(26.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(34.dp, 4.dp).clip(CircleShape).background(colorScheme.outlineVariant))
            }

            // ── Header ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = CircleShape,
                        color = colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "Queue",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = trackCountLabel(queue.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (queue.isNotEmpty()) {
                        TextButton(onClick = onClearQueue) {
                            Text("Clear", color = colorScheme.error, fontWeight = FontWeight.Medium)
                        }
                    }
                    FilledTonalIconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close queue")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = infiniteModeEnabled,
                    onClick = { onToggleInfiniteMode(!infiniteModeEnabled) },
                    label = { Text("Auto queue") },
                    leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)) },
                    shape = CircleShape
                )
                FilterChip(
                    selected = shuffleEnabled,
                    onClick = onToggleShuffle,
                    label = { Text("Shuffle") },
                    leadingIcon = { Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)) },
                    shape = CircleShape
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (queue.isEmpty()) {
                // ── Empty State ─────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Your queue is empty",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Add songs to start playing",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                // ── Scrollable queue content ────────────────────────
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = lazyListState
                ) {
                    // ── Now Playing ─────────────────────────────────
                    nowPlayingSong?.let { song ->
                        item(key = "now_playing_header", contentType = " header") {
                            SectionHeader(
                                title = "Now Playing",
                                color = colorScheme.primary
                            )
                        }

                        item(key = "now_playing_${song.id}", contentType = " now_playing") {
                            NowPlayingCard(
                                song = song,
                                queueIndex = safeIndex,
                                onClick = { onPlay(safeIndex) }
                            )
                        }
                    }

                    // ── Up Next ──────────────────────────────────────
                    if (localUpNext.isNotEmpty()) {
                        item(key = "up_next_header", contentType = " header") {
                            SectionHeader(
                                title = "Up Next",
                                subtitle = trackCountLabel(localUpNext.size),
                                color = colorScheme.onSurface
                            )
                        }

                        itemsIndexed(
                            items = localUpNext,
                            key = { _, item -> item.stableId },
                            contentType = { _, _ -> "track" }
                        ) { relativeIndex, item ->
                            val song = item.song
                            val absoluteIndex = safeIndex + 1 + relativeIndex

                            ReorderableItem(
                                state = reorderableState,
                                key = item.stableId,
                                animateItemModifier = Modifier.animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                    placementSpec = if (reduceMotion) null else MotionTokens.spatial()
                                )
                            ) { isDragging ->
                                val topCorner = if (relativeIndex == 0) 22.dp else 8.dp
                                val bottomCorner = if (relativeIndex == localUpNext.lastIndex) 22.dp else 8.dp
                                val rowShape = if (isDragging) RoundedCornerShape(20.dp) else RoundedCornerShape(
                                    topStart = topCorner,
                                    topEnd = topCorner,
                                    bottomStart = bottomCorner,
                                    bottomEnd = bottomCorner
                                )
                                val elevation by animateDpAsState(
                                    targetValue = if (isDragging) 10.dp else 0.dp,
                                    animationSpec = if (reduceMotion) snap() else MotionTokens.fastTween(),
                                    label = "DragElevation"
                                )
                                val scale by androidx.compose.animation.core.animateFloatAsState(
                                    targetValue = if (isDragging && !reduceMotion) 1.03f else 1f,
                                    animationSpec = if (reduceMotion) snap() else MotionTokens.InteractiveSpring,
                                    label = "DragScale"
                                )

                                Surface(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp, vertical = 2.dp)
                                        .shadow(elevation, shape = rowShape)
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        },
                                    shape = rowShape,
                                    color = if (isDragging) colorScheme.secondaryContainer else colorScheme.surfaceContainer
                                ) {
                                    TrackItem(
                                        song = song,
                                        isDimmed = false,
                                        queueIndex = absoluteIndex,
                                        onClick = { if (dragSnapshot == null) onPlay(absoluteIndex) },
                                        onRemove = { if (dragSnapshot == null) onRemove(absoluteIndex) },
                                        dragModifier = Modifier.draggableHandle(
                                            onDragStarted = {
                                                dragSnapshot = queue to safeIndex
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDragStopped = {
                                                val before = upNextEntries.indexOfFirst { it.stableId == item.stableId }
                                                val after = localUpNext.indexOfFirst { it.stableId == item.stableId }
                                                val canCommit = dragSnapshot == (queue to safeIndex) &&
                                                    before >= 0 && after >= 0 && before != after
                                                dragSnapshot = null
                                                if (canCommit) onReorder(safeIndex + 1 + before, safeIndex + 1 + after)
                                                else localUpNext = upNextEntries
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // ── History (previously played) ─────────────────
                    if (historySongs.isNotEmpty()) {
                        item(key = "history_header", contentType = " header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showHistory = !showHistory }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = null,
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Previously Played",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${historySongs.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Icon(
                                    imageVector = if (showHistory) Icons.Rounded.KeyboardArrowUp
                                        else Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (showHistory) {
                            itemsIndexed(
                                items = historySongs,
                                key = { index, song -> "hist_${song.id}_$index" },
                                contentType = { _, _ -> "history_track" }
                            ) { index, song ->
                                TrackItem(
                                    song = song,
                                    isDimmed = true,
                                    queueIndex = null,
                                    onClick = { onPlay(index) },
                                    onRemove = null,
                                    dragModifier = null,
                                    modifier = Modifier.padding(horizontal = 16.dp).animateItem(
                                        fadeInSpec = if (reduceMotion) null else MotionTokens.fastTween(),
                                        fadeOutSpec = if (reduceMotion) null else MotionTokens.fastTween(),
                                        placementSpec = if (reduceMotion) null else MotionTokens.spatial()
                                    )
                                )
                            }
                        }
                    }

                    // Bottom spacer
                    item(key = "queue_bottom_spacer", contentType = "spacer") { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
// COMPONENTS
// ═════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Now Playing card — prominent display of the current track
 */
@Composable
private fun NowPlayingCard(
    song: Song,
    queueIndex: Int,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .songPressable(
                song = song,
                source = SongActionSource.QUEUE,
                queueIndex = queueIndex,
                onClick = onClick
            ),
        shape = RoundedCornerShape(28.dp),
        color = colorScheme.primaryContainer,
        contentColor = colorScheme.onPrimaryContainer,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail with equalizer overlay
            val thumbnailShape = LocalThumbnailShape.current.toComposeShape()
            Box(modifier = Modifier.size(56.dp)) {
                SongThumbnail(
                    artworkUrl = song.thumbnailUrl,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(thumbnailShape)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(thumbnailShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "Now Playing",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Duration badge
            if (song.duration > 0) {
                Surface(
                    shape = CircleShape,
                    color = colorScheme.onPrimaryContainer.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = song.formattedDuration(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Track row used in Up Next and listening history.
 */
@Composable
private fun TrackItem(
    song: Song,
    isDimmed: Boolean,
    queueIndex: Int? = null,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    dragModifier: Modifier?,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentAlpha = if (isDimmed) 0.5f else 1f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .songCombinedClickable(
                song = song,
                source = SongActionSource.QUEUE,
                queueIndex = queueIndex,
                onClick = onClick
            )
            .alpha(contentAlpha)
            .padding(start = 6.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag handle (only for reorderable items)
        if (dragModifier != null) {
            Box(modifier = dragModifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Thumbnail
        Surface(
            modifier = Modifier.size(46.dp),
            shape = LocalThumbnailShape.current.toComposeShape(),
            color = colorScheme.surfaceContainerHigh
        ) {
            SongThumbnail(
                artworkUrl = song.thumbnailUrl,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title + Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Duration
        if (song.duration > 0) {
            Text(
                text = song.formattedDuration(),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        // Remove button (only for Up Next items)
        if (onRemove != null) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove ${song.title} from queue",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun trackCountLabel(count: Int) = if (count == 1) "1 track" else "$count tracks"
