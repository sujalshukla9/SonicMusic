package com.sonicmusic.app.features.player.presentation

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import com.sonicmusic.app.theme.pressScale
import com.sonicmusic.app.theme.shouldReduceMotion
import com.sonicmusic.app.theme.MotionTokens
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.domain.model.PlayerControlStyle
import com.sonicmusic.app.features.player.presentation.PlayerUiState
import com.sonicmusic.app.features.player.presentation.PlayerActions
import com.sonicmusic.app.features.player.presentation.ClassicPlayerControls
import com.sonicmusic.app.features.player.presentation.ModernPlayerControls
import com.sonicmusic.app.theme.toComposeShape
import com.sonicmusic.app.theme.toRoundedPolygon
import com.sonicmusic.app.theme.MorphComposeShape
import com.sonicmusic.app.theme.animated
import androidx.compose.ui.graphics.luminance
import com.sonicmusic.app.theme.LocalPlayerColorScheme
import com.sonicmusic.app.theme.ColorExtractor
import androidx.graphics.shapes.Morph
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import com.sonicmusic.app.shared.components.SongThumbnail
import com.sonicmusic.app.shared.components.AudioBadgeRow
import com.sonicmusic.app.features.player.presentation.sheets.SongDetailsSheet
import com.sonicmusic.app.features.player.presentation.LyricsUiState
import com.sonicmusic.app.features.player.presentation.PlayerViewModel
import com.sonicmusic.app.features.player.presentation.AlbumLinkState
import com.sonicmusic.app.features.player.presentation.sheets.*
import com.sonicmusic.app.app.LocalWindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import com.sonicmusic.app.theme.Dimens
import androidx.compose.foundation.basicMarquee
import java.net.URLEncoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sin
import kotlinx.coroutines.launch

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
    onOpenArtist: (String, String?) -> Unit = { _, _ -> },
    onOpenAlbum: (String, String, String, String?) -> Unit = { _, _, _, _ -> },
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val fullPlayerStyle by viewModel.fullPlayerStyle.collectAsStateWithLifecycle()
    val albumArtBlurEnabled by viewModel.albumArtBlurEnabled.collectAsStateWithLifecycle()
    val thumbnailShape by viewModel.thumbnailShape.collectAsStateWithLifecycle()
    val playerControlStyle by viewModel.playerControlStyle.collectAsStateWithLifecycle()
    



    // Core Playback states collected at the top level
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val currentQueueIndex by viewModel.currentQueueIndex.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val infiniteModeEnabled by viewModel.infiniteModeEnabled.collectAsStateWithLifecycle()
    val isLiked by viewModel.isLiked.collectAsStateWithLifecycle()

    // Sheet states — only collected when sheets are open
    val sleepTimerRemaining by viewModel.sleepTimerRemaining.collectAsStateWithLifecycle()
    val sleepTimerActive by viewModel.sleepTimerActive.collectAsStateWithLifecycle()
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    var showSpeedSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }
    var showSongDetailsSheet by remember { mutableStateOf(false) }

    val streamInfo by viewModel.currentStreamInfo.collectAsStateWithLifecycle()
    val audioQualityUiState by viewModel.audioQualityUiState.collectAsStateWithLifecycle()
    val audioCapability by viewModel.audioCapability.collectAsStateWithLifecycle()

    val liveQualityBadge = remember(streamInfo, audioQualityUiState) {
        streamInfo?.qualityBadge?.takeIf { it != "Audio" }
            ?: audioQualityUiState?.toAudioStreamInfo()?.qualityBadge?.takeIf { it != "Audio" }
    }

    // Lyrics overlay state
    var showLyricsOverlay by remember { mutableStateOf(false) }
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val albumLinkState by viewModel.albumLinkState.collectAsStateWithLifecycle()

    var showDoubleTapForwardIndicator by remember { mutableStateOf(false) }
    var showDoubleTapBackwardIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(showDoubleTapForwardIndicator) {
        if (showDoubleTapForwardIndicator) {
            kotlinx.coroutines.delay(650)
            showDoubleTapForwardIndicator = false
        }
    }

    LaunchedEffect(showDoubleTapBackwardIndicator) {
        if (showDoubleTapBackwardIndicator) {
            kotlinx.coroutines.delay(650)
            showDoubleTapBackwardIndicator = false
        }
    }

    if (currentSong == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenHeightPx = remember(configuration, density) {
        with(density) { configuration.screenHeightDp.dp.toPx() }
    }

    val reduceMotion = shouldReduceMotion()

    // Swipe to dismiss and enter animation
    val offsetYAnim = remember { Animatable(if (reduceMotion) 0f else screenHeightPx * 1.5f) }
    val dismissScope = rememberCoroutineScope()

    val dismissQueueSheet = remember {
        {
            showQueueSheet = false
            Unit
        }
    }

    // Emphasized easings for transitions
    val EmphasizedDecelerateEasing = remember { androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) }
    val EmphasizedAccelerateEasing = remember { androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f) }

    // Slide up enter animation
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            offsetYAnim.snapTo(0f)
        } else {
            offsetYAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 400, easing = EmphasizedDecelerateEasing)
            )
        }
    }

    val animateAndDismiss: () -> Unit = remember(screenHeightPx, onDismiss, reduceMotion) {
        {
            dismissScope.launch {
                if (reduceMotion) {
                    offsetYAnim.snapTo(screenHeightPx)
                    onDismiss()
                } else {
                    val currentOffset = offsetYAnim.value
                    val remainingDistance = (screenHeightPx - currentOffset).coerceAtLeast(0f)
                    val duration = ((remainingDistance / screenHeightPx) * 320f).toInt().coerceIn(160, 320)
                    offsetYAnim.animateTo(
                        targetValue = screenHeightPx,
                        animationSpec = tween(durationMillis = duration, easing = EmphasizedAccelerateEasing)
                    )
                    onDismiss()
                }
            }
            Unit
        }
    }

    // Handle system back button to dismiss full player instead of closing app
    BackHandler {
        animateAndDismiss()
    }

    val baseColorScheme = MaterialTheme.colorScheme
    val colorScheme = baseColorScheme

    val accentColor by animateColorAsState(
        targetValue = baseColorScheme.primary,
        animationSpec = tween(durationMillis = 300),
        label = "full_player_accent"
    )
    val onAccentColor by animateColorAsState(
        targetValue = baseColorScheme.onPrimary,
        animationSpec = tween(durationMillis = 300),
        label = "full_player_on_accent"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val progress = (offsetYAnim.value / screenHeightPx).coerceIn(0f, 1f)
                val scrimAlpha = (1f - progress).coerceIn(0f, 1f)
                drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f * scrimAlpha))
            }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = (offsetYAnim.value / screenHeightPx).coerceIn(0f, 1f)
                    val currentDragScale = androidx.compose.ui.util.lerp(1f, 0.90f, progress)
                    val currentCornerRadius = androidx.compose.ui.util.lerp(0f, 32f, progress).dp

                    translationY = offsetYAnim.value
                    scaleX = currentDragScale
                    scaleY = currentDragScale
                    clip = true
                    shape = RoundedCornerShape(currentCornerRadius)
                    shadowElevation = (16f * progress).dp.toPx()
                }
                .pointerInput(screenHeightPx) {
                    val velocityTracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = {
                            velocityTracker.resetTracking()
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            dismissScope.launch {
                                val current = offsetYAnim.value
                                val target = if (current + dragAmount < 0f) {
                                    // Rubber-band resistance when pulling upward past top edge
                                    current + dragAmount * 0.25f
                                } else {
                                    current + dragAmount
                                }
                                offsetYAnim.snapTo(target)
                            }
                        },
                        onDragEnd = {
                            val velocityY = velocityTracker.calculateVelocity().y
                            val currentOffset = offsetYAnim.value
                            val shouldDismiss = velocityY > 800f || currentOffset > screenHeightPx * 0.22f

                            if (shouldDismiss && currentOffset > 0f) {
                                animateAndDismiss()
                            } else {
                                dismissScope.launch {
                                    offsetYAnim.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            dismissScope.launch {
                                offsetYAnim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        }
                    )
                },
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = colorScheme.surface
        ) {
            val isAnimating = offsetYAnim.isRunning || offsetYAnim.value != 0f
            val blurAlpha by animateFloatAsState(
                targetValue = if (isAnimating) 0f else 1f,
                animationSpec = tween(durationMillis = 350),
                label = "blurAlpha"
            )

            FullPlayerBackground(
                artworkUrl = currentSong?.thumbnailUrl,
                enableArtworkBlur = albumArtBlurEnabled,
                blurAlpha = blurAlpha,
                modifier = Modifier.fillMaxSize()
            )
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 10.dp)
            ) {
                val availableHeight = maxHeight
                val availableWidth = maxWidth
                val isCompactHeight = availableHeight < 600.dp

                // ViTune-style: album art is as large as possible — fill up to 88% of width
                // or up to ~52% of available height (portrait), whichever is smaller.
                // This gives a large, dominant artwork just like ViTune.
                val maxArtWidth = availableWidth * 0.88f
                val maxArtFromHeight = when {
                    availableHeight >= 800.dp -> availableHeight * 0.52f
                    availableHeight >= 680.dp -> availableHeight * 0.50f
                    availableHeight >= 580.dp -> availableHeight * 0.47f
                    else -> availableHeight * 0.44f
                }
                val artworkSize = minOf(maxArtWidth, maxArtFromHeight).coerceAtLeast(160.dp)

                // Spacing between artwork and controls — adjusted to preserve player controls position after badge removal
                val artworkSpacing = when {
                    availableHeight >= 800.dp -> 72.dp
                    availableHeight >= 680.dp -> 54.dp
                    availableHeight >= 580.dp -> 36.dp
                    else -> 20.dp
                }

                val isBottomCompact = isCompactHeight || availableWidth < 360.dp

                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 16.dp))
                    // No internet banner
                    val isOnline = com.sonicmusic.app.shared.components.LocalIsOnline.current
                    com.sonicmusic.app.shared.components.NoInternetBanner(isVisible = !isOnline)

                    val windowSizeClass = LocalWindowSizeClass.current
                    val useLandscapeLayout = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact ||
                            windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

                    if (useLandscapeLayout) {
                        // ═══════════════════════════════════════════
                        // LANDSCAPE / WIDE LAYOUT (Two columns)
                        // ═══════════════════════════════════════════
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = Dimens.screenPadding, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Left column: Album art and capability badges
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                val swipeScope = rememberCoroutineScope()
                                val artOffsetX = remember { Animatable(0f) }

                                // Animate new artwork sliding in when song changes
                                var lastQueueIndexForSwipe by remember { mutableIntStateOf(currentQueueIndex) }
                                var lastSongIdForSwipe by remember { mutableStateOf(currentSong?.id) }
                                var displayedSong by remember { mutableStateOf(currentSong) }

                                LaunchedEffect(currentSong?.id) {
                                    val newSong = currentSong
                                    val newId = newSong?.id
                                    val newIndex = currentQueueIndex
                                    
                                    if (newId != null && newId != lastSongIdForSwipe) {
                                        if (reduceMotion) {
                                            artOffsetX.snapTo(0f)
                                            displayedSong = newSong
                                        } else {
                                            val isNext = if (abs(artOffsetX.value) > 100f) {
                                                artOffsetX.value < 0f
                                            } else {
                                                newIndex > lastQueueIndexForSwipe
                                            }
                                            
                                            if (abs(artOffsetX.value) < 100f) {
                                                val exitTo = if (isNext) -1000f else 1000f
                                                artOffsetX.animateTo(
                                                    targetValue = exitTo,
                                                    animationSpec = tween(durationMillis = 150, easing = LinearEasing)
                                                )
                                            }
                                            
                                            val entryFrom = if (isNext) 1f else -1f
                                            
                                            artOffsetX.snapTo(entryFrom * 800f)
                                            displayedSong = newSong
                                            
                                            artOffsetX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.7f,
                                                    stiffness = 500f
                                                )
                                            )
                                        }
                                        lastSongIdForSwipe = newId
                                        lastQueueIndexForSwipe = newIndex
                                        showLyricsOverlay = false
                                        viewModel.clearLyricsState()
                                    } else {
                                        displayedSong = newSong
                                        lastQueueIndexForSwipe = newIndex
                                    }
                                }

                                ArtworkSection(
                                    displayedSong = displayedSong,
                                    thumbnailShape = thumbnailShape,
                                    showDoubleTapForwardIndicator = showDoubleTapForwardIndicator,
                                    showDoubleTapBackwardIndicator = showDoubleTapBackwardIndicator,
                                    showLyricsOverlay = showLyricsOverlay,
                                    lyricsState = lyricsState,
                                    artOffsetX = artOffsetX,
                                    swipeScope = swipeScope,
                                    viewModel = viewModel,
                                    onLyricsOverlayToggle = { showLyricsOverlay = it },
                                    onDoubleTapForwardChange = { showDoubleTapForwardIndicator = it },
                                    onDoubleTapBackwardChange = { showDoubleTapBackwardIndicator = it },
                                    useLandscapeLayout = true,
                                    modifier = Modifier.fillMaxSize(),
                                    artworkSize = artworkSize,
                                    availableHeight = availableHeight
                                )
                            }

                            // Right column: Metadata, play controls, seekbar, and option sheet launcher
                            Column(
                                modifier = Modifier
                                    .weight(1.2f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val isLandscapeCompact = (availableWidth * (1.2f / 2.2f)) < 360.dp

                                AudioBadgeRow(
                                    capability = audioCapability,
                                    qualityBadge = liveQualityBadge,
                                    onQualityBadgeClick = { showSongDetailsSheet = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                )

                                PlaybackControlsSection(
                                    currentSong = currentSong,
                                    albumLinkState = albumLinkState,
                                    playerControlStyle = playerControlStyle,
                                    viewModel = viewModel,
                                    isLiked = isLiked,
                                    repeatMode = repeatMode,
                                    accentColor = accentColor,
                                    onAccentColor = onAccentColor,
                                    fullPlayerStyle = fullPlayerStyle,
                                    colorScheme = colorScheme,
                                    onOpenArtist = onOpenArtist,
                                    onOpenAlbum = onOpenAlbum,
                                    modifier = Modifier.fillMaxWidth(),
                                    isCompactHeight = isCompactHeight,
                                    isCompactWidth = isLandscapeCompact,
                                    availableHeight = availableHeight
                                )

                                Spacer(modifier = Modifier.height(if (isCompactHeight) 12.dp else 24.dp))

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = colorScheme.surfaceContainer,
                                    tonalElevation = 2.dp
                                ) {
                                    BottomActionsSection(
                                        sleepTimerActive = sleepTimerActive,
                                        sleepTimerRemaining = sleepTimerRemaining,
                                        onQueueClick = { showQueueSheet = true },
                                        onSleepTimerClick = { showSleepTimerSheet = true },
                                        onMoreClick = { showMoreSheet = true },
                                        colorScheme = colorScheme,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = if (isLandscapeCompact) 12.dp else 16.dp,
                                                vertical = if (isLandscapeCompact) 8.dp else 12.dp
                                            ),
                                        isCompactWidth = isLandscapeCompact
                                    )
                                }
                            }
                        }
                    } else {
                        // ═══════════════════════════════════════════
                        // PORTRAIT LAYOUT (Original vertical list)
                        // ═══════════════════════════════════════════
                        val swipeScope = rememberCoroutineScope()
                        val artOffsetX = remember { Animatable(0f) }

                        // Animate new artwork sliding in when song changes
                        var lastQueueIndexForSwipe by remember { mutableIntStateOf(currentQueueIndex) }
                        var lastSongIdForSwipe by remember { mutableStateOf(currentSong?.id) }
                        var displayedSong by remember { mutableStateOf(currentSong) }

                        LaunchedEffect(currentSong?.id) {
                            val newSong = currentSong
                            val newId = newSong?.id
                            val newIndex = currentQueueIndex
                            
                            if (newId != null && newId != lastSongIdForSwipe) {
                                if (reduceMotion) {
                                    artOffsetX.snapTo(0f)
                                    displayedSong = newSong
                                } else {
                                    val isNext = if (abs(artOffsetX.value) > 100f) {
                                        artOffsetX.value < 0f
                                    } else {
                                        newIndex > lastQueueIndexForSwipe
                                    }
                                    
                                    if (abs(artOffsetX.value) < 100f) {
                                        val exitTo = if (isNext) -1000f else 1000f
                                        artOffsetX.animateTo(
                                            targetValue = exitTo,
                                            animationSpec = tween(durationMillis = 150, easing = LinearEasing)
                                        )
                                    }
                                    
                                    val entryFrom = if (isNext) 1f else -1f
                                    
                                    artOffsetX.snapTo(entryFrom * 800f)
                                    displayedSong = newSong
                                    
                                    artOffsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = 0.7f,
                                            stiffness = 500f
                                        )
                                    )
                                }
                                lastSongIdForSwipe = newId
                                lastQueueIndexForSwipe = newIndex
                                showLyricsOverlay = false
                                viewModel.clearLyricsState()
                            } else {
                                displayedSong = newSong
                                lastQueueIndexForSwipe = newIndex
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(max = Dimens.maxContentWidth)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ArtworkSection(
                                displayedSong = displayedSong,
                                thumbnailShape = thumbnailShape,
                                showDoubleTapForwardIndicator = showDoubleTapForwardIndicator,
                                showDoubleTapBackwardIndicator = showDoubleTapBackwardIndicator,
                                showLyricsOverlay = showLyricsOverlay,
                                lyricsState = lyricsState,
                                artOffsetX = artOffsetX,
                                swipeScope = swipeScope,
                                viewModel = viewModel,
                                onLyricsOverlayToggle = { showLyricsOverlay = it },
                                onDoubleTapForwardChange = { showDoubleTapForwardIndicator = it },
                                onDoubleTapBackwardChange = { showDoubleTapBackwardIndicator = it },
                                useLandscapeLayout = false,
                                modifier = Modifier.fillMaxWidth(),
                                artworkSize = artworkSize,
                                availableHeight = availableHeight
                            )

                            AudioBadgeRow(
                                capability = audioCapability,
                                qualityBadge = liveQualityBadge,
                                onQualityBadgeClick = { showSongDetailsSheet = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            )

                            Spacer(modifier = Modifier.height((artworkSpacing - 4.dp).coerceAtLeast(2.dp)))

                            PlaybackControlsSection(
                                currentSong = currentSong,
                                albumLinkState = albumLinkState,
                                playerControlStyle = playerControlStyle,
                                viewModel = viewModel,
                                isLiked = isLiked,
                                repeatMode = repeatMode,
                                accentColor = accentColor,
                                onAccentColor = onAccentColor,
                                fullPlayerStyle = fullPlayerStyle,
                                colorScheme = colorScheme,
                                onOpenArtist = onOpenArtist,
                                onOpenAlbum = onOpenAlbum,
                                modifier = Modifier.fillMaxWidth(),
                                isCompactHeight = isCompactHeight,
                                isCompactWidth = isBottomCompact,
                                availableHeight = availableHeight
                            )
                        }

                        // Bottom Action sheet trigger bar (Portrait only)
                        var dragAccumulator by remember { mutableFloatStateOf(0f) }
                        val swipeUpThresholdPx = with(density) { -32.dp.toPx() }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(screenHeightPx) {
                                    val velocityTracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            velocityTracker.resetTracking()
                                            dragAccumulator = 0f
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                                            dragAccumulator += dragAmount
                                            if (dragAccumulator < swipeUpThresholdPx) {
                                                change.consume()
                                                showQueueSheet = true
                                                dragAccumulator = 0f
                                            } else if (dragAccumulator > 0f) {
                                                change.consume()
                                                dismissScope.launch {
                                                    val current = offsetYAnim.value
                                                    offsetYAnim.snapTo(current + dragAmount)
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            val velocityY = velocityTracker.calculateVelocity().y
                                            val currentOffset = offsetYAnim.value
                                            val shouldDismiss = velocityY > 800f || currentOffset > screenHeightPx * 0.22f

                                            if (shouldDismiss && currentOffset > 0f) {
                                                animateAndDismiss()
                                            } else if (currentOffset > 0f) {
                                                dismissScope.launch {
                                                    offsetYAnim.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = spring(
                                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                                        )
                                                    )
                                                }
                                            }
                                            dragAccumulator = 0f
                                        },
                                        onDragCancel = {
                                            dragAccumulator = 0f
                                        }
                                    )
                                },
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                            color = colorScheme.surfaceContainer,
                            tonalElevation = 2.dp
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Spacer(modifier = Modifier.height(if (isBottomCompact) 6.dp else 10.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(width = 32.dp, height = 4.dp)
                                            .clip(CircleShape)
                                            .background(colorScheme.onSurfaceVariant.copy(alpha = 0.40f))
                                    )
                                    Spacer(modifier = Modifier.height(if (isBottomCompact) 4.dp else 6.dp))

                                    BottomActionsSection(
                                        sleepTimerActive = sleepTimerActive,
                                        sleepTimerRemaining = sleepTimerRemaining,
                                        onQueueClick = { showQueueSheet = true },
                                        onSleepTimerClick = { showSleepTimerSheet = true },
                                        onMoreClick = { showMoreSheet = true },
                                        colorScheme = colorScheme,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .windowInsetsPadding(WindowInsets.navigationBars)
                                            .padding(
                                                horizontal = if (isBottomCompact) 16.dp else 24.dp,
                                                vertical = if (isBottomCompact) 6.dp else 12.dp
                                            ),
                                        isCompactWidth = isBottomCompact
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // SHEETS — State is only collected when the sheet is visible.
        // This prevents 6+ active flow collectors from triggering
        // recompositions during normal playback.
        // ═══════════════════════════════════════════════════════════════

        if (showMoreSheet) {
            val isCurrentSongDownloaded by viewModel.isCurrentSongDownloaded.collectAsStateWithLifecycle()
            val currentDownloadProgress by viewModel.currentSongDownloadProgress.collectAsStateWithLifecycle()
            val downloadedFileSize by viewModel.downloadedFileSizeBytes.collectAsStateWithLifecycle()
            val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
            val duration by viewModel.duration.collectAsStateWithLifecycle()

            FullPlayerMoreSheet(
                isVisible = true,
                song = currentSong,
                isLiked = isLiked,
                isDownloaded = isCurrentSongDownloaded,
                downloadProgress = currentDownloadProgress,
                albumLinkState = albumLinkState,
                audioCapability = audioCapability,
                streamInfo = streamInfo,
                audioQualityUiState = audioQualityUiState,
                downloadedFileSizeBytes = downloadedFileSize,
                durationMs = duration,
                isPlaying = isPlaying,
                onDismiss = { showMoreSheet = false },
                onToggleLike = viewModel::toggleLike,
                onShare = {
                    val song = currentSong ?: return@FullPlayerMoreSheet
                    val shareText = "${song.title} • ${song.artist}\n${buildYouTubeMusicWatchUrl(song.id)}"
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    val chooser = Intent.createChooser(shareIntent, "Share song")
                    runCatching { context.startActivity(chooser) }
                        .onFailure {
                            Toast.makeText(context, "Unable to share song", Toast.LENGTH_SHORT).show()
                        }
                },
                onStartRadio = {
                    showMoreSheet = false
                    viewModel.refreshRecommendations()
                    showQueueSheet = true
                },
                onAddToPlaylist = {
                    showMoreSheet = false
                    showAddToPlaylistSheet = true
                },
                onEqualizer = {
                    showMoreSheet = false
                    if (!openDeviceEqualizerPanel(context)) {
                        Toast.makeText(context, "No device equalizer found", Toast.LENGTH_SHORT).show()
                    }
                },
                onSpeedAndPitch = {
                    showMoreSheet = false
                    showSpeedSheet = true
                },
                onSleepTimer = {
                    showMoreSheet = false
                    showSleepTimerSheet = true
                },
                onGoToAlbum = {
                    showMoreSheet = false
                    when (val state = albumLinkState) {
                        is AlbumLinkState.Available -> {
                            onOpenAlbum(
                                state.browseId,
                                state.name,
                                currentSong?.artist ?: "",
                                currentSong?.thumbnailUrl
                            )
                        }
                        is AlbumLinkState.Loading -> {
                            Toast.makeText(context, "Resolving album info...", Toast.LENGTH_SHORT).show()
                        }
                        is AlbumLinkState.Unavailable -> {
                            Toast.makeText(context, "Album info unavailable", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onMoreFromArtist = { artistName, artistBrowseId ->
                    showMoreSheet = false
                    if (artistName.isBlank()) {
                        Toast.makeText(context, "Artist not available", Toast.LENGTH_SHORT).show()
                    } else {
                        onOpenArtist(artistName, artistBrowseId)
                    }
                },
                onWatchOnYouTube = {
                    showMoreSheet = false
                    val songId = currentSong?.id ?: return@FullPlayerMoreSheet
                    if (!openExternalUrl(context, buildYouTubeWatchUrl(songId))) {
                        Toast.makeText(context, "Unable to open YouTube", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenInYouTubeMusic = {
                    showMoreSheet = false
                    val songId = currentSong?.id ?: return@FullPlayerMoreSheet
                    if (!openExternalUrl(context, buildYouTubeMusicWatchUrl(songId))) {
                        Toast.makeText(context, "Unable to open YouTube Music", Toast.LENGTH_SHORT).show()
                    }
                },
                onDownloadOffline = {
                    showMoreSheet = false
                    viewModel.downloadCurrentSongOffline()
                    Toast.makeText(context, "Downloading for offline...", Toast.LENGTH_SHORT).show()
                },
                onRemoveDownload = {
                    showMoreSheet = false
                    viewModel.deleteCurrentSongDownload()
                }
            )
        }

        if (showSongDetailsSheet) {
            val downloadedFileSize by viewModel.downloadedFileSizeBytes.collectAsStateWithLifecycle()
            val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
            val duration by viewModel.duration.collectAsStateWithLifecycle()

            SongDetailsSheet(
                isVisible = true,
                song = currentSong,
                streamInfo = streamInfo,
                downloadedFileSizeBytes = downloadedFileSize,
                durationMs = duration,
                onDismiss = { showSongDetailsSheet = false },
                audioCapability = audioCapability,
                isPlaying = isPlaying,
                audioQualityUiState = audioQualityUiState
            )
        }

        if (showAddToPlaylistSheet) {
            val playlists by viewModel.playlists.collectAsStateWithLifecycle()
            AddToPlaylistSheet(
                isVisible = true,
                playlists = playlists,
                fallbackArtworkUrl = currentSong?.thumbnailUrl,
                onDismiss = { showAddToPlaylistSheet = false },
                onSelectPlaylist = { playlistId, force ->
                    viewModel.addCurrentSongToPlaylist(playlistId, force)
                },
                onCreatePlaylistAndAdd = viewModel::createPlaylistAndAddCurrentSong,
                checkDuplicate = { playlistId ->
                    val songId = currentSong?.id ?: ""
                    viewModel.hasSongInPlaylist(playlistId, songId)
                }
            )
        }

        if (showSleepTimerSheet) {
            SleepTimerSheet(
                isVisible = true,
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
        }

        if (showQueueSheet) {
            val shuffleEnabled by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
            QueueSheet(
                isVisible = true,
                onDismiss = dismissQueueSheet,
                queue = queue,
                currentIndex = currentQueueIndex,
                onRemove = viewModel::removeFromQueue,
                onReorder = viewModel::reorderQueue,
                onClearQueue = viewModel::clearQueue,
                onPlay = { index ->
                    viewModel.skipToQueueItem(index)
                    dismissQueueSheet()
                },
                infiniteModeEnabled = infiniteModeEnabled,
                onToggleInfiniteMode = viewModel::setInfiniteMode,
                shuffleEnabled = shuffleEnabled,
                onToggleShuffle = viewModel::toggleShuffle
            )
        }

        if (showSpeedSheet) {
            val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
            PlaybackSpeedSheet(
                isVisible = true,
                onDismiss = { showSpeedSheet = false },
                currentSpeed = playbackSpeed,
                onSpeedChange = viewModel::setPlaybackSpeed
            )
        }
    }
}

private fun buildYouTubeWatchUrl(songId: String): String {
    return "https://www.youtube.com/watch?v=$songId"
}

private fun buildYouTubeMusicWatchUrl(songId: String): String {
    return "https://music.youtube.com/watch?v=$songId"
}

private fun buildYouTubeMusicAlbumUrl(song: Song): String {
    val albumId = song.albumId
    if (!albumId.isNullOrBlank()) {
        return "https://music.youtube.com/browse/$albumId"
    }

    val query = buildString {
        append(song.album?.takeIf { it.isNotBlank() } ?: song.title)
        if (song.artist.isNotBlank()) {
            append(" ")
            append(song.artist)
        }
    }
    val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
    return "https://music.youtube.com/search?q=$encodedQuery"
}

private fun openExternalUrl(context: Context, url: String): Boolean {
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
}

private fun openDeviceEqualizerPanel(context: Context): Boolean {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    return runCatching { context.startActivity(intent) }.isSuccess
}

@Composable
private fun FullPlayerBackground(
    artworkUrl: String?,
    enableArtworkBlur: Boolean,
    blurAlpha: Float,
    modifier: Modifier = Modifier
) {
    val playerColorScheme = LocalPlayerColorScheme.current
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.surface.luminance() < 0.5f

    // Animated dynamic background gradient stops matching current song smoothly (~800ms)
    val topStop by animateColorAsState(
        targetValue = playerColorScheme.backgroundStops.firstOrNull() ?: colorScheme.surfaceContainerLowest,
        animationSpec = tween(durationMillis = MotionTokens.DurationTheme, easing = MotionTokens.EmphasizedEasing),
        label = "playerBgTop"
    )
    val bottomStop by animateColorAsState(
        targetValue = playerColorScheme.backgroundStops.lastOrNull() ?: colorScheme.surface,
        animationSpec = tween(durationMillis = MotionTokens.DurationTheme, easing = MotionTokens.EmphasizedEasing),
        label = "playerBgBottom"
    )

    Box(
        modifier = modifier.background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    topStop.copy(alpha = if (isDark) 0.65f else 0.45f),
                    bottomStop
                )
            )
        )
    ) {
        if (enableArtworkBlur && !artworkUrl.isNullOrBlank() && blurAlpha > 0f) {
            SongThumbnail(
                artworkUrl = artworkUrl,
                shape = ThumbnailShape.SQUARE,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = blurAlpha * 0.45f }
                    .blur(50.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                targetSizePx = 100,
                highQuality = false
            )
            // Protective gradient scrim ensuring high contrast readability for song metadata and playback controls
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                if (isDark) Color.Black.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.50f)
                            )
                        )
                    )
            )
        }
    }
}


private fun formatSleepTimerDuration(duration: kotlin.time.Duration): String {
    val totalSeconds = duration.inWholeSeconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format("%d:%02d", minutes, seconds)
    }
}

@Composable
fun CenteredScrollableContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val viewportWidth = constraints.maxWidth
        val scrollState = rememberScrollState()

        Layout(
            content = content,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
        ) { measurables, childConstraints ->
            val placeable = measurables.first().measure(childConstraints)
            val contentWidth = placeable.width
            val contentHeight = placeable.height

            val layoutWidth = if (contentWidth < viewportWidth) viewportWidth else contentWidth

            layout(layoutWidth, contentHeight) {
                val x = if (contentWidth < viewportWidth) {
                    (viewportWidth - contentWidth) / 2
                } else {
                    0
                }
                placeable.placeRelative(x, 0)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Responsive Sub-composables to support Landscape and Wide layouts
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ArtworkSection(
    displayedSong: Song?,
    thumbnailShape: ThumbnailShape,
    showDoubleTapForwardIndicator: Boolean,
    showDoubleTapBackwardIndicator: Boolean,
    showLyricsOverlay: Boolean,
    lyricsState: LyricsUiState,
    artOffsetX: androidx.compose.animation.core.Animatable<Float, *>,
    swipeScope: kotlinx.coroutines.CoroutineScope,
    viewModel: PlayerViewModel,
    onLyricsOverlayToggle: (Boolean) -> Unit,
    onDoubleTapForwardChange: (Boolean) -> Unit,
    onDoubleTapBackwardChange: (Boolean) -> Unit,
    useLandscapeLayout: Boolean,
    modifier: Modifier = Modifier,
    artworkSize: androidx.compose.ui.unit.Dp = Dimens.albumArtSize,
    availableHeight: androidx.compose.ui.unit.Dp = 640.dp
) {
    Column(
        modifier = modifier.padding(horizontal = Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!useLandscapeLayout) {
            val topSpacer = when {
                availableHeight >= 800.dp -> 28.dp
                availableHeight >= 680.dp -> 20.dp
                availableHeight >= 580.dp -> 12.dp
                else -> 6.dp
            }
            Spacer(modifier = Modifier.height(topSpacer))
        }

        // Album art card with ViTune swipe physics
        Surface(
            modifier = (if (useLandscapeLayout) {
                Modifier
                    .fillMaxHeight(0.85f)
                    .aspectRatio(1f)
            } else {
                Modifier
                    .size(artworkSize)
                    .aspectRatio(1f)
            })
                .graphicsLayer {
                    translationX = artOffsetX.value
                    rotationZ = (artOffsetX.value / size.width) * 6f
                    val progress = abs(artOffsetX.value) / size.width
                    val scale = 1f - (progress * 0.08f).coerceAtMost(0.08f)
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onDragEnd = {
                            val threshold = size.width * 0.30f
                            if (abs(artOffsetX.value) > threshold) {
                                val isNext = artOffsetX.value < 0f
                                val canSkip = if (isNext) {
                                    viewModel.playerUiState.value.canSkipNext
                                } else {
                                    viewModel.playerUiState.value.canSkipPrevious
                                }

                                if (canSkip) {
                                    if (isNext) {
                                        viewModel.skipToNext()
                                    } else {
                                        viewModel.forceSkipToPrevious()
                                    }
                                    val exitTarget = sign(artOffsetX.value) * size.width * 1.5f
                                    swipeScope.launch {
                                        artOffsetX.animateTo(
                                            targetValue = exitTarget,
                                            animationSpec = spring(
                                                dampingRatio = 1f,
                                                stiffness = 650f
                                            )
                                        )
                                    }
                                } else {
                                    swipeScope.launch {
                                        artOffsetX.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = 0.6f,
                                                stiffness = 500f
                                            )
                                        )
                                    }
                                }
                            } else {
                                swipeScope.launch {
                                    artOffsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = 0.6f,
                                            stiffness = 500f
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            swipeScope.launch {
                                artOffsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.6f,
                                        stiffness = 500f
                                    )
                                )
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            swipeScope.launch {
                                artOffsetX.snapTo(artOffsetX.value + dragAmount)
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val isRightSide = offset.x > size.width / 2
                            if (isRightSide) {
                                onDoubleTapForwardChange(true)
                                val newPos = (viewModel.currentPosition.value + 10000L).coerceAtMost(viewModel.duration.value)
                                viewModel.seekToPosition(newPos)
                            } else {
                                onDoubleTapBackwardChange(true)
                                val newPos = (viewModel.currentPosition.value - 10000L).coerceAtLeast(0L)
                                viewModel.seekToPosition(newPos)
                            }
                        },
                        onTap = {
                            if (showLyricsOverlay) {
                                onLyricsOverlayToggle(false)
                            } else {
                                onLyricsOverlayToggle(true)
                                viewModel.fetchLyrics()
                            }
                        }
                    )
                },
            shape = androidx.compose.ui.graphics.RectangleShape,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            color = androidx.compose.ui.graphics.Color.Transparent
        ) {
            androidx.compose.runtime.key(displayedSong?.id) {
                Box(modifier = Modifier.fillMaxSize()) {
                    SongThumbnail(
                        artworkUrl = displayedSong?.thumbnailUrl,
                        shape = thumbnailShape,
                        shadowElevation = 0.dp,
                        backgroundColor = Color.Transparent,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = displayedSong?.let { "${it.title} by ${it.artist}" }
                            ?: "Album Art",
                        contentScale = ContentScale.Crop,
                        crossfade = true,
                        highQuality = true,
                        targetSizePx = 1080,
                        isLarge = true
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(thumbnailShape.toComposeShape(isLarge = true))
                    ) {
                        // Double Tap Forward Overlay
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showDoubleTapForwardIndicator,
                            enter = fadeIn(animationSpec = tween(150)),
                            exit = fadeOut(animationSpec = tween(250))
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.5f)
                                        .fillMaxSize()
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "FF",
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 24.sp
                                            ),
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "+10s",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        // Double Tap Backward Overlay
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showDoubleTapBackwardIndicator,
                            enter = fadeIn(animationSpec = tween(150)),
                            exit = fadeOut(animationSpec = tween(250))
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.5f)
                                        .fillMaxSize()
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "◀◀",
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 24.sp
                                            ),
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "-10s",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        LyricsOverlay(
                            lyricsState = lyricsState,
                            currentPositionFlow = viewModel.currentPosition,
                            visible = showLyricsOverlay,
                            onDismiss = { onLyricsOverlayToggle(false) },
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

    }
}

@Composable
private fun PlaybackControlsSection(
    currentSong: Song?,
    albumLinkState: AlbumLinkState,
    playerControlStyle: PlayerControlStyle,
    viewModel: PlayerViewModel,
    isLiked: Boolean,
    repeatMode: Int,
    accentColor: Color,
    onAccentColor: Color,
    fullPlayerStyle: FullPlayerStyle,
    colorScheme: androidx.compose.material3.ColorScheme,
    onOpenArtist: (String, String?) -> Unit,
    onOpenAlbum: (String, String, String, String?) -> Unit,
    modifier: Modifier = Modifier,
    isCompactHeight: Boolean = false,
    isCompactWidth: Boolean = false,
    availableHeight: androidx.compose.ui.unit.Dp = 640.dp
) {
    // Collect here — not at FullPlayerScreen level — so only this sub-tree recomposes on position ticks.
    val uiState by viewModel.playerUiState.collectAsStateWithLifecycle()
    AnimatedContent(
        targetState = playerControlStyle,
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
        },
        label = "player_control_style_crossfade",
        modifier = modifier
    ) { style ->
        when (style) {
            PlayerControlStyle.CLASSIC -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // SONG INFO (centered, Classic only)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (isCompactWidth) 12.dp else 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val songInfoTop = when {
                            availableHeight >= 800.dp -> 32.dp
                            availableHeight >= 680.dp -> 20.dp
                            availableHeight >= 580.dp -> 10.dp
                            else -> 4.dp
                        }
                        Spacer(modifier = Modifier.height(songInfoTop))
                        CenteredScrollableContainer(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = currentSong?.title ?: "",
                                style = (if (isCompactHeight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).copy(
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                ),
                                color = colorScheme.onSurface.copy(alpha = 0.90f),
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.basicMarquee()
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        val albumState = albumLinkState
                        val resolvedArtists = currentSong?.resolvedArtists.orEmpty()
                        val songTitle = currentSong?.title.orEmpty()
                        val subtitleAnnotated = remember(resolvedArtists, currentSong?.artist, albumState, songTitle) {
                            val builder = androidx.compose.ui.text.AnnotatedString.Builder()
                            if (resolvedArtists.isNotEmpty()) {
                                resolvedArtists.forEachIndexed { index, artistRef ->
                                    if (index > 0) {
                                        builder.append(", ")
                                    }
                                    builder.pushStringAnnotation("artist", "${artistRef.browseId.orEmpty()}:${artistRef.name}")
                                    builder.append(artistRef.name)
                                    builder.pop()
                                }
                            } else {
                                val fallbackName = currentSong?.artist.orEmpty()
                                builder.pushStringAnnotation("artist", ":${fallbackName}")
                                builder.append(fallbackName)
                                builder.pop()
                            }
                            if (albumState is AlbumLinkState.Available && 
                                albumState.name.isNotBlank() && 
                                !isAlbumRedundantWithSongTitle(albumState.name, songTitle)
                            ) {
                                builder.append(" • ")
                                builder.pushStringAnnotation("album", albumState.browseId)
                                builder.append(albumState.name)
                                builder.pop()
                            }
                            builder.toAnnotatedString()
                        }

                        CenteredScrollableContainer(modifier = Modifier.fillMaxWidth()) {
                            @Suppress("DEPRECATION")
                            androidx.compose.foundation.text.ClickableText(
                                text = subtitleAnnotated,
                                style = (if (isCompactHeight) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(
                                    color = colorScheme.onSurface.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center
                                ),
                                maxLines = 1,
                                onClick = { offset ->
                                    subtitleAnnotated.getStringAnnotations("artist", offset, offset)
                                        .firstOrNull()?.let { annotation ->
                                            val parts = annotation.item.split(":", limit = 2)
                                            val browseId = parts[0].takeIf { it.isNotBlank() }
                                            val name = parts.getOrElse(1) { "" }
                                            if (name.isNotBlank()) {
                                                onOpenArtist(name, browseId)
                                            }
                                            return@ClickableText
                                        }
                                    subtitleAnnotated.getStringAnnotations("album", offset, offset)
                                        .firstOrNull()?.let { annotation ->
                                            val browseId = annotation.item
                                            val name = (albumState as? AlbumLinkState.Available)?.name.orEmpty()
                                            onOpenAlbum(browseId, name, currentSong?.artist.orEmpty(), currentSong?.thumbnailUrl)
                                        }
                                }
                            )
                        }
                    }
                    val controlsTop = when {
                        availableHeight >= 800.dp -> 12.dp
                        availableHeight >= 680.dp -> 8.dp
                        availableHeight >= 580.dp -> 4.dp
                        else -> 2.dp
                    }
                    Spacer(modifier = Modifier.height(controlsTop))

                    ClassicPlayerControls(
                        state = uiState,
                        actions = viewModel,
                        isLiked = isLiked,
                        onToggleLike = { viewModel.toggleLike() },
                        repeatMode = repeatMode,
                        onToggleRepeat = { viewModel.toggleRepeatMode() },
                        accentColor = accentColor,
                        onAccentColor = onAccentColor,
                        fullPlayerStyle = fullPlayerStyle,
                        isCompactHeight = isCompactHeight,
                        isCompactWidth = isCompactWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            PlayerControlStyle.MODERN -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val modernSpacing = when {
                        availableHeight >= 800.dp -> 16.dp
                        availableHeight >= 680.dp -> 12.dp
                        availableHeight >= 580.dp -> 6.dp
                        else -> 4.dp
                    }
                    Spacer(modifier = Modifier.height(modernSpacing))
                    ModernPlayerControls(
                        state = uiState,
                        actions = viewModel,
                        accentColor = accentColor,
                        surfaceTonalColor = colorScheme.surfaceVariant,
                        onOpenArtist = onOpenArtist,
                        artistId = currentSong?.artistId,
                        artists = currentSong?.resolvedArtists ?: emptyList(),
                        albumLinkState = albumLinkState,
                        onOpenAlbum = onOpenAlbum,
                        thumbnailUrl = currentSong?.thumbnailUrl,
                        fullPlayerStyle = fullPlayerStyle,
                        isCompactHeight = isCompactHeight,
                        isCompactWidth = isCompactWidth,
                        availableHeight = availableHeight,
                        modifier = Modifier.padding(
                            start = if (isCompactWidth) 12.dp else 24.dp,
                            end = if (isCompactWidth) 12.dp else 24.dp,
                            top = if (isCompactHeight) 8.dp else 16.dp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomActionsSection(
    sleepTimerActive: Boolean,
    sleepTimerRemaining: kotlin.time.Duration?,
    onQueueClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onMoreClick: () -> Unit,
    colorScheme: androidx.compose.material3.ColorScheme,
    modifier: Modifier = Modifier,
    isCompactWidth: Boolean = false
) {
    val actionContentColor = colorScheme.onSurfaceVariant
    val inactiveTimerColor = colorScheme.onSurfaceVariant.copy(alpha = 0.70f)

    val queueInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val timerInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val moreInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    val timerBgColor by animateColorAsState(
        targetValue = if (sleepTimerActive) colorScheme.primaryContainer else Color.Transparent,
        animationSpec = tween(durationMillis = 300),
        label = "timerBgColor"
    )
    val timerContentColor by animateColorAsState(
        targetValue = if (sleepTimerActive) colorScheme.onPrimaryContainer else inactiveTimerColor,
        animationSpec = tween(durationMillis = 300),
        label = "timerContentColor"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = if (isCompactWidth) Arrangement.SpaceAround else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: Queue icon + "Next Up"
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .pressScale(queueInteractionSource, targetScale = 0.94f)
                .clickable(
                    interactionSource = queueInteractionSource,
                    indication = androidx.compose.material3.ripple(),
                    onClick = onQueueClick
                )
                .padding(horizontal = if (isCompactWidth) 8.dp else 12.dp, vertical = if (isCompactWidth) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Queue",
                modifier = Modifier.size(22.dp),
                tint = actionContentColor
            )
            if (!isCompactWidth) {
                Text(
                    text = "Next Up",
                    style = MaterialTheme.typography.labelLarge,
                    color = actionContentColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Middle: Sleep Timer Countdown / Indicator
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(timerBgColor)
                .pressScale(timerInteractionSource, targetScale = 0.94f)
                .clickable(
                    interactionSource = timerInteractionSource,
                    indication = androidx.compose.material3.ripple(),
                    onClick = onSleepTimerClick
                )
                .padding(horizontal = if (isCompactWidth) 8.dp else 12.dp, vertical = if (isCompactWidth) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Bedtime,
                contentDescription = "Sleep Timer",
                modifier = Modifier.size(20.dp),
                tint = timerContentColor
            )
            if (!isCompactWidth) {
                Text(
                    text = if (sleepTimerActive && sleepTimerRemaining != null) {
                        formatSleepTimerDuration(sleepTimerRemaining)
                    } else {
                        "Sleep"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = timerContentColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Right side: 3 dots + "Option"
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .pressScale(moreInteractionSource, targetScale = 0.94f)
                .clickable(
                    interactionSource = moreInteractionSource,
                    indication = androidx.compose.material3.ripple(),
                    onClick = onMoreClick
                )
                .padding(horizontal = if (isCompactWidth) 8.dp else 12.dp, vertical = if (isCompactWidth) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                modifier = Modifier.size(22.dp),
                tint = actionContentColor
            )
            if (!isCompactWidth) {
                Text(
                    text = "Option",
                    style = MaterialTheme.typography.labelLarge,
                    color = actionContentColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
