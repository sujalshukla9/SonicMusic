package com.sonicmusic.app.features.player.presentation

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import com.sonicmusic.app.theme.sharedPlayerArtwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import com.sonicmusic.app.theme.pressScale
import com.sonicmusic.app.theme.shouldReduceMotion
import com.sonicmusic.app.theme.MotionTokens
import com.sonicmusic.app.theme.contentMotion
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import com.sonicmusic.app.R
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.domain.model.PlayerControlStyle
import androidx.compose.ui.graphics.luminance
import com.sonicmusic.app.shared.components.AudioBadgeRow
import androidx.compose.animation.core.snap
import com.sonicmusic.app.shared.components.SongThumbnail
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
import java.net.URLEncoder
import kotlin.math.abs

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
    sharedArtworkTransition: Boolean = false,
    isVisible: Boolean = true,
    onSlideDismissStarted: () -> Unit = {},
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

    var showSpeedSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }
    var showSongDetailsSheet by remember { mutableStateOf(false) }

    val streamInfo by viewModel.currentStreamInfo.collectAsStateWithLifecycle()
    val audioQualityUiState by viewModel.audioQualityUiState.collectAsStateWithLifecycle()
    val audioCapability by viewModel.audioCapability.collectAsStateWithLifecycle()

    // Lyrics overlay state
    var showLyricsOverlay by remember { mutableStateOf(false) }
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val albumLinkState by viewModel.albumLinkState.collectAsStateWithLifecycle()

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

    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismiss)
    val motion = remember(reduceMotion) {
        PlayerPanelMotion(scope, reduceMotion, sharedArtworkTransition) { latestDismiss() }
    }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    val height = viewportHeightPx.takeIf { it > 0f } ?: screenHeightPx.coerceAtLeast(1f)
    val queueVisible by remember(motion) {
        derivedStateOf { motion.offset < 0f || motion.target == PlayerPanel.Queue }
    }
    val queueActive = motion.target == PlayerPanel.Queue
    val minimizing = motion.target == PlayerPanel.Minimized
    LaunchedEffect(motion, isVisible) {
        if (isVisible) motion.settle(PlayerPanel.Player)
    }

    fun openQueue() {
        showLyricsOverlay = false
        motion.settle(PlayerPanel.Queue)
    }
    fun closeQueue() = motion.settle(PlayerPanel.Player)
    fun slideAndDismiss(velocity: Float = 0f) {
        onSlideDismissStarted()
        motion.settle(PlayerPanel.Minimized, velocity)
    }

    val playerDrag = rememberDraggableState { motion.drag(it / height, PlayerPanel.Player) }
    val queueDrag = rememberDraggableState { motion.drag(it / height, PlayerPanel.Queue) }
    val queueScroll = remember(motion, height) {
        object : NestedScrollConnection {
            private var pulling = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!pulling || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                return Offset(0f, motion.drag(available.y / height, PlayerPanel.Queue) * height)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                pulling = true
                return Offset(0f, motion.drag(available.y / height, PlayerPanel.Queue) * height)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!pulling) return Velocity.Zero
                pulling = false
                val velocity = available.y / height
                motion.settle(settlePlayerPanel(PlayerPanel.Queue, motion.offset, velocity), velocity)
                return Velocity(0f, available.y)
            }
        }
    }

    PredictiveBackHandler(enabled = isVisible && !minimizing) { progress ->
        val fromQueue = queueVisible
        val fromLyrics = showLyricsOverlay && !fromQueue
        val initialOffset = motion.offset
        var followedGesture = false
        try {
            progress.collect { event ->
                if (!reduceMotion && !fromLyrics) {
                    followedGesture = true
                    val displacement = if (fromQueue) -initialOffset else 0.35f
                    motion.seek(initialOffset + event.progress * displacement)
                }
            }
            when {
                fromQueue -> closeQueue()
                fromLyrics -> showLyricsOverlay = false
                sharedArtworkTransition && !followedGesture -> onDismiss()
                else -> slideAndDismiss()
            }
        } catch (cancelled: CancellationException) {
            if (!fromLyrics) motion.settle(if (fromQueue) PlayerPanel.Queue else PlayerPanel.Player)
            throw cancelled
        }
    }

    LaunchedEffect(currentSong?.id, showLyricsOverlay) {
        if (showLyricsOverlay) viewModel.fetchLyrics()
    }

    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height.toFloat() }
            .clipToBounds()
            .drawBehind {
                val progress = motion.offset.coerceIn(0f, 1f)
                drawRect(Color.Black.copy(alpha = 0.55f * (1f - progress)))
            }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    state = playerDrag,
                    orientation = Orientation.Vertical,
                    enabled = isVisible && !showLyricsOverlay && !queueActive && !minimizing,
                    onDragStopped = { pixelsPerSecond ->
                        val velocity = pixelsPerSecond / height
                        val destination = settlePlayerPanel(PlayerPanel.Player, motion.offset, velocity)
                        if (destination == PlayerPanel.Minimized) slideAndDismiss(velocity)
                        else motion.settle(destination, velocity)
                    }
                )
                .graphicsLayer {
                    val progress = motion.offset.coerceIn(0f, 1f)
                    val queueProgress = (-motion.offset).coerceIn(0f, 1f)
                    translationY = motion.offset.coerceAtLeast(0f) * height
                    transformOrigin = if (progress > 0f) TransformOrigin(0.5f, 0f) else TransformOrigin.Center
                    scaleX = if (reduceMotion) 1f else 1f - progress * 0.06f - queueProgress * 0.035f
                    scaleY = scaleX
                }
                .then(if (queueActive) Modifier.clearAndSetSemantics { invisibleToUser() } else Modifier),
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = colorScheme.surface
        ) {
            val queueCoveringPlayer by remember(motion) {
                derivedStateOf { motion.offset < 0f }
            }
            val blurAlpha = animateFloatAsState(
                targetValue = if (queueCoveringPlayer) 0f else 1f,
                animationSpec = if (reduceMotion) snap() else MotionTokens.standardTween(),
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
                                ArtworkSection(
                                    currentSong = currentSong,
                                    thumbnailShape = thumbnailShape,
                                    showLyricsOverlay = showLyricsOverlay,
                                    lyricsState = lyricsState,
                                    queue = queue,
                                    currentQueueIndex = currentQueueIndex,
                                    repeatMode = repeatMode,
                                    viewModel = viewModel,
                                    onLyricsOverlayToggle = { showLyricsOverlay = it },
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

                                AudioBadgeRow(capability = audioCapability)
                                StreamQualityLabel(streamInfo?.compactQualityLabel.orEmpty())

                                PlaybackControlsSection(
                                    currentSong = currentSong,
                                    albumLinkState = albumLinkState,
                                    playerControlStyle = playerControlStyle,
                                    viewModel = viewModel,
                                    isLiked = isLiked,
                                    repeatMode = repeatMode,
                                    fullPlayerStyle = fullPlayerStyle,
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
                                        onQueueClick = ::openQueue,
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
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(max = Dimens.maxContentWidth)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ArtworkSection(
                                currentSong = currentSong,
                                thumbnailShape = thumbnailShape,
                                showLyricsOverlay = showLyricsOverlay,
                                lyricsState = lyricsState,
                                queue = queue,
                                currentQueueIndex = currentQueueIndex,
                                repeatMode = repeatMode,
                                viewModel = viewModel,
                                onLyricsOverlayToggle = { showLyricsOverlay = it },
                                useLandscapeLayout = false,
                                modifier = Modifier.fillMaxWidth(),
                                artworkSize = artworkSize,
                                availableHeight = availableHeight
                            )

                            AudioBadgeRow(capability = audioCapability)
                            Box(
                                modifier = Modifier.fillMaxWidth().height(artworkSpacing - 4.dp),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                StreamQualityLabel(streamInfo?.compactQualityLabel.orEmpty())
                            }

                            PlaybackControlsSection(
                                currentSong = currentSong,
                                albumLinkState = albumLinkState,
                                playerControlStyle = playerControlStyle,
                                viewModel = viewModel,
                                isLiked = isLiked,
                                repeatMode = repeatMode,
                                fullPlayerStyle = fullPlayerStyle,
                                onOpenArtist = onOpenArtist,
                                onOpenAlbum = onOpenAlbum,
                                modifier = Modifier.fillMaxWidth(),
                                isCompactHeight = isCompactHeight,
                                isCompactWidth = isBottomCompact,
                                availableHeight = availableHeight
                            )
                        }

                        // Bottom Action sheet trigger bar (Portrait only)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
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
                                        onQueueClick = ::openQueue,
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
                    viewModel.startRadio()
                    openQueue()
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

        if (queueVisible) {
            val shuffleEnabled by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
            QueueSheet(
                onDismiss = ::closeQueue,
                queue = queue,
                currentIndex = currentQueueIndex,
                onRemove = viewModel::removeFromQueue,
                onReorder = viewModel::reorderQueue,
                onClearQueue = viewModel::clearQueue,
                onPlay = { index ->
                    viewModel.skipToQueueItem(index)
                    closeQueue()
                },
                infiniteModeEnabled = infiniteModeEnabled,
                onToggleInfiniteMode = viewModel::setInfiniteMode,
                shuffleEnabled = shuffleEnabled,
                onToggleShuffle = viewModel::toggleShuffle,
                modifier = Modifier.graphicsLayer {
                    translationY = (1f + motion.offset.coerceAtMost(0f)) * height
                }.nestedScroll(queueScroll),
                handleModifier = Modifier.draggable(
                    state = queueDrag,
                    orientation = Orientation.Vertical,
                    onDragStopped = { pixelsPerSecond ->
                        val velocity = pixelsPerSecond / height
                        motion.settle(settlePlayerPanel(PlayerPanel.Queue, motion.offset, velocity), velocity)
                    }
                )
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
    blurAlpha: State<Float>,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.surface.luminance() < 0.5f
    val topStop = colorScheme.surfaceContainerHigh
    val bottomStop = colorScheme.surface
    val background = remember(topStop, bottomStop) {
        Brush.verticalGradient(colors = listOf(topStop, bottomStop))
    }
    val showBlur by remember(blurAlpha) { derivedStateOf { blurAlpha.value > 0f } }

    Box(
        modifier = modifier.background(background)
    ) {
        if (enableArtworkBlur && !artworkUrl.isNullOrBlank() && showBlur) {
            SongThumbnail(
                artworkUrl = artworkUrl,
                shape = ThumbnailShape.SQUARE,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = blurAlpha.value * 0.45f }
                    .blur(50.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                targetSizePx = 100,
                highQuality = false
            )
            // Protective gradient scrim ensuring high contrast readability for song metadata and playback controls
            val scrim = remember(isDark) {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        (if (isDark) Color.Black else Color.White).copy(alpha = 0.50f)
                    )
                )
            }
            Box(modifier = Modifier.fillMaxSize().background(scrim))
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
    currentSong: Song?,
    thumbnailShape: ThumbnailShape,
    showLyricsOverlay: Boolean,
    lyricsState: LyricsUiState,
    queue: List<Song>,
    currentQueueIndex: Int,
    repeatMode: Int,
    viewModel: PlayerViewModel,
    onLyricsOverlayToggle: (Boolean) -> Unit,
    useLandscapeLayout: Boolean,
    modifier: Modifier = Modifier,
    artworkSize: androidx.compose.ui.unit.Dp = Dimens.albumArtSize,
    availableHeight: androidx.compose.ui.unit.Dp = 640.dp
) {
    val song = currentSong ?: return
    val queueIndex = if (queue.getOrNull(currentQueueIndex)?.id == song.id) currentQueueIndex
        else queue.indexOfFirst { it.id == song.id }
    val songs = if (queueIndex < 0) listOf(song) else queue
    val wraps = repeatMode == Player.REPEAT_MODE_ALL && songs.size > 1
    val artworkPages = remember(songs, wraps) {
        if (wraps) listOf(songs.last()) + songs + songs.first() else songs
    }
    val page = queueIndex.coerceAtLeast(0) + if (wraps) 1 else 0
    val pager = rememberPagerState(initialPage = page, pageCount = { artworkPages.size })
    val reduceMotion = shouldReduceMotion()
    val latestPage by rememberUpdatedState(page)
    val latestSongs by rememberUpdatedState(songs)
    val latestQueueIndex by rememberUpdatedState(queueIndex)
    val latestWraps by rememberUpdatedState(wraps)
    val showLyrics by rememberUpdatedState(onLyricsOverlayToggle)
    var userSwipe by remember { mutableStateOf(false) }
    var seekFeedback by remember(song.id, showLyricsOverlay) { mutableStateOf<ArtworkSeekFeedback?>(null) }
    val seekBackwardLabel = stringResource(R.string.player_seek_backward)
    val seekForwardLabel = stringResource(R.string.player_seek_forward)

    fun seek(direction: ArtworkSeekDirection) {
        viewModel.seekToPosition(viewModel.currentPosition.value + direction.deltaMs)
        seekFeedback = ArtworkSeekFeedback(direction, (seekFeedback?.sequence ?: 0) + 1)
    }

    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(MotionTokens.SeekFeedbackHold.toLong())
            seekFeedback = null
        }
    }

    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userSwipe = true
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.isScrollInProgress to pager.settledPage }.collect { (scrolling, settled) ->
            if (!scrolling && userSwipe) {
                userSwipe = false
                val selectedIndex = if (latestWraps) Math.floorMod(settled - 1, latestSongs.size) else settled
                if (latestQueueIndex >= 0 && selectedIndex != latestQueueIndex && selectedIndex in latestSongs.indices) {
                    val selectedSong = latestSongs[selectedIndex].id
                    viewModel.skipToQueueItem(selectedIndex)
                    val confirmed = withTimeoutOrNull(1_750) {
                        snapshotFlow { latestQueueIndex == selectedIndex && latestSongs.getOrNull(selectedIndex)?.id == selectedSong }
                            .first { it }
                    }
                    if (confirmed == null && !pager.isScrollInProgress && !userSwipe) {
                        if (reduceMotion) pager.scrollToPage(latestPage)
                        else pager.animateScrollToPage(latestPage, animationSpec = MotionTokens.standardTween())
                    }
                }
            }
        }
    }
    LaunchedEffect(page, song.id, artworkPages.size, reduceMotion) {
        if (!userSwipe && pager.settledPage != page) {
            if (reduceMotion || abs(pager.currentPage - page) > 1) pager.scrollToPage(page)
            else pager.animateScrollToPage(page, animationSpec = MotionTokens.standardTween())
        }
    }

    Column(
        modifier = modifier.padding(horizontal = Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!useLandscapeLayout) {
            Spacer(Modifier.height(when {
                availableHeight >= 800.dp -> 28.dp
                availableHeight >= 680.dp -> 20.dp
                availableHeight >= 580.dp -> 12.dp
                else -> 6.dp
            }))
        }
        Box(
            modifier = (if (useLandscapeLayout) Modifier.fillMaxHeight(0.85f).aspectRatio(1f)
                else Modifier.size(artworkSize).aspectRatio(1f)).clipToBounds()
        ) {
            val openLyrics = { showLyrics(true) }
            val tapModifier = if (showLyricsOverlay) Modifier else Modifier
                    .semantics {
                        role = Role.Button
                        onClick(label = "Show lyrics") { openLyrics(); true }
                        customActions = listOf(
                            CustomAccessibilityAction(seekBackwardLabel) { seek(ArtworkSeekDirection.Backward); true },
                            CustomAccessibilityAction(seekForwardLabel) { seek(ArtworkSeekDirection.Forward); true }
                        )
                    }
                    .pointerInput(viewModel, song.id) {
                        detectTapGestures(
                            onTap = { openLyrics() },
                            onDoubleTap = { tap ->
                                seek(if (tap.x < size.width * 0.5f) ArtworkSeekDirection.Backward else ArtworkSeekDirection.Forward)
                            }
                        )
                    }
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().then(tapModifier),
                userScrollEnabled = !showLyricsOverlay && queueIndex >= 0,
                beyondViewportPageCount = if (songs.size > 1) 1 else 0,
                pageSpacing = 14.dp,
                key = { index -> artworkPages[index].id + ":" + index }
            ) { index ->
                val track = artworkPages[index]
                SongThumbnail(
                    artworkUrl = track.thumbnailUrl,
                    shape = thumbnailShape,
                    modifier = Modifier
                        .fillMaxSize()
                        .sharedPlayerArtwork(track.id.takeIf { index == page })
                        .then(if (reduceMotion) Modifier else Modifier.graphicsLayer {
                            val distance = abs(pager.currentPage - index + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                            scaleX = 1f - distance * 0.065f
                            scaleY = scaleX
                            alpha = 1f - distance * 0.22f
                        }),
                    contentDescription = track.title + " by " + track.artist,
                    highQuality = true,
                    targetSizePx = 900,
                    isLarge = true
                )
            }
            ArtworkSeekIndicator(
                feedback = seekFeedback?.takeIf { it.direction == ArtworkSeekDirection.Backward },
                direction = ArtworkSeekDirection.Backward,
                reduceMotion = reduceMotion,
                modifier = Modifier.align(AbsoluteAlignment.CenterLeft)
            )
            ArtworkSeekIndicator(
                feedback = seekFeedback?.takeIf { it.direction == ArtworkSeekDirection.Forward },
                direction = ArtworkSeekDirection.Forward,
                reduceMotion = reduceMotion,
                modifier = Modifier.align(AbsoluteAlignment.CenterRight)
            )
            LyricsOverlay(
                lyricsState = lyricsState,
                visible = showLyricsOverlay,
                onDismiss = { showLyrics(false) },
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private enum class ArtworkSeekDirection(val deltaMs: Long, val sign: Float) {
    Backward(-10_000L, -1f), Forward(10_000L, 1f)
}

private data class ArtworkSeekFeedback(val direction: ArtworkSeekDirection, val sequence: Int)

@Composable
private fun ArtworkSeekIndicator(
    feedback: ArtworkSeekFeedback?,
    direction: ArtworkSeekDirection,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(feedback, reduceMotion) {
        if (!reduceMotion && feedback != null) {
            pulse.animateTo(1f, MotionTokens.fastTween())
            pulse.animateTo(0f, MotionTokens.quickEffects())
        } else {
            pulse.snapTo(0f)
        }
    }
    val forward = direction == ArtworkSeekDirection.Forward
    AnimatedVisibility(
        visible = feedback != null,
        modifier = modifier.padding(horizontal = 16.dp),
        enter = if (reduceMotion) EnterTransition.None else
            fadeIn(MotionTokens.quickEffects()) + scaleIn(MotionTokens.quickSpatial(), initialScale = 0.88f),
        exit = if (reduceMotion) ExitTransition.None else
            fadeOut(MotionTokens.fastTween()) + scaleOut(MotionTokens.quickEffects(), targetScale = 0.94f)
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(20.dp).graphicsLayer {
                        translationX = 4.dp.toPx() * direction.sign * pulse.value
                    }
                )
                Text(
                    text = stringResource(if (forward) R.string.player_seek_forward_indicator else R.string.player_seek_backward_indicator),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun StreamQualityLabel(label: String) {
    if (label.isEmpty()) return
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PlaybackControlsSection(
    currentSong: Song?,
    albumLinkState: AlbumLinkState,
    playerControlStyle: PlayerControlStyle,
    viewModel: PlayerViewModel,
    isLiked: Boolean,
    repeatMode: Int,
    fullPlayerStyle: FullPlayerStyle,
    onOpenArtist: (String, String?) -> Unit,
    onOpenAlbum: (String, String, String, String?) -> Unit,
    modifier: Modifier = Modifier,
    isCompactHeight: Boolean = false,
    isCompactWidth: Boolean = false,
    availableHeight: androidx.compose.ui.unit.Dp = 640.dp
) {
    // Collect here — not at FullPlayerScreen level — so only this sub-tree recomposes on position ticks.
    val uiState by viewModel.playerUiState.collectAsStateWithLifecycle()
    val reduceMotion = shouldReduceMotion()
    AnimatedContent(
        targetState = playerControlStyle,
        transitionSpec = {
            contentMotion(reduceMotion)
        },
        label = "player_control_style_crossfade",
        modifier = modifier
    ) { style ->
        val controlsTop = when {
            availableHeight >= 800.dp -> 16.dp
            availableHeight >= 680.dp -> 12.dp
            availableHeight >= 580.dp -> 6.dp
            else -> 4.dp
        }
        PlayerControls(
            state = uiState,
            actions = viewModel,
            controlStyle = style,
            isLiked = isLiked,
            onToggleLike = viewModel::toggleLike,
            repeatMode = repeatMode,
            onToggleRepeat = viewModel::toggleRepeatMode,
            onOpenArtist = onOpenArtist,
            artistId = currentSong?.artistId,
            artists = currentSong?.resolvedArtists.orEmpty(),
            albumLinkState = albumLinkState,
            onOpenAlbum = onOpenAlbum,
            thumbnailUrl = currentSong?.thumbnailUrl,
            fullPlayerStyle = fullPlayerStyle,
            isCompactHeight = isCompactHeight,
            isCompactWidth = isCompactWidth,
            availableHeight = availableHeight,
            currentPositionFlow = viewModel.currentPosition,
            modifier = Modifier
                .padding(top = controlsTop + if (isCompactHeight) 8.dp else 16.dp)
                .padding(horizontal = if (isCompactWidth) 12.dp else 24.dp)
        )
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
        animationSpec = MotionTokens.effects(),
        label = "timerBgColor"
    )
    val timerContentColor by animateColorAsState(
        targetValue = if (sleepTimerActive) colorScheme.onPrimaryContainer else inactiveTimerColor,
        animationSpec = MotionTokens.effects(),
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
