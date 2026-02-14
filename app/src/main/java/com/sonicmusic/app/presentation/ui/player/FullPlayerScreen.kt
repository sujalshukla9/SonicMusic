package com.sonicmusic.app.presentation.ui.player

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.presentation.ui.components.SongThumbnail
import com.sonicmusic.app.presentation.viewmodel.PlayerViewModel
import java.net.URLEncoder
import kotlin.math.PI
import kotlin.math.sin

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
    onOpenArtist: (String) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isBuffering by viewModel.isBuffering.collectAsState()
    val isLiked by viewModel.isLiked.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val fullPlayerStyle by viewModel.fullPlayerStyle.collectAsState()
    val dynamicColorsEnabled by viewModel.dynamicColorsEnabled.collectAsState()
    val dynamicColorIntensity by viewModel.dynamicColorIntensity.collectAsState()
    val albumArtBlurEnabled by viewModel.albumArtBlurEnabled.collectAsState()

    // Sheet states
    val sleepTimerRemaining by viewModel.sleepTimerRemaining.collectAsState()
    val sleepTimerActive by viewModel.sleepTimerActive.collectAsState()
    var showSleepTimerSheet by remember { mutableStateOf(false) }

    val queue by viewModel.queue.collectAsState()
    val currentQueueIndex by viewModel.currentQueueIndex.collectAsState()
    val infiniteModeEnabled by viewModel.infiniteModeEnabled.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var showQueueSheet by remember { mutableStateOf(false) }

    var showSpeedSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }

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
            animationSpec = tween(durationMillis = 300),
            label = "full_player_accent"
        )
        val onAccentColor by animateColorAsState(
            targetValue = artworkPalette.onAccent,
            animationSpec = tween(durationMillis = 300),
            label = "full_player_on_accent"
        )
        val backgroundTopTint by animateColorAsState(
            targetValue = artworkPalette.containerSoft,
            animationSpec = tween(durationMillis = 300),
            label = "full_player_bg_top_tint"
        )
        val backgroundMidTint by animateColorAsState(
            targetValue = artworkPalette.container,
            animationSpec = tween(durationMillis = 300),
            label = "full_player_bg_mid_tint"
        )

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
                                if (dragAmount > 0f) offsetY += dragAmount
                            },
                            onDragEnd = {
                                if (offsetY > 200f) onDismiss()
                                offsetY = 0f
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
                            SongThumbnail(
                                artworkUrl = currentSong?.thumbnailUrl,
                                modifier = Modifier.fillMaxSize(),
                                contentDescription = "Album Art",
                                contentScale = ContentScale.Crop
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

                    Spacer(modifier = Modifier.height(20.dp))

                    // ═══════════════════════════════════════════
                    // PROGRESS SLIDER
                    // ═══════════════════════════════════════════
                    // ═══════════════════════════════════════════
                    // PROGRESS SLIDER - Isolated
                    // ═══════════════════════════════════════════
                    PlayerProgressSection(
                        viewModel = viewModel,
                        fullPlayerStyle = fullPlayerStyle,
                        isPlaying = isPlaying
                    )

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
                        // Like
                        IconButton(
                            onClick = { viewModel.toggleLike() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Like",
                                modifier = Modifier.size(24.dp),
                                tint = if (isLiked) colorScheme.error else colorScheme.onSurfaceVariant
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
                        Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                            if (isBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = accentColor,
                                    strokeWidth = 4.dp
                                )
                            } else {
                                FilledIconButton(
                                    onClick = { viewModel.togglePlayPause() },
                                    modifier = Modifier.size(80.dp),
                                    shape = CircleShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = accentColor,
                                        contentColor = onAccentColor
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
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
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) {
                                    accentColor
                                } else {
                                    colorScheme.onSurfaceVariant
                                }
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
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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

                        Spacer(modifier = Modifier.size(28.dp))

                        // More
                        IconButton(
                            onClick = { showMoreSheet = true },
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
        FullPlayerMoreSheet(
            isVisible = showMoreSheet,
            song = currentSong,
            isLiked = isLiked,
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
                val song = currentSong ?: return@FullPlayerMoreSheet
                val albumUrl = buildYouTubeMusicAlbumUrl(song)
                if (!openExternalUrl(context, albumUrl)) {
                    Toast.makeText(context, "Unable to open album", Toast.LENGTH_SHORT).show()
                }
            },
            onMoreFromArtist = {
                showMoreSheet = false
                val artistName = currentSong?.artist.orEmpty()
                if (artistName.isBlank()) {
                    Toast.makeText(context, "Artist not available", Toast.LENGTH_SHORT).show()
                } else {
                    onOpenArtist(artistName)
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
            }
        )

        AddToPlaylistSheet(
            isVisible = showAddToPlaylistSheet,
            playlists = playlists,
            fallbackArtworkUrl = currentSong?.thumbnailUrl,
            onDismiss = { showAddToPlaylistSheet = false },
            onSelectPlaylist = viewModel::addCurrentSongToPlaylist,
            onCreatePlaylistAndAdd = viewModel::createPlaylistAndAddCurrentSong
        )

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
            onToggleInfiniteMode = viewModel::setInfiniteMode,
            shuffleEnabled = shuffleEnabled,
            onToggleShuffle = viewModel::toggleShuffle
        )

        PlaybackSpeedSheet(
            isVisible = showSpeedSheet,
            onDismiss = { showSpeedSheet = false },
            currentSpeed = playbackSpeed,
            onSpeedChange = viewModel::setPlaybackSpeed
        )
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
                modifier = Modifier
                    .fillMaxSize()
                    .blur(68.dp),
                contentDescription = null,
                contentScale = ContentScale.Crop
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerProgressSection(
    viewModel: PlayerViewModel,
    fullPlayerStyle: FullPlayerStyle,
    isPlaying: Boolean
) {
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            PlayerSeekTrack(
                progress = sliderPosition,
                style = fullPlayerStyle,
                isPlaying = isPlaying,
                activeColor = colorScheme.onSurface,
                inactiveColor = colorScheme.onSurface.copy(alpha = 0.28f),
                modifier = Modifier.fillMaxWidth()
            )

            Slider(
                value = sliderPosition,
                onValueChange = { newValue ->
                    isUserDragging = true
                    dragValue = newValue
                },
                onValueChangeFinished = {
                    viewModel.seekTo(dragValue)
                    isUserDragging = false
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                )
            )
        }

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

@Composable
private fun PlayerSeekTrack(
    progress: Float,
    style: FullPlayerStyle,
    isPlaying: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    val isWavyStyle = style == FullPlayerStyle.WAVY
    val wavePhase = if (isWavyStyle && isPlaying) {
        rememberInfiniteTransition(label = "seek_wave_motion_transition").animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI.toFloat()),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "seek_wave_phase"
        ).value
    } else {
        0f
    }
    val waveAmplitudeScale by animateFloatAsState(
        targetValue = when {
            isWavyStyle && isPlaying -> 1f
            isWavyStyle -> 0.72f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 240),
        label = "seek_wave_amplitude"
    )

    Canvas(
        modifier = modifier.height(24.dp)
    ) {
        val centerY = size.height / 2f
        val trackStroke = 5.dp.toPx()
        val thumbStroke = 7.dp.toPx()
        val thumbHeight = 18.dp.toPx()
        val clampedProgress = progress.coerceIn(0f, 1f)
        val activeEndX = size.width * clampedProgress
        val waveAmplitude = 2.8.dp.toPx() * waveAmplitudeScale
        val wavelength = 34.dp.toPx()
        val waveStep = 2.dp.toPx()

        var thumbCenterY = centerY

        if (activeEndX > 0f) {
            if (isWavyStyle) {
                val wavePath = Path().apply {
                    val startY = centerY + sin(wavePhase) * waveAmplitude
                    moveTo(0f, startY)
                    var x = waveStep
                    while (x <= activeEndX) {
                        val radians = ((x / wavelength) * (2f * PI.toFloat())) + wavePhase
                        lineTo(x, centerY + sin(radians) * waveAmplitude)
                        x += waveStep
                    }
                    if (x - waveStep < activeEndX) {
                        val radians = ((activeEndX / wavelength) * (2f * PI.toFloat())) + wavePhase
                        lineTo(activeEndX, centerY + sin(radians) * waveAmplitude)
                    }
                }

                drawPath(
                    path = wavePath,
                    color = activeColor,
                    style = Stroke(width = trackStroke, cap = StrokeCap.Round)
                )

                val thumbRadians = ((activeEndX / wavelength) * (2f * PI.toFloat())) + wavePhase
                thumbCenterY = centerY + sin(thumbRadians) * waveAmplitude
            } else {
                drawLine(
                    color = activeColor,
                    start = Offset(0f, centerY),
                    end = Offset(activeEndX, centerY),
                    strokeWidth = trackStroke,
                    cap = StrokeCap.Round
                )
            }
        }

        if (activeEndX < size.width) {
            drawLine(
                color = inactiveColor,
                start = Offset(activeEndX.coerceIn(0f, size.width), centerY),
                end = Offset(size.width, centerY),
                strokeWidth = trackStroke,
                cap = StrokeCap.Round
            )
        }

        drawLine(
            color = activeColor,
            start = Offset(
                x = activeEndX.coerceIn(0f, size.width),
                y = thumbCenterY - (thumbHeight / 2f)
            ),
            end = Offset(
                x = activeEndX.coerceIn(0f, size.width),
                y = thumbCenterY + (thumbHeight / 2f)
            ),
            strokeWidth = thumbStroke,
            cap = StrokeCap.Round
        )
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds < 0) return "0:00"
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", minutes, secs)
}
