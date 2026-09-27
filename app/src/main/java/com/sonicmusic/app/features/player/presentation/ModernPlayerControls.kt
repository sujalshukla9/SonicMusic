package com.sonicmusic.app.features.player.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.sonicmusic.app.theme.pressScale
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.sonicmusic.app.domain.model.ArtistRef
import com.sonicmusic.app.domain.model.FullPlayerStyle
import androidx.compose.foundation.text.ClickableText
import com.sonicmusic.app.theme.expressiveShapes
import com.sonicmusic.app.features.player.presentation.AlbumLinkState
import com.sonicmusic.app.theme.Dimens


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernPlayerControls(
    state: PlayerUiState,
    actions: PlayerActions,
    accentColor: Color,
    surfaceTonalColor: Color,
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
    availableHeight: androidx.compose.ui.unit.Dp = 640.dp
) {
    val colorScheme = MaterialTheme.colorScheme
    val onSurfaceColor = colorScheme.onSurface
    val onAccentColor = getOnAccentColor(accentColor)

    // Reset seek state when the song changes
    var isUserDragging by remember(state.title + state.artist) { mutableStateOf(false) }
    var dragValue by remember(state.title + state.artist) { mutableFloatStateOf(0f) }
    var lastSeekTime by remember(state.title + state.artist) { mutableStateOf(0L) }

    val progress = if (state.durationMs > 0) {
        state.currentPositionMs.toFloat() / state.durationMs
    } else {
        0f
    }
    val isRecentSeek = lastSeekTime > 0L && (System.currentTimeMillis() - lastSeekTime < 800L)

    // Animate the progress smoothly when not dragging or recently seeking
    val animatedProgress by androidx.compose.runtime.key(state.title, state.artist) {
        animateFloatAsState(
            targetValue = progress,
            animationSpec = if (isUserDragging || isRecentSeek) {
                snap()
            } else {
                tween(durationMillis = 250, easing = LinearEasing)
            },
            label = "smooth_progress"
        )
    }

    val sliderPosition = if (isUserDragging || isRecentSeek) {
        dragValue
    } else {
        animatedProgress
    }

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
        availableHeight >= 800.dp -> 24.dp
        availableHeight >= 680.dp -> 16.dp
        availableHeight >= 580.dp -> 8.dp
        else -> 4.dp
    }
    val seekHeight = if (isCompact) 16.dp else 24.dp
    val controlBtnSize = if (isCompact) 56.dp else Dimens.modernControlButtonSize
    val skipBtnSize = if (isCompact) 48.dp else Dimens.modernControlButtonSize

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacingHeight)
    ) {
        // Song Info Centered Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isCompactWidth) 4.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CenteredScrollableContainer(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = state.title,
                    style = (if (isCompactHeight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).copy(
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    color = onSurfaceColor.copy(alpha = 0.90f),
                    maxLines = 1
                )
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
            val artistAnnotated = remember(effectiveArtists, artistText, albumState) {
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
                if (albumState is AlbumLinkState.Available && albumState.name.isNotBlank()) {
                    builder.append(" • ")
                    builder.pushStringAnnotation("album", albumState.browseId)
                    builder.append(albumState.name)
                    builder.pop()
                }
                builder.toAnnotatedString()
            }

            CenteredScrollableContainer(modifier = Modifier.fillMaxWidth()) {
                @Suppress("DEPRECATION")
                ClickableText(
                    text = artistAnnotated,
                    style = (if (isCompactHeight) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(
                        color = onSurfaceColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 1,
                    onClick = { offset ->
                        artistAnnotated.getStringAnnotations("artist", offset, offset)
                            .firstOrNull()?.let { annotation ->
                                val parts = annotation.item.split(":", limit = 2)
                                val browseId = parts[0].takeIf { it.isNotBlank() }
                                val name = parts.getOrElse(1) { "" }
                                if (name.isNotBlank()) {
                                    onOpenArtist(name, browseId)
                                }
                                return@ClickableText
                            }
                        artistAnnotated.getStringAnnotations("album", offset, offset)
                            .firstOrNull()?.let { annotation ->
                                val browseId = annotation.item
                                val name = (albumState as? AlbumLinkState.Available)?.name.orEmpty()
                                onOpenAlbum(browseId, name, artistText, thumbnailUrl)
                            }
                    }
                )
            }
            Spacer(modifier = Modifier.height(textSpPadding)) // padding between artist name and modern player controls
        }

        // Row 1: Play/Pause Pill (~70% width) + Skip Next Squircle (~30% width)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (isCompactWidth) 10.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play/Pause pill
            val playPauseInteractionSource = remember { MutableInteractionSource() }
            val isBuffering = state.isBuffering

            Box(
                modifier = Modifier
                    .weight(0.7f)
                    .height(controlBtnSize)
                    .modernScaleOnPress(playPauseInteractionSource, targetScale = 0.96f)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(accentColor)
                    .clickable(
                        interactionSource = playPauseInteractionSource,
                        indication = LocalIndication.current,
                        onClick = { actions.onPlayPause() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Morphing Icon animation
                AnimatedContent(
                    targetState = state.isPlaying,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                                scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(90)) +
                                        scaleOut(targetScale = 0.92f, animationSpec = tween(90))
                            )
                    },
                    label = "play_pause_icon_morph"
                ) { isPlaying ->
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else " Play",
                        modifier = Modifier.size(controlBtnSize * 0.44f),
                        tint = onAccentColor
                    )
                }

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
                    .modernScaleOnPress(nextInteractionSource, targetScale = 0.96f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 36.dp, bottomEnd = 36.dp, bottomStart = 16.dp))
                    .background(accentColor.copy(alpha = if (nextEnabled) 0.15f else 0.06f))
                    .clickable(
                        interactionSource = nextInteractionSource,
                        indication = if (nextEnabled) LocalIndication.current else null,
                        enabled = nextEnabled,
                        onClick = { actions.onSkipNext() }
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

        // Row 2: Skip Previous Squircle + Seek Bar (centered vertically)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = skipBtnSize),
            horizontalArrangement = Arrangement.spacedBy(if (isCompactWidth) 10.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Skip Previous button
            val prevInteractionSource = remember { MutableInteractionSource() }
            val prevEnabled = state.canSkipPrevious

            Box(
                modifier = Modifier
                    .size(skipBtnSize)
                    .modernScaleOnPress(prevInteractionSource, targetScale = 0.96f)
                    .clip(RoundedCornerShape(topStart = 36.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 36.dp))
                    .background(accentColor.copy(alpha = if (prevEnabled) 0.15f else 0.06f))
                    .clickable(
                        interactionSource = prevInteractionSource,
                        indication = if (prevEnabled) LocalIndication.current else null,
                        enabled = prevEnabled,
                        onClick = { actions.onSkipPrevious() }
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

            // Custom Slider seek bar
            Column(
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically)
            ) {
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
                        activeColor = onSurfaceColor,
                        inactiveColor = onSurfaceColor.copy(alpha = 0.32f),
                        modifier = Modifier.fillMaxWidth(),
                        trackHeight = seekHeight
                    )

                    Slider(
                        value = sliderPosition,
                        onValueChange = { newValue ->
                            isUserDragging = true
                            dragValue = newValue
                            actions.onSeekPreviewChange((newValue * state.durationMs).toLong())
                        },
                        onValueChangeFinished = {
                            actions.onSeekTo((dragValue * state.durationMs).toLong())
                            lastSeekTime = System.currentTimeMillis()
                            isUserDragging = false
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Transparent,
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent
                        ),
                        thumb = {}
                    )
                }

                val displayTimeMs = if (isUserDragging || isRecentSeek) {
                    (dragValue * state.durationMs).toLong()
                } else {
                    state.currentPositionMs
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
                        color = onSurfaceColor.copy(alpha = 0.70f)
                    )
                    Text(
                        text = formatDuration((state.durationMs / 1000).toInt()),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = onSurfaceColor.copy(alpha = 0.70f)
                    )
                }
            }
        }
    }
}

@Composable
fun Modifier.modernScaleOnPress(
    interactionSource: InteractionSource,
    targetScale: Float = 0.96f
): Modifier = this.pressScale(interactionSource, targetScale)

private fun getOnAccentColor(accentColor: Color): Color {
    val red = accentColor.red
    val green = accentColor.green
    val blue = accentColor.blue
    val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return if (luminance > 0.5f) Color.Black else Color.White
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
