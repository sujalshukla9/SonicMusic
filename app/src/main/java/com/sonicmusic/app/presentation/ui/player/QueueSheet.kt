package com.sonicmusic.app.presentation.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Queue Bottom Sheet — Music App Style (ViTune / YTMusic)
 *
 * Layout:
 * 1. Header (title + song count + clear button)
 * 2. Settings card (Auto Queue Similar, Shuffle)
 * 3. "Now Playing" card
 * 4. "Up Next" numbered track list (reorderable via drag)
 * 5. "History" collapsed section (previously played, dimmed)
 *
 * DRAG FIX: We use a SEPARATE LazyColumn for the reorderable "Up Next" list
 * to avoid index offset issues with headers in the same LazyColumn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    isVisible: Boolean,
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
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    var showHistory by remember { mutableStateOf(false) }
    val safeIndex = currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
    val nowPlayingSong = queue.getOrNull(safeIndex)
    val upNextSongs = if (queue.size > safeIndex + 1) queue.subList(safeIndex + 1, queue.size) else emptyList()
    val historySongs = if (safeIndex > 0) queue.subList(0, safeIndex) else emptyList()
    val upNextStartIndexInLazyList = (if (nowPlayingSong != null) 2 else 0) + 1

    // Local mutable copy of the up-next list so drag-and-drop reflects instantly
    var localUpNext by remember(upNextSongs) { mutableStateOf(upNextSongs) }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Reorderable callbacks use absolute LazyColumn indices (including headers).
        val fromRelative = from.index - upNextStartIndexInLazyList
        val toRelative = to.index - upNextStartIndexInLazyList
        if (fromRelative !in localUpNext.indices || toRelative !in localUpNext.indices) {
            return@rememberReorderableLazyListState
        }

        // These indices are now relative to localUpNext (0-based)
        localUpNext = localUpNext.toMutableList().apply {
            add(toRelative, removeAt(fromRelative))
        }

        // Convert to absolute queue indices for the player
        val absoluteFrom = safeIndex + 1 + fromRelative
        val absoluteTo = safeIndex + 1 + toRelative
        onReorder(absoluteFrom, absoluteTo)
    }

    val colorScheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
        containerColor = colorScheme.surface,
        contentColor = colorScheme.onSurface,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
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
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = "${queue.size} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (queue.isNotEmpty()) {
                    TextButton(onClick = onClearQueue) {
                        Text(
                            text = "Clear",
                            color = colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ── Settings Card ───────────────────────────────────────
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
                color = colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto Queue Similar",
                                style = MaterialTheme.typography.titleSmall,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "Keep queue filled with recommendations",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = infiniteModeEnabled,
                            onCheckedChange = onToggleInfiniteMode
                        )
                    }

                    HorizontalDivider(
                        color = colorScheme.outlineVariant.copy(alpha = 0.45f),
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Shuffle",
                                style = MaterialTheme.typography.titleSmall,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "Play queue in random order",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = shuffleEnabled,
                            onCheckedChange = { onToggleShuffle() }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                        item(key = "now_playing_header") {
                            SectionHeader(
                                title = "Now Playing",
                                color = colorScheme.primary
                            )
                        }

                        item(key = "now_playing_${song.id}") {
                            NowPlayingCard(
                                song = song,
                                onClick = { onPlay(safeIndex) }
                            )
                        }
                    }

                    // ── Up Next ──────────────────────────────────────
                    if (localUpNext.isNotEmpty()) {
                        item(key = "up_next_header") {
                            SectionHeader(
                                title = "Up Next",
                                subtitle = "${localUpNext.size} tracks",
                                color = colorScheme.onSurface
                            )
                        }

                        itemsIndexed(
                            items = localUpNext,
                            key = { _, song -> song.id }
                        ) { relativeIndex, song ->
                            val absoluteIndex = safeIndex + 1 + relativeIndex

                            ReorderableItem(reorderableState, key = song.id) { isDragging ->
                                val elevation by animateDpAsState(
                                    targetValue = if (isDragging) 12.dp else 0.dp,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                    ),
                                    label = "DragElevation"
                                )
                                val scale by androidx.compose.animation.core.animateFloatAsState(
                                    targetValue = if (isDragging) 1.03f else 1f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                                    ),
                                    label = "DragScale"
                                )

                                Surface(
                                    modifier = Modifier
                                        .shadow(elevation, shape = RoundedCornerShape(12.dp))
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .animateItem(
                                            fadeInSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                                            fadeOutSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                                            placementSpec = androidx.compose.animation.core.spring(
                                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                                                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                            )
                                        ),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isDragging) colorScheme.surfaceContainerHigh else Color.Transparent
                                ) {
                                    TrackItem(
                                        song = song,
                                        trackNumber = relativeIndex + 1,
                                        isPlaying = false,
                                        isDimmed = false,
                                        onClick = { onPlay(absoluteIndex) },
                                        onRemove = {
                                            onRemove(absoluteIndex)
                                            localUpNext = localUpNext.filterNot { it.id == song.id }
                                        },
                                        dragModifier = Modifier.draggableHandle()
                                    )
                                }
                            }
                        }
                    }

                    // ── History (previously played) ─────────────────
                    if (historySongs.isNotEmpty()) {
                        item(key = "history_header") {
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
                                key = { idx, song -> "hist_${song.id}_$idx" }
                            ) { index, song ->
                                TrackItem(
                                    song = song,
                                    trackNumber = index + 1,
                                    isPlaying = false,
                                    isDimmed = true,
                                    onClick = { onPlay(index) },
                                    onRemove = null,
                                    dragModifier = null
                                )
                            }
                        }
                    }

                    // Bottom spacer
                    item { Spacer(modifier = Modifier.height(24.dp)) }
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
            style = MaterialTheme.typography.titleSmall,
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
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.primaryContainer.copy(alpha = 0.18f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail with equalizer overlay
            Box(modifier = Modifier.size(56.dp)) {
                SongThumbnail(
                    artworkUrl = song.thumbnailUrl,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "Now Playing",
                        tint = colorScheme.primary,
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
                    color = colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Duration badge
            if (song.duration > 0) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = song.formattedDuration(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Track item — numbered row for Up Next and History sections
 */
@Composable
private fun TrackItem(
    song: Song,
    trackNumber: Int,
    isPlaying: Boolean,
    isDimmed: Boolean,
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
            .clickable(onClick = onClick)
            .alpha(contentAlpha)
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag handle (only for reorderable items)
        if (dragModifier != null) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = "Drag to reorder",
                tint = colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = dragModifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Track number
        Text(
            text = "$trackNumber",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (isPlaying) colorScheme.primary else colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.width(28.dp),
            maxLines = 1
        )

        // Thumbnail
        Surface(
            modifier = Modifier.size(46.dp),
            shape = RoundedCornerShape(8.dp),
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
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isPlaying) colorScheme.primary else colorScheme.onSurface,
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
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove",
                    tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
