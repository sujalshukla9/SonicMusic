package com.sonicmusic.app.features.player.presentation

import com.sonicmusic.app.theme.iconMotion
import com.sonicmusic.app.theme.contentMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.sonicmusic.app.theme.MotionTokens
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.Player
import com.sonicmusic.app.domain.model.ArtistRef
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.PlayerControlStyle
import com.sonicmusic.app.theme.Dimens
import com.sonicmusic.app.theme.pressScale
import com.sonicmusic.app.theme.shouldReduceMotion


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerControls(
    state: PlayerUiState,
    actions: PlayerActions,
    controlStyle: PlayerControlStyle,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    repeatMode: Int,
    onToggleRepeat: () -> Unit,
    onOpenArtist: (String, String?) -> Unit,
    artistId: String?,
    artists: List<ArtistRef> = emptyList(),
    albumLinkState: AlbumLinkState,
    onOpenAlbum: (String, String, String, String?) -> Unit,
    thumbnailUrl: String?,
    modifier: Modifier = Modifier,
    fullPlayerStyle: FullPlayerStyle = FullPlayerStyle.NORMAL,
    isCompactHeight: Boolean = false,
    isCompactWidth: Boolean = false,
    availableHeight: Dp = 640.dp,
    currentPositionFlow: StateFlow<Long>
) {
    val reduceMotion = shouldReduceMotion()
    val colorScheme = MaterialTheme.colorScheme
    val onSurfaceColor = colorScheme.onSurface
    val isClassic = controlStyle == PlayerControlStyle.CLASSIC

    val isCompact = isCompactHeight || isCompactWidth
    val spacingHeight = when {
        isCompactWidth -> 6.dp
        availableHeight >= 800.dp -> 20.dp
        availableHeight >= 680.dp -> 16.dp
        availableHeight >= 580.dp -> 8.dp
        else -> 4.dp
    }
    val textSpPadding = when {
        isCompactWidth -> 8.dp
        isClassic -> if (availableHeight >= 680.dp) 8.dp else 0.dp
        availableHeight >= 800.dp -> 24.dp
        availableHeight >= 680.dp -> 16.dp
        availableHeight >= 580.dp -> 8.dp
        else -> 4.dp
    }
    val titleStyle = when {
        isClassic && !isCompactHeight -> MaterialTheme.typography.headlineMedium
        !isClassic && isCompactHeight -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleLarge
    }
    val seekHeight = if (isCompact) 16.dp else 24.dp
    val controlBtnSize = if (isCompact) 56.dp else Dimens.modernControlButtonSize
    val skipBtnSize = if (isCompact) 48.dp else Dimens.modernControlButtonSize

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacingHeight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isCompactWidth) 4.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CenteredScrollableContainer(modifier = Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = state.title,
                    transitionSpec = { contentMotion(reduceMotion) },
                    label = "track_title"
                ) { title ->
                    Text(
                        text = title,
                        style = titleStyle.copy(
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        ),
                        color = if (isClassic) onSurfaceColor else onSurfaceColor.copy(alpha = 0.9f),
                        maxLines = 1
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            // Build annotated string with clickable artist segments
            val artistText = state.artist
            val albumState = albumLinkState
            val effectiveArtists = remember(artists, artistText, artistId) {
                if (artists.isNotEmpty()) {
                    artists
                } else if (artistText.isNotBlank()) {
                    val ids = artistId?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() && it != "null" }.orEmpty()
                    val lower = artistText.trim().lowercase(java.util.Locale.ROOT)
                    if (com.sonicmusic.app.domain.model.Song.isKnownCompositeBand(lower)) {
                        listOf(ArtistRef(artistText.trim(), ids.firstOrNull()))
                    } else {
                        val names = artistText.split(
                            Regex("\\s*[,;/+×|\\u00d7]\\s*|\\s*&\\s*|\\s+(?:and|with|w/|feat\\.?|ft\\.?|x|vs\\.?)\\s+", RegexOption.IGNORE_CASE)
                        ).map { it.trim() }.filter { it.isNotBlank() }
                        when {
                            names.size == ids.size && ids.isNotEmpty() -> {
                                names.zip(ids) { n, id -> ArtistRef(n, id) }
                            }
                            names.size <= 1 -> {
                                listOf(ArtistRef(names.firstOrNull() ?: artistText.trim(), ids.firstOrNull()))
                            }
                            else -> {
                                names.map { n -> ArtistRef(n, null) }
                            }
                        }
                    }
                } else {
                    emptyList()
                }
            }
            val songTitle = state.title
            val artistAnnotated = remember(effectiveArtists, artistText, albumState, songTitle) {
                val builder = androidx.compose.ui.text.AnnotatedString.Builder()
                if (effectiveArtists.isNotEmpty()) {
                    effectiveArtists.forEachIndexed { index, artistRef ->
                        if (index > 0) {
                            builder.append(", ")
                        }
                        builder.pushStringAnnotation("artist", "${artistRef.browseId.orEmpty()}:${artistRef.name}")
                        builder.append(artistRef.name)
                        builder.pop()
                    }
                } else {
                    builder.pushStringAnnotation("artist", ":${artistText}")
                    builder.append(artistText)
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
                AnimatedContent(
                    targetState = artistAnnotated,
                    transitionSpec = { contentMotion(reduceMotion) },
                    label = "track_artists"
                ) { credit ->
                    @Suppress("DEPRECATION")
                    ClickableText(
                        text = credit,
                        style = (if (isCompactHeight) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(
                            color = if (isClassic) colorScheme.onSurfaceVariant else onSurfaceColor.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        ),
                        maxLines = 1,
                        onClick = { offset ->
                            credit.getStringAnnotations("artist", offset, offset)
                                .firstOrNull()?.let { annotation ->
                                    val parts = annotation.item.split(":", limit = 2)
                                    val browseId = parts[0].takeIf { it.isNotBlank() }
                                    val name = parts.getOrElse(1) { "" }
                                    if (name.isNotBlank()) {
                                        onOpenArtist(name, browseId)
                                    }
                                    return@ClickableText
                                }
                            credit.getStringAnnotations("album", offset, offset)
                                .firstOrNull()?.let { annotation ->
                                    val browseId = annotation.item
                                    val name = (albumState as? AlbumLinkState.Available)?.name.orEmpty()
                                    onOpenAlbum(browseId, name, artistText, thumbnailUrl)
                                }
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(textSpPadding))
        }

        val seekBar: @Composable (Modifier) -> Unit = { seekModifier ->
            PlayerSliderSection(
                currentPositionFlow = currentPositionFlow,
                state = state,
                fullPlayerStyle = fullPlayerStyle,
                controlStyle = controlStyle,
                seekHeight = seekHeight,
                actions = actions,
                modifier = seekModifier
            )
        }
        if (isClassic) {
            seekBar(Modifier.fillMaxWidth())
            ClassicTransportControls(
                state = state,
                actions = actions,
                isLiked = isLiked,
                onToggleLike = onToggleLike,
                repeatMode = repeatMode,
                onToggleRepeat = onToggleRepeat,
                controlBtnSize = controlBtnSize,
                skipBtnSize = skipBtnSize,
                isCompact = isCompact
            )
        } else {
            ModernTransportControls(
                state = state,
                actions = actions,
                controlBtnSize = controlBtnSize,
                skipBtnSize = skipBtnSize,
                spacing = if (isCompactWidth) 10.dp else 16.dp,
                seekBar = seekBar
            )
        }
    }
}

@Composable
private fun ClassicTransportControls(
    state: PlayerUiState,
    actions: PlayerActions,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    repeatMode: Int,
    onToggleRepeat: () -> Unit,
    controlBtnSize: Dp,
    skipBtnSize: Dp,
    isCompact: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme
    val accentColor = colorScheme.primary
    val reduceMotion = shouldReduceMotion()
    val playCornerRadius by animateDpAsState(
        targetValue = if (state.isPlaying) 20.dp else controlBtnSize / 2,
        animationSpec = if (reduceMotion) snap() else MotionTokens.quickSpatial(),
        label = "playback_button_shape"
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = when {
            maxWidth >= 360.dp -> 12.dp
            maxWidth >= 286.dp -> 8.dp
            else -> 4.dp
        }
        val availableSkipWidth = (maxWidth - 96.dp - 56.dp - gap * 4) / 2
        val fittedSkipSize = minOf(skipBtnSize, availableSkipWidth.coerceAtLeast(40.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
                IconToggleButton(checked = isLiked, onCheckedChange = { onToggleLike() }) {
                    AnimatedContent(targetState = isLiked, transitionSpec = { iconMotion(reduceMotion) }, label = "like") { liked ->
                        Icon(
                            imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Like",
                            tint = if (liked) accentColor else colorScheme.onSurfaceVariant
                        )
                    }
                }

                SkipTrackButton(
                    onClick = actions::onSkipPrevious,
                    enabled = state.canSkipPrevious,
                    icon = Icons.Rounded.SkipPrevious,
                    description = "Previous track",
                    size = fittedSkipSize,
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 12.dp, bottomEnd = 12.dp)
                )

                val playInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .width(if (isCompact) 116.dp else 152.dp)
                        .height(controlBtnSize)
                        .pressScale(playInteraction)
                        .clip(RoundedCornerShape(playCornerRadius))
                        .background(accentColor)
                        .clickable(
                            interactionSource = playInteraction,
                            indication = LocalIndication.current,
                            role = Role.Button,
                            onClick = actions::onPlayPause
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    PlaybackGlyph(
                        playing = state.isPlaying,
                        modifier = Modifier.size(32.dp),
                        tint = colorScheme.onPrimary
                    )
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(44.dp),
                            color = colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    }
                }

                SkipTrackButton(
                    onClick = actions::onSkipNext,
                    enabled = state.canSkipNext,
                    icon = Icons.Rounded.SkipNext,
                    description = "Next track",
                    size = fittedSkipSize,
                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 28.dp, bottomEnd = 28.dp)
                )

                IconButton(onClick = onToggleRepeat) {
                    AnimatedContent(targetState = repeatMode, transitionSpec = { iconMotion(reduceMotion) }, label = "repeat") { mode ->
                        Icon(
                            imageVector = if (mode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            contentDescription = when (mode) {
                                Player.REPEAT_MODE_ONE -> "Repeat one track"
                                Player.REPEAT_MODE_ALL -> "Repeat all tracks"
                                else -> "Repeat off"
                            },
                            tint = if (mode == Player.REPEAT_MODE_OFF) colorScheme.onSurfaceVariant else accentColor
                        )
                    }
                }
        }
    }
}

@Composable
private fun ModernTransportControls(
    state: PlayerUiState,
    actions: PlayerActions,
    controlBtnSize: Dp,
    skipBtnSize: Dp,
    spacing: Dp,
    seekBar: @Composable (Modifier) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val accentColor = colors.primary
    val onAccentColor = colors.onPrimary

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play/Pause pill
        val playPauseInteractionSource = remember { MutableInteractionSource() }
        val isBuffering = state.isBuffering

        Box(
            modifier = Modifier
                .weight(0.7f)
                .height(controlBtnSize)
                .pressScale(playPauseInteractionSource, targetScale = 0.96f)
                .clip(RoundedCornerShape(percent = 50))
                .background(accentColor)
                .clickable(
                    interactionSource = playPauseInteractionSource,
                    indication = LocalIndication.current,
                    role = Role.Button,
                onClick = actions::onPlayPause
                ),
            contentAlignment = Alignment.Center
        ) {
            PlaybackGlyph(
                playing = state.isPlaying,
                modifier = Modifier.size(controlBtnSize * 0.44f),
                tint = onAccentColor
            )

            // Buffering overlay (non-blocking)
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(controlBtnSize * 0.66f),
                    color = onAccentColor.copy(alpha = 0.5f),
                    strokeWidth = 3.dp
                )
            }
        }

        // Skip Next button
        val nextInteractionSource = remember { MutableInteractionSource() }
        val nextEnabled = state.canSkipNext

        Box(
            modifier = Modifier
                .size(skipBtnSize)
                .pressScale(nextInteractionSource, targetScale = 0.96f)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 36.dp, bottomEnd = 36.dp, bottomStart = 16.dp))
                .background(accentColor.copy(alpha = if (nextEnabled) 0.15f else 0.06f))
                .clickable(
                    interactionSource = nextInteractionSource,
                    indication = if (nextEnabled) LocalIndication.current else null,
                    enabled = nextEnabled,
                    role = Role.Button,
                onClick = actions::onSkipNext
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next",
                modifier = Modifier.size(skipBtnSize * 0.38f),
                tint = if (nextEnabled) accentColor else accentColor.copy(alpha = 0.38f)
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = skipBtnSize),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Skip Previous button
        val prevInteractionSource = remember { MutableInteractionSource() }
        val prevEnabled = state.canSkipPrevious

        Box(
            modifier = Modifier
                .size(skipBtnSize)
                .pressScale(prevInteractionSource, targetScale = 0.96f)
                .clip(RoundedCornerShape(topStart = 36.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 36.dp))
                .background(accentColor.copy(alpha = if (prevEnabled) 0.15f else 0.06f))
                .clickable(
                    interactionSource = prevInteractionSource,
                    indication = if (prevEnabled) LocalIndication.current else null,
                    enabled = prevEnabled,
                    role = Role.Button,
                onClick = actions::onSkipPrevious
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Previous",
                modifier = Modifier.size(skipBtnSize * 0.38f),
                tint = if (prevEnabled) accentColor else accentColor.copy(alpha = 0.38f)
            )
        }
        seekBar(Modifier.weight(1f))
    }
}

@Composable
private fun SkipTrackButton(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: ImageVector,
    description: String,
    size: Dp,
    shape: RoundedCornerShape
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
            .pressScale(interactionSource)
            .clip(shape)
            .background(if (enabled) colors.secondaryContainer else colors.surfaceContainer)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) colors.onSecondaryContainer else colors.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.size(24.dp)
        )
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds < 0) return "0:00"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%d:%02d", minutes, secs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSliderSection(
    currentPositionFlow: StateFlow<Long>,
    state: PlayerUiState,
    fullPlayerStyle: FullPlayerStyle,
    controlStyle: PlayerControlStyle,
    seekHeight: Dp,
    actions: PlayerActions,
    modifier: Modifier = Modifier
) {
    val currentPosition by currentPositionFlow.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val reduceMotion = shouldReduceMotion()
    val durationMs = state.durationMs
    val songTitle = state.title
    val songArtist = state.artist
    val isClassic = controlStyle == PlayerControlStyle.CLASSIC
    val timeColor = if (isClassic) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.7f)

    var isUserDragging by remember(songTitle, songArtist) { mutableStateOf(false) }
    var dragValue by remember(songTitle, songArtist) { mutableFloatStateOf(0f) }
    var lastSeekTime by remember(songTitle, songArtist) { mutableStateOf(0L) }

    val progress = if (durationMs > 0) {
        (currentPosition.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val isRecentSeek = lastSeekTime > 0L && (System.currentTimeMillis() - lastSeekTime < 800L)

    val animatedProgress by androidx.compose.runtime.key(songTitle, songArtist) {
        animateFloatAsState(
            targetValue = progress,
            animationSpec = if (reduceMotion || isUserDragging || isRecentSeek) {
                snap()
            } else {
                MotionTokens.linearTween(MotionTokens.PlaybackProgressInterval)
            },
            label = "smooth_progress"
        )
    }

    val sliderPosition = if (isUserDragging || isRecentSeek) {
        dragValue
    } else {
        animatedProgress
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            PlayerSeekTrack(
                progress = sliderPosition,
                style = fullPlayerStyle,
                isPlaying = state.isPlaying,
                activeColor = if (isClassic) colors.primary else colors.onSurface,
                inactiveColor = if (isClassic) colors.outlineVariant else colors.onSurface.copy(alpha = 0.32f),
                modifier = Modifier.fillMaxWidth(),
                trackHeight = seekHeight
            )

            Slider(
                value = sliderPosition,
                onValueChange = { newValue ->
                    isUserDragging = true
                    dragValue = newValue
                    actions.onSeekPreviewChange((newValue * durationMs).toLong())
                },
                onValueChangeFinished = {
                    actions.onSeekTo((dragValue * durationMs).toLong())
                    lastSeekTime = System.currentTimeMillis()
                    isUserDragging = false
                },
                valueRange = 0f..1f,
                enabled = durationMs > 0L,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Playback position" },
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent
                ),
                thumb = {}
            )
        }

        val displayTimeMs = if (isUserDragging || isRecentSeek) {
            (dragValue * durationMs).toLong()
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
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = timeColor
            )
            Text(
                text = formatDuration((durationMs / 1000).toInt()),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = timeColor
            )
        }
    }
}
