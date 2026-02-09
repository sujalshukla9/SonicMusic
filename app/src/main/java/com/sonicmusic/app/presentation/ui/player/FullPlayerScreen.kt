package com.sonicmusic.app.presentation.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sonicmusic.app.presentation.viewmodel.PlayerViewModel

/**
 * Redesigned Material 3 Expressive Full Player Screen
 * 
 * Features:
 * - Large album art with subtle shadow
 * - Interactive seek slider
 * - Clean typography hierarchy  
 * - Expressive control buttons
 * - Bottom-aligned action row
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    onDismiss: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLiked by viewModel.isLiked.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
    
    // Sheet states
    val sleepTimerRemaining by viewModel.sleepTimerRemaining.collectAsState()
    val sleepTimerActive by viewModel.sleepTimerActive.collectAsState()
    var showSleepTimerSheet by remember { mutableStateOf(false) }

    val equalizerEnabled by viewModel.equalizerEnabled.collectAsState()
    val equalizerBands by viewModel.equalizerBands.collectAsState()
    val equalizerPresets by viewModel.equalizerPresets.collectAsState()
    val currentPreset by viewModel.currentPreset.collectAsState()
    var showEqualizerSheet by remember { mutableStateOf(false) }

    val queue by viewModel.queue.collectAsState()
    val currentQueueIndex by viewModel.currentQueueIndex.collectAsState()
    val infiniteModeEnabled by viewModel.infiniteModeEnabled.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    var showQueueSheet by remember { mutableStateOf(false) }

    var showSpeedSheet by remember { mutableStateOf(false) }

    if (currentSong == null) {
        onDismiss()
        return
    }

    // Swipe to dismiss
    var offsetY by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = offsetY,
        animationSpec = tween(durationMillis = 100),
        label = "offset"
    )

    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorScheme.surface
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = animatedOffset }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount > 0) offsetY += dragAmount
                        },
                        onDragEnd = {
                            if (offsetY > 200) onDismiss()
                            offsetY = 0f
                        }
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                // ═══════════════════════════════════════════
                // TOP SECTION - Album Art
                // ═══════════════════════════════════════════
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(80.dp))
                    
                    // Album Art - Full width with subtle corners
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        shape = RoundedCornerShape(12.dp),
                        shadowElevation = 12.dp,
                        tonalElevation = 0.dp,
                        color = colorScheme.surfaceContainerHigh
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(currentSong?.thumbnailUrl)
                                .crossfade(300)
                                .size(coil.size.Size.ORIGINAL)
                                .build(),
                            contentDescription = "Album Art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(100.dp))
                
                // ═══════════════════════════════════════════
                // SONG INFO
                // ═══════════════════════════════════════════
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentSong?.title ?: "",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = currentSong?.artist ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        // Like button
                        IconButton(onClick = { viewModel.toggleLike() }) {
                            Icon(
                                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Like",
                                tint = if (isLiked) colorScheme.error else colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // ═══════════════════════════════════════════
                // PROGRESS SLIDER
                // ═══════════════════════════════════════════
                // ═══════════════════════════════════════════
                // PROGRESS SLIDER - Isolated
                // ═══════════════════════════════════════════
                PlayerProgressSection(viewModel = viewModel)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // ═══════════════════════════════════════════
                // MAIN CONTROLS
                // ═══════════════════════════════════════════
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle
                    IconButton(
                        onClick = { viewModel.toggleShuffle() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (shuffleEnabled) Icons.Filled.Shuffle else Icons.Outlined.Shuffle,
                            contentDescription = "Shuffle",
                            modifier = Modifier.size(24.dp),
                            tint = if (shuffleEnabled) colorScheme.primary else colorScheme.onSurfaceVariant
                        )
                    }

                    // Previous
                    FilledIconButton(
                        onClick = { viewModel.skipToPrevious() },
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colorScheme.surfaceContainerHighest,
                            contentColor = colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Play/Pause - Large expressive button
                    FilledIconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(80.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colorScheme.primary,
                            contentColor = colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    // Next
                    FilledIconButton(
                        onClick = { viewModel.skipToNext() },
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colorScheme.surfaceContainerHighest,
                            contentColor = colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Repeat
                    IconButton(
                        onClick = { viewModel.toggleRepeatMode() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = when (repeatMode) {
                                Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                                Player.REPEAT_MODE_ALL -> Icons.Filled.Repeat
                                else -> Icons.Outlined.Repeat
                            },
                            contentDescription = "Repeat",
                            modifier = Modifier.size(24.dp),
                            tint = if (repeatMode != Player.REPEAT_MODE_OFF) 
                                colorScheme.primary else colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // ═══════════════════════════════════════════
                // BOTTOM ACTIONS (pushed to bottom)
                // ═══════════════════════════════════════════
                Spacer(modifier = Modifier.weight(1f))
                
                // Sleep timer indicator
                if (sleepTimerActive) {
                    SleepTimerIndicator(
                        remainingTime = sleepTimerRemaining,
                        onClick = { showSleepTimerSheet = true }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sleep Timer
                    IconButton(
                        onClick = { showSleepTimerSheet = true },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bedtime,
                            contentDescription = "Sleep Timer",
                            modifier = Modifier.size(22.dp),
                            tint = if (sleepTimerActive) colorScheme.primary 
                                else colorScheme.onSurfaceVariant
                        )
                    }

                    // Equalizer
                    IconButton(
                        onClick = { showEqualizerSheet = true },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Equalizer",
                            modifier = Modifier.size(22.dp),
                            tint = if (equalizerEnabled) colorScheme.primary 
                                else colorScheme.onSurfaceVariant
                        )
                    }

                    // Queue
                    IconButton(
                        onClick = { showQueueSheet = true },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Queue",
                            modifier = Modifier.size(22.dp),
                            tint = colorScheme.onSurfaceVariant
                        )
                    }

                    // More
                    IconButton(
                        onClick = { showSpeedSheet = true },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More",
                            modifier = Modifier.size(22.dp),
                            tint = colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Bottom Sheets
    SleepTimerSheet(
        isVisible = showSleepTimerSheet,
        onDismiss = { showSleepTimerSheet = false },
        onSelectDuration = { dur ->
            viewModel.startSleepTimer(dur)
            showSleepTimerSheet = false
        },
        onCancel = { viewModel.cancelSleepTimer() },
        onAddFiveMinutes = { viewModel.extendSleepTimer() },
        isTimerActive = sleepTimerActive,
        remainingTime = sleepTimerRemaining
    )

    EqualizerSheet(
        isVisible = showEqualizerSheet,
        onDismiss = { showEqualizerSheet = false },
        enabled = equalizerEnabled,
        onEnabledChange = viewModel::setEqualizerEnabled,
        bands = equalizerBands,
        onBandLevelChange = viewModel::setBandLevel,
        presets = equalizerPresets,
        currentPreset = currentPreset,
        onPresetSelect = viewModel::usePreset
    )

    QueueSheet(
        isVisible = showQueueSheet,
        onDismiss = { showQueueSheet = false },
        queue = queue,
        currentIndex = currentQueueIndex,
        onRemove = viewModel::removeFromQueue,
        onReorder = viewModel::reorderQueue,
        onClearQueue = viewModel::clearQueue,
        onPlay = { index ->
            viewModel.skipToQueueItem(index)
            showQueueSheet = false
        },
        infiniteModeEnabled = infiniteModeEnabled,
        onToggleInfiniteMode = viewModel::toggleInfiniteMode,
        isLoadingMore = isLoadingMore,
        onRefreshRecommendations = viewModel::refreshRecommendations
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerProgressSection(viewModel: PlayerViewModel) {
    val progress by viewModel.progress.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val colorScheme = MaterialTheme.colorScheme

    // Track dragging state with remember
    var isUserDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    
    // The value to display: use drag value when dragging, otherwise use progress
    val sliderPosition = if (isUserDragging) dragValue else progress

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Slider(
            value = sliderPosition,
            onValueChange = { newValue ->
                // User started/is dragging
                isUserDragging = true
                dragValue = newValue
            },
            onValueChangeFinished = {
                // User finished dragging - seek and reset state
                viewModel.seekTo(dragValue)
                isUserDragging = false
            },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = colorScheme.primary,
                activeTrackColor = colorScheme.primary,
                inactiveTrackColor = colorScheme.surfaceContainerHighest
            )
        )
        
        // Calculate display time based on drag state
        val displayTimeMs = if (isUserDragging) {
            (dragValue * duration).toLong()
        } else {
            currentPosition
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration((displayTimeMs / 1000).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant
            )
            Text(
                text = formatDuration((duration / 1000).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds < 0) return "0:00"
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", minutes, secs)
}