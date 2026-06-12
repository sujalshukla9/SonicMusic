package com.sonicmusic.app.presentation.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonicmusic.app.player.queue.PlayerController
import com.sonicmusic.app.player.queue.QueueSong
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun QueueScreen(
    playerController: PlayerController,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queueManager = playerController.queueManager
    val queue by queueManager.currentQueue.collectAsState()
    val currentIndex by queueManager.currentIndex.collectAsState()
    val isFetchingMore by queueManager.isFetchingMore.collectAsState()

    val lazyListState = rememberLazyListState()
    val colorScheme = MaterialTheme.colorScheme

    // Auto-scroll to current index when it changes
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < queue.size) {
            // Wait slightly for layout to settle
            delay(100)
            lazyListState.animateScrollToItem(currentIndex)
        }
    }

    // Local mutable copy of queue to reflect drag-and-drop instantly before committing to ExoPlayer
    var localQueueList by remember(queue) { mutableStateOf(queue) }
    var dragFromIndex by remember { mutableStateOf(-1) }

    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (from.index !in localQueueList.indices || to.index !in localQueueList.indices) return@rememberReorderableLazyListState

        if (dragFromIndex == -1) {
            dragFromIndex = from.index
        }

        localQueueList = localQueueList.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = colorScheme.surface,
        contentColor = colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // Header Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Up Next",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${queue.size} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Close Queue",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Queue List
            if (localQueueList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Queue is empty",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    itemsIndexed(
                        items = localQueueList,
                        key = { _, song -> song.videoId }
                    ) { index, song ->
                        val isCurrent = index == currentIndex

                        ReorderableItem(reorderableState, key = song.videoId) { isDragging ->
                            val elevation by animateDpAsState(
                                targetValue = if (isDragging) 8.dp else 0.dp,
                                label = "ElevationAnimation"
                            )
                            val scale by animateFloatAsState(
                                targetValue = if (isDragging) 1.03f else 1f,
                                label = "ScaleAnimation"
                            )

                            Surface(
                                modifier = Modifier
                                    .shadow(elevation, shape = RoundedCornerShape(12.dp))
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = androidx.compose.animation.core.spring()
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isCurrent) {
                                    colorScheme.primaryContainer.copy(alpha = 0.25f)
                                } else if (isDragging) {
                                    colorScheme.surfaceContainerHigh
                                } else {
                                    Color.Transparent
                                }
                            ) {
                                QueueTrackRow(
                                    song = song,
                                    isCurrent = isCurrent,
                                    onClick = { playerController.playQueueItem(index) },
                                    onRemove = { playerController.removeSong(index) },
                                    dragModifier = Modifier.draggableHandle(
                                        onDragStopped = {
                                            if (dragFromIndex != -1) {
                                                val finalIndex = localQueueList.indexOfFirst { it.videoId == song.videoId }
                                                if (finalIndex != -1 && finalIndex != dragFromIndex) {
                                                    playerController.reorderSongs(dragFromIndex, finalIndex)
                                                }
                                                dragFromIndex = -1
                                            }
                                        }
                                    )
                                )
                            }
                        }
                    }

                    // Bottom loading indicator for fetching more items
                    item {
                        AnimatedVisibility(
                            visible = isFetchingMore,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = colorScheme.primary
                                    )
                                    Text(
                                        text = "Loading more suggestions...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QueueTrackRow(
    song: QueueSong,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    dragModifier: Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag Handle
        Icon(
            imageVector = Icons.Rounded.DragHandle,
            contentDescription = "Reorder",
            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = dragModifier
                .size(24.dp)
                .padding(end = 4.dp)
        )

        // Thumbnail
        Box(modifier = Modifier.size(46.dp)) {
            SongThumbnail(
                artworkUrl = song.thumbnailUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
            )
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "Playing",
                        tint = colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Artists
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) colorScheme.primary else colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artists,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Duration / Status
        if (song.durationMs > 0) {
            Text(
                text = song.formattedDuration(),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        // Remove Button
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove",
                tint = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// Suspend delay helper to mimic layout delays
private suspend fun delay(timeMs: Long) {
    kotlinx.coroutines.delay(timeMs)
}
