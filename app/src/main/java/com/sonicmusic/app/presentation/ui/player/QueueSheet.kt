package com.sonicmusic.app.presentation.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sonicmusic.app.domain.model.Song
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Redesigned Queue Bottom Sheet
 *
 * Features:
 * - Song thumbnails for visual identification
 * - Drag-to-reorder with smooth animations
 * - Swipe-to-remove with delete confirmation
 * - Playing indicator with equalizer animation
 * - Clear header with song count
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
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    var localQueue by remember(queue) { mutableStateOf(queue) }
    
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localQueue = localQueue.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        onReorder(from.index, to.index)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // ═══════════════════════════════════════════
            // HEADER
            // ═══════════════════════════════════════════
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
                            text = "Up Next",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = "${localQueue.size} songs in queue",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (localQueue.isNotEmpty()) {
                    TextButton(
                        onClick = onClearQueue
                    ) {
                        Text(
                            text = "Clear",
                            color = colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ═══════════════════════════════════════════
            // QUEUE LIST
            // ═══════════════════════════════════════════
            if (localQueue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = lazyListState
                ) {
                    itemsIndexed(
                        items = localQueue,
                        key = { index, song -> "${song.id}_$index" }
                    ) { index, song ->
                        val isPlaying = index == currentIndex

                        ReorderableItem(reorderableState, key = "${song.id}_$index") { isDragging ->
                            val elevation by animateDpAsState(
                                targetValue = if (isDragging) 8.dp else 0.dp,
                                label = "DragElevation"
                            )

                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = {
                                    if (it == SwipeToDismissBoxValue.EndToStart) {
                                        onRemove(index)
                                        true
                                    } else false
                                }
                            )

                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                enableDismissFromEndToStart = !isDragging,
                                backgroundContent = {
                                    val color by animateColorAsState(
                                        if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                                            colorScheme.errorContainer
                                        else colorScheme.surface,
                                        label = "DismissColor"
                                    )
                                    val scale by animateFloatAsState(
                                        if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) 1f else 0.75f,
                                        label = "DismissScale"
                                    )
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(color)
                                            .padding(horizontal = 24.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            modifier = Modifier.scale(scale),
                                            tint = colorScheme.onErrorContainer
                                        )
                                    }
                                },
                                content = {
                                    Surface(
                                        modifier = Modifier.shadow(elevation),
                                        color = if (isDragging)
                                            colorScheme.surfaceContainerHigh
                                        else Color.Transparent
                                    ) {
                                        QueueItemCard(
                                            song = song,
                                            isPlaying = isPlaying,
                                            isDragging = isDragging,
                                            onClick = { onPlay(index) },
                                            dragModifier = Modifier.draggableHandle()
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemCard(
    song: Song,
    isPlaying: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                when {
                    isDragging -> colorScheme.surfaceContainerHigh
                    isPlaying -> colorScheme.primaryContainer.copy(alpha = 0.15f)
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag Handle
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "Drag to reorder",
            tint = if (isDragging) colorScheme.primary 
                else colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = dragModifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Thumbnail
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(8.dp),
            color = colorScheme.surfaceContainerHigh
        ) {
            Box {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(song.thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                
                // Playing overlay
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Now Playing",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Song Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isPlaying) colorScheme.primary else colorScheme.onSurface,
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

        // Play indicator
        if (isPlaying) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = CircleShape,
                color = colorScheme.primary,
                modifier = Modifier.size(8.dp)
            ) {}
        }
    }
}
