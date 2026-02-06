package com.sonicmusic.app.presentation.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PlayerUI(
    viewModel: PlayerViewModel = hiltViewModel(),
    isExpanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    bottomPadding: Dp = 0.dp // Height of the Navigation Bar
) {
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()

    if (playerState is PlaybackState.State) {
        val state = playerState as PlaybackState.State
        ExpandablePlayer(
            state = state,
            isExpanded = isExpanded,
            onExpand = onExpand,
            onCollapse = onCollapse,
            onPlayPause = viewModel::togglePlayPause,
            onSkipNext = {}, // Implement in VM
            onSkipPrevious = {}, // Implement in VM
            onSeek = {}, // Implement in VM
            bottomPadding = bottomPadding
        )
    }
}

@Composable
fun ExpandablePlayer(
    state: PlaybackState.State,
    isExpanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    bottomPadding: Dp
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    
    // Constraints
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fullHeight = maxHeight
        val miniPlayerHeight = 72.dp // Compact height
        
        // Calculate offsets
        // Collapsed: At bottom, above Nav Bar
        val collapsedOffset = fullHeight - (miniPlayerHeight + bottomPadding)
        // Expanded: At top (0)
        val expandedOffset = 0.dp
        
        // Total distance to drag
        val totalDragDistance = collapsedOffset
        
        // Animation State
        // 0f = Collapsed (Mini), 1f = Expanded (Full)
        val expansionProgress = remember { Animatable(if (isExpanded) 1f else 0f) }
        
        LaunchedEffect(isExpanded) {
            expansionProgress.animateTo(
                if (isExpanded) 1f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessLow)
            )
        }

        // Gesture Handling
        val draggableState = rememberDraggableState { delta ->
             val dragDistancePx = with(density) { totalDragDistance.toPx() }
             val newProgress = (expansionProgress.value - delta / dragDistancePx).coerceIn(0f, 1f)
             scope.launch { expansionProgress.snapTo(newProgress) }
        }
        
        // Android Back Handler
        BackHandler(enabled = isExpanded) {
            onCollapse()
        }

        // SURFACE
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(fullHeight)
                .offset {
                    val yOffset = lerp(
                        collapsedOffset,
                        expandedOffset,
                        expansionProgress.value
                    )
                    IntOffset(0, yOffset.roundToPx())
                }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (velocity < -1000 || expansionProgress.value > 0.5f) {
                            onExpand()
                        } else {
                            onCollapse()
                        }
                    }
                ),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 1.dp,
            shadowElevation = if (isExpanded) 8.dp else 4.dp,
            shape = RoundedCornerShape(
                topStart = lerp(12.dp, 0.dp, expansionProgress.value),
                topEnd = lerp(12.dp, 0.dp, expansionProgress.value)
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // BACKGROUND ARTWORK BLUR
                 if (expansionProgress.value > 0.01f) {
                     Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(expansionProgress.value)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                     )
                }

                // 1. MINI PLAYER CONTENT
                val miniAlpha = (1f - expansionProgress.value * 5f).coerceIn(0f, 1f)
                if (miniAlpha > 0f) {
                    MiniPlayerContent(
                        state = state,
                        modifier = Modifier
                            .alpha(miniAlpha)
                            .height(miniPlayerHeight),
                        onPlayPause = onPlayPause,
                        onSkipNext = onSkipNext
                    )
                }
                
                // 2. FULL PLAYER CONTENT
                val fullAlpha = ((expansionProgress.value - 0.2f) * 1.5f).coerceIn(0f, 1f)
                if (fullAlpha > 0f) {
                    FullPlayerContent(
                        state = state,
                        modifier = Modifier
                            .alpha(fullAlpha)
                            .fillMaxSize(),
                        onCollapse = onCollapse,
                        onPlayPause = onPlayPause,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                        onSeek = onSeek
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayerContent(
    state: PlaybackState.State,
    modifier: Modifier = Modifier,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data("https://via.placeholder.com/150") // Replace with state.artworkUrl
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            
            Spacer(modifier = Modifier.size(12.dp))
            
            // Text Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = state.currentMediaId ?: "Unknown Title",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
                Text(
                    text = "Artist Name", // Placeholder
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            
            // Controls
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null
                )
            }
            
            IconButton(onClick = onSkipNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = null)
            }
        }
        // Progress Indicator at bottom
        LinearProgressIndicator(
            progress = { 0.3f }, // Placeholder
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.BottomCenter),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullPlayerContent(
    state: PlaybackState.State,
    modifier: Modifier = Modifier,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Float) -> Unit
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse", modifier = Modifier.size(32.dp))
            }
            Text(
                text = "NOW PLAYING",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = { /* Check Queue */ }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Options")
            }
        }
        
        Spacer(modifier = Modifier.weight(0.1f))
        
        // Artwork
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .aspectRatio(1f)
                .shadow(16.dp, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
             AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data("https://via.placeholder.com/600") // Replace with state.artworkUrl
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        Spacer(modifier = Modifier.weight(0.1f))
        
        // Info Row (Title, Like)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.currentMediaId ?: "Unknown Title",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee()
                )
                Text(
                    text = "Artist Name • Album",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = { /* Toggle Like */ }) {
                Icon(
                    imageVector = Icons.Default.FavoriteBorder,
                    contentDescription = "Like",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Progress Slider
        Column {
            Slider(
                value = 0.3f, // Drag state
                onValueChange = onSeek,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1:23", style = MaterialTheme.typography.labelMedium)
                Text("4:56", style = MaterialTheme.typography.labelMedium)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Main Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = { /* Shuffle */ }) {
                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", modifier = Modifier.size(24.dp))
            }
            
            IconButton(onClick = onSkipPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(48.dp))
            }
            
            // Play/Pause FAB
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp).clickable(onClick = onPlayPause)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            IconButton(onClick = onSkipNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(48.dp))
            }
            
            IconButton(onClick = { /* Repeat */ }) {
                Icon(Icons.Default.Repeat, contentDescription = "Repeat", modifier = Modifier.size(24.dp))
            }
        }
        
        Spacer(modifier = Modifier.weight(0.2f))
        
        // Bottom Tools (Queue, Share)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
             IconButton(onClick = { /* Share */ }) {
                Icon(Icons.Outlined.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { /* Queue */ }) {
                Icon(Icons.Outlined.QueueMusic, contentDescription = "Queue", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Helper to calculate DP from Px
@Composable
fun Float.toDp(): Dp = with(LocalDensity.current) { this@toDp.toDp() }


