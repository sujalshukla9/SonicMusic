package com.sonicmusic.app.presentation.ui.player

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
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
import com.sonicmusic.app.presentation.ui.player.PlayerUiState
import com.sonicmusic.app.presentation.ui.player.PlayerActions
import com.sonicmusic.app.presentation.ui.player.ClassicPlayerControls
import com.sonicmusic.app.presentation.ui.player.ModernPlayerControls
import com.sonicmusic.app.presentation.ui.theme.toComposeShape
import com.sonicmusic.app.presentation.ui.theme.toRoundedPolygon
import com.sonicmusic.app.presentation.ui.theme.MorphComposeShape
import androidx.graphics.shapes.Morph
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.ui.components.AudioBadgeRow
import com.sonicmusic.app.presentation.viewmodel.LyricsUiState
import com.sonicmusic.app.presentation.viewmodel.PlayerViewModel
import com.sonicmusic.app.presentation.viewmodel.AlbumLinkState
import com.sonicmusic.app.presentation.ui.LocalWindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import com.sonicmusic.app.player.audio.AudioCapabilityDetector.AudioCapability
import com.sonicmusic.app.presentation.ui.theme.Dimens
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
    val currentSong by viewModel.currentSong.collectAsStateWithLifecycle()
    val audioCapability by viewModel.audioCapability.collectAsStateWithLifecycle()
    val fullPlayerStyle by viewModel.fullPlayerStyle.collectAsStateWithLifecycle()
    val dynamicColorsEnabled by viewModel.dynamicColorsEnabled.collectAsStateWithLifecycle()
    val dynamicColorIntensity by viewModel.dynamicColorIntensity.collectAsStateWithLifecycle()
    val albumArtBlurEnabled by viewModel.albumArtBlurEnabled.collectAsStateWithLifecycle()
    val thumbnailShape by viewModel.thumbnailShape.collectAsStateWithLifecycle()
    val playerControlStyle by viewModel.playerControlStyle.collectAsStateWithLifecycle()
    
    // Animating shape morph transitions for overlays
    var previousShape by remember { mutableStateOf(thumbnailShape) }
    var targetShape by remember { mutableStateOf(thumbnailShape) }
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(thumbnailShape) {
        if (thumbnailShape != targetShape) {
            previousShape = targetShape
            targetShape = thumbnailShape
            animProgress.snapTo(0f)
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
            )
            previousShape = thumbnailShape // Reset morph state to prevent infinite rebuilds
        }
    }

    val morphingShape = remember(previousShape, targetShape, animProgress.value) {
        if (previousShape == targetShape) {
            targetShape.toComposeShape()
        } else {
            val startPoly = previousShape.toRoundedPolygon()
            val endPoly = targetShape.toRoundedPolygon()
            MorphComposeShape(Morph(startPoly, endPoly), animProgress.value)
        }
    }

    val targetPadding = when (targetShape) {
        ThumbnailShape.CLOVER, ThumbnailShape.BURST, ThumbnailShape.SCALLOP -> 4.dp
        ThumbnailShape.CIRCLE, ThumbnailShape.COOKIE, ThumbnailShape.ARCH -> 2.dp
        else -> 0.dp
    }
    val animatedPadding by animateDpAsState(
        targetValue = targetPadding,
        animationSpec = tween(durationMillis = 500),
        label = "thumbnailPadding"
    )


    // Core Playback states collected at the top level
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val currentQueueIndex by viewModel.currentQueueIndex.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val infiniteModeEnabled by viewModel.infiniteModeEnabled.collectAsStateWithLifecycle()
    val isLiked by viewModel.isLiked.collectAsStateWithLifecycle()
    val uiState by viewModel.playerUiState.collectAsStateWithLifecycle()

    // Sheet states — only collected when sheets are open
    val sleepTimerRemaining by viewModel.sleepTimerRemaining.collectAsStateWithLifecycle()
    val sleepTimerActive by viewModel.sleepTimerActive.collectAsStateWithLifecycle()
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    var showSpeedSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }

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

    // Handle system back button to dismiss full player instead of closing app
    BackHandler {
        onDismiss()
    }

    // Swipe to dismiss
    val offsetYAnim = remember { Animatable(0f) }
    val dismissScope = rememberCoroutineScope()

    val baseColorScheme = MaterialTheme.colorScheme
    val artworkPalette = rememberPlayerArtworkPalette(
        artworkUrl = currentSong?.thumbnailUrl,
        fallbackColorScheme = baseColorScheme,
        enabled = dynamicColorsEnabled,
        intensity = dynamicColorIntensity / 100f
    )

    MaterialTheme(colorScheme = artworkPalette.colorScheme) {
        val colorScheme = MaterialTheme.colorScheme
        val accentColor by animateColorAsState(
            targetValue = artworkPalette.accent,
            animationSpec = tween(durationMillis = 1000),
            label = "full_player_accent"
        )
        val onAccentColor by animateColorAsState(
            targetValue = artworkPalette.onAccent,
            animationSpec = tween(durationMillis = 1000),
            label = "full_player_on_accent"
        )
        val backgroundTopTint by animateColorAsState(
            targetValue = artworkPalette.containerSoft,
            animationSpec = tween(durationMillis = 1000),
            label = "full_player_bg_top_tint"
        )
        val backgroundMidTint by animateColorAsState(
            targetValue = artworkPalette.container,
            animationSpec = tween(durationMillis = 1000),
            label = "full_player_bg_mid_tint"
        )

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colorScheme.surface
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = offsetYAnim.value }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                if (dragAmount > 0f) {
                                    dismissScope.launch {
                                        offsetYAnim.snapTo(offsetYAnim.value + dragAmount)
                                    }
                                }
                            },
                            onDragEnd = {
                                if (offsetYAnim.value > 200f) onDismiss()
                                dismissScope.launch {
                                    offsetYAnim.animateTo(0f, tween(durationMillis = 100))
                                }
                            }
                        )
                    }
            ) {
                FullPlayerBackground(
                    artworkUrl = currentSong?.thumbnailUrl,
                    enableArtworkBlur = albumArtBlurEnabled,
                    topColor = backgroundTopTint,
                    midColor = backgroundMidTint,
                    bottomColor = colorScheme.surface,
                    modifier = Modifier.fillMaxSize()
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // No internet banner
                    val isOnline = com.sonicmusic.app.presentation.ui.components.LocalIsOnline.current
                    com.sonicmusic.app.presentation.ui.components.NoInternetBanner(isVisible = !isOnline)

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
                                    morphingShape = morphingShape,
                                    animatedPadding = animatedPadding,
                                    showDoubleTapForwardIndicator = showDoubleTapForwardIndicator,
                                    showDoubleTapBackwardIndicator = showDoubleTapBackwardIndicator,
                                    showLyricsOverlay = showLyricsOverlay,
                                    lyricsState = lyricsState,
                                    artOffsetX = artOffsetX,
                                    swipeScope = swipeScope,
                                    viewModel = viewModel,
                                    audioCapability = audioCapability,
                                    onDismiss = onDismiss,
                                    onOpenArtist = onOpenArtist,
                                    onOpenAlbum = onOpenAlbum,
                                    onLyricsOverlayToggle = { showLyricsOverlay = it },
                                    onDoubleTapForwardChange = { showDoubleTapForwardIndicator = it },
                                    onDoubleTapBackwardChange = { showDoubleTapBackwardIndicator = it },
                                    useLandscapeLayout = true,
                                    colorScheme = colorScheme,
                                    modifier = Modifier.fillMaxSize()
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
                                PlaybackControlsSection(
                                    currentSong = currentSong,
                                    albumLinkState = albumLinkState,
                                    playerControlStyle = playerControlStyle,
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    isLiked = isLiked,
                                    repeatMode = repeatMode,
                                    accentColor = accentColor,
                                    onAccentColor = onAccentColor,
                                    fullPlayerStyle = fullPlayerStyle,
                                    colorScheme = colorScheme,
                                    onOpenArtist = onOpenArtist,
                                    onOpenAlbum = onOpenAlbum,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = colorScheme.surfaceContainerHigh,
                                    tonalElevation = 4.dp
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
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
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
                                morphingShape = morphingShape,
                                animatedPadding = animatedPadding,
                                showDoubleTapForwardIndicator = showDoubleTapForwardIndicator,
                                showDoubleTapBackwardIndicator = showDoubleTapBackwardIndicator,
                                showLyricsOverlay = showLyricsOverlay,
                                lyricsState = lyricsState,
                                artOffsetX = artOffsetX,
                                swipeScope = swipeScope,
                                viewModel = viewModel,
                                audioCapability = audioCapability,
                                onDismiss = onDismiss,
                                onOpenArtist = onOpenArtist,
                                onOpenAlbum = onOpenAlbum,
                                onLyricsOverlayToggle = { showLyricsOverlay = it },
                                onDoubleTapForwardChange = { showDoubleTapForwardIndicator = it },
                                onDoubleTapBackwardChange = { showDoubleTapBackwardIndicator = it },
                                useLandscapeLayout = false,
                                colorScheme = colorScheme,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(Dimens.playerArtworkSpacing))

                            PlaybackControlsSection(
                                currentSong = currentSong,
                                albumLinkState = albumLinkState,
                                playerControlStyle = playerControlStyle,
                                uiState = uiState,
                                viewModel = viewModel,
                                isLiked = isLiked,
                                repeatMode = repeatMode,
                                accentColor = accentColor,
                                onAccentColor = onAccentColor,
                                fullPlayerStyle = fullPlayerStyle,
                                colorScheme = colorScheme,
                                onOpenArtist = onOpenArtist,
                                onOpenAlbum = onOpenAlbum,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Bottom Action sheet trigger bar (Portrait only)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                            color = colorScheme.surfaceContainerHigh,
                            tonalElevation = 6.dp
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
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
                                        .windowInsetsPadding(WindowInsets.navigationBars)
                                        .padding(horizontal = 24.dp, vertical = 20.dp)
                                )
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
            FullPlayerMoreSheet(
                isVisible = true,
                song = currentSong,
                isLiked = isLiked,
                isDownloaded = isCurrentSongDownloaded,
                downloadProgress = currentDownloadProgress,
                albumLinkState = albumLinkState,
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
                onMoreFromArtist = { artistName ->
                    showMoreSheet = false
                    if (artistName.isBlank()) {
                        Toast.makeText(context, "Artist not available", Toast.LENGTH_SHORT).show()
                    } else {
                        onOpenArtist(artistName, null)
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
    topColor: Color,
    midColor: Color,
    bottomColor: Color,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        if (enableArtworkBlur && !artworkUrl.isNullOrBlank()) {
            SongThumbnail(
                artworkUrl = artworkUrl,
                shape = ThumbnailShape.SQUARE,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(68.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                targetSizePx = 100,
                highQuality = false
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            topColor.copy(alpha = if (enableArtworkBlur) 0.54f else 0.40f),
                            midColor.copy(alpha = if (enableArtworkBlur) 0.36f else 0.22f),
                            bottomColor
                        )
                    )
                )
        )
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
    morphingShape: androidx.compose.ui.graphics.Shape,
    animatedPadding: androidx.compose.ui.unit.Dp,
    showDoubleTapForwardIndicator: Boolean,
    showDoubleTapBackwardIndicator: Boolean,
    showLyricsOverlay: Boolean,
    lyricsState: LyricsUiState,
    artOffsetX: androidx.compose.animation.core.Animatable<Float, *>,
    swipeScope: kotlinx.coroutines.CoroutineScope,
    viewModel: PlayerViewModel,
    audioCapability: AudioCapability,
    onDismiss: () -> Unit,
    onOpenArtist: (String, String?) -> Unit,
    onOpenAlbum: (String, String, String, String?) -> Unit,
    onLyricsOverlayToggle: (Boolean) -> Unit,
    onDoubleTapForwardChange: (Boolean) -> Unit,
    onDoubleTapBackwardChange: (Boolean) -> Unit,
    useLandscapeLayout: Boolean,
    colorScheme: androidx.compose.material3.ColorScheme,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!useLandscapeLayout) {
            Spacer(modifier = Modifier.height(Dimens.playerTopSpacing))
        }

        // Album art card with ViTune swipe physics
        Surface(
            modifier = (if (useLandscapeLayout) {
                Modifier
                    .fillMaxHeight(0.85f)
                    .aspectRatio(1f)
            } else {
                Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = Dimens.albumArtSize)
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
                                if (isNext) {
                                    viewModel.skipToNext()
                                } else {
                                    viewModel.skipToPrevious()
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
                        shadowElevation = 12.dp,
                        backgroundColor = colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = "Album Art",
                        contentScale = ContentScale.Crop,
                        crossfade = true,
                        highQuality = true,
                        targetSizePx = 1080
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(animatedPadding)
                            .clip(morphingShape)
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
                                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f))
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "▶▶",
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
                                                colors = listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent)
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

        Spacer(modifier = Modifier.height(16.dp))
        
        AudioBadgeRow(capability = audioCapability)
    }
}

@Composable
private fun PlaybackControlsSection(
    currentSong: Song?,
    albumLinkState: AlbumLinkState,
    playerControlStyle: PlayerControlStyle,
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    isLiked: Boolean,
    repeatMode: Int,
    accentColor: Color,
    onAccentColor: Color,
    fullPlayerStyle: FullPlayerStyle,
    colorScheme: androidx.compose.material3.ColorScheme,
    onOpenArtist: (String, String?) -> Unit,
    onOpenAlbum: (String, String, String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
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
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CenteredScrollableContainer(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = currentSong?.title ?: "",
                                style = if (LocalWindowSizeClass.current.widthSizeClass == androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact) {
                                    MaterialTheme.typography.titleLarge
                                } else {
                                    MaterialTheme.typography.headlineSmall
                                },
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.basicMarquee()
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        val artistText = currentSong?.artist ?: ""
                        val albumState = albumLinkState
                        val subtitleAnnotated = remember(artistText, albumState) {
                            val regex = Regex("(,\\s*|\\s+&\\s+|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+x\\s+|\\s+and\\s+|\\s*/\\s*|\\s*;\\s*|\\s*\\|\\s*)", RegexOption.IGNORE_CASE)
                            val builder = androidx.compose.ui.text.AnnotatedString.Builder()
                            var lastEnd = 0
                            var artistIndex = 0
                            regex.findAll(artistText).forEach { match ->
                                if (match.range.first > lastEnd) {
                                    val name = artistText.substring(lastEnd, match.range.first)
                                    builder.pushStringAnnotation("artist", "${artistIndex}:${name.trim()}")
                                    builder.append(name)
                                    builder.pop()
                                    artistIndex++
                                }
                                builder.append(match.value)
                                lastEnd = match.range.last + 1
                            }
                            if (lastEnd < artistText.length) {
                                val name = artistText.substring(lastEnd)
                                builder.pushStringAnnotation("artist", "${artistIndex}:${name.trim()}")
                                builder.append(name)
                                builder.pop()
                            }
                            if (albumState is AlbumLinkState.Available) {
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
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                ),
                                maxLines = 1,
                                onClick = { offset ->
                                    subtitleAnnotated.getStringAnnotations("artist", offset, offset)
                                        .firstOrNull()?.let { annotation ->
                                            val parts = annotation.item.split(":", limit = 2)
                                            val idx = parts[0].toIntOrNull() ?: 0
                                            val name = parts.getOrElse(1) { "" }
                                            if (name.isNotBlank()) {
                                                val artistId = currentSong?.artistId?.split(",")?.getOrNull(idx)?.trim()?.takeIf { it.isNotBlank() }
                                                onOpenArtist(name, artistId)
                                            }
                                            return@ClickableText
                                        }
                                    subtitleAnnotated.getStringAnnotations("album", offset, offset)
                                        .firstOrNull()?.let { annotation ->
                                            val browseId = annotation.item
                                            val name = (albumState as? AlbumLinkState.Available)?.name.orEmpty()
                                            onOpenAlbum(browseId, name, artistText, currentSong?.thumbnailUrl)
                                        }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))

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
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            PlayerControlStyle.MODERN -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    ModernPlayerControls(
                        state = uiState,
                        actions = viewModel,
                        accentColor = accentColor,
                        surfaceTonalColor = colorScheme.surfaceVariant,
                        onOpenArtist = onOpenArtist,
                        artistId = currentSong?.artistId,
                        albumLinkState = albumLinkState,
                        onOpenAlbum = onOpenAlbum,
                        thumbnailUrl = currentSong?.thumbnailUrl,
                        fullPlayerStyle = fullPlayerStyle,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp)
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: Queue icon + "Next Up"
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onQueueClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Queue",
                modifier = Modifier.size(22.dp),
                tint = colorScheme.onSurfaceVariant
            )
            Text(
                text = "Next Up",
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }

        // Middle: Sleep Timer Countdown / Indicator
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onSleepTimerClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Bedtime,
                contentDescription = "Sleep Timer",
                modifier = Modifier.size(20.dp),
                tint = if (sleepTimerActive) colorScheme.primary else colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = if (sleepTimerActive && sleepTimerRemaining != null) {
                    formatSleepTimerDuration(sleepTimerRemaining)
                } else {
                    "Sleep"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (sleepTimerActive) colorScheme.primary else colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontWeight = FontWeight.Medium
            )
        }

        // Right side: 3 dots + "Option"
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onMoreClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                modifier = Modifier.size(22.dp),
                tint = colorScheme.onSurfaceVariant
            )
            Text(
                text = "Option",
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
