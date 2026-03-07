package com.sonicmusic.app.presentation.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.presentation.viewmodel.LyricsUiState
import com.sonicmusic.app.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * ViTune-style lyrics overlay that renders ON TOP of the album artwork.
 *
 * Shows a semi-transparent dark scrim with scrollable lyrics text.
 * Features:
 * - Full song lyrics (synced with auto-scroll, or plain text)
 * - Language toggle (Hinglish ↔ Original script) for Hindi songs
 * - Tap anywhere on the overlay to dismiss it
 */
@Composable
fun LyricsOverlay(
    lyricsState: LyricsUiState,
    currentPositionFlow: StateFlow<Long>,
    visible: Boolean,
    onDismiss: () -> Unit,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 6 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 6 }),
        modifier = modifier
    ) {
        val showOriginal by viewModel.showOriginalScript.collectAsStateWithLifecycle()

        // Check if language toggle is available
        val hasOriginalScript = remember(lyricsState) {
            when (lyricsState) {
                is LyricsUiState.LoadedPlain -> lyricsState.originalText != null
                is LyricsUiState.LoadedSynced -> lyricsState.lines.any { it.originalText != null }
                else -> false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.78f),
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.78f)
                        )
                    )
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            when (lyricsState) {
                is LyricsUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color.White.copy(alpha = 0.8f),
                        strokeWidth = 2.5.dp
                    )
                }

                is LyricsUiState.LoadedPlain -> {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(4.dp))

                        // Top row: header + language toggle
                        LyricsHeader(
                            title = "Lyrics",
                            hasOriginalScript = hasOriginalScript,
                            showOriginal = showOriginal,
                            onToggleLanguage = { viewModel.toggleLyricsLanguage() }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Full lyrics text
                        val displayText = if (showOriginal && lyricsState.originalText != null) {
                            lyricsState.originalText
                        } else {
                            lyricsState.text
                        }

                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                lineHeight = 28.sp,
                                letterSpacing = 0.3.sp
                            ),
                            color = Color.White.copy(alpha = 0.92f),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Medium
                        )

                        // Source attribution
                        if (!lyricsState.source.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = lyricsState.source,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.4f),
                                fontStyle = FontStyle.Italic,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                is LyricsUiState.LoadedSynced -> {
                    val listState = rememberLazyListState()
                    val currentPositionState by currentPositionFlow.collectAsStateWithLifecycle()
                    val currentPositionMs = currentPositionState

                    // Determine which line is currently active
                    val activeIndex = remember(currentPositionMs, lyricsState.lines) {
                        val index = lyricsState.lines.indexOfLast { it.timeMs <= currentPositionMs }
                        if (index >= 0) index else 0
                    }

                    // Auto-scroll to active line
                    LaunchedEffect(activeIndex) {
                        if (activeIndex in lyricsState.lines.indices) {
                            val scrollIndex = (activeIndex - 3).coerceAtLeast(0)
                            listState.animateScrollToItem(scrollIndex)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        contentPadding = PaddingValues(vertical = 32.dp)
                    ) {
                        item {
                            LyricsHeader(
                                title = "Synced Lyrics",
                                hasOriginalScript = hasOriginalScript,
                                showOriginal = showOriginal,
                                onToggleLanguage = { viewModel.toggleLyricsLanguage() }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        itemsIndexed(lyricsState.lines) { index, line ->
                            val isActive = index == activeIndex
                            val isUpcoming = index > activeIndex

                            val displayText = if (showOriginal && line.originalText != null) {
                                line.originalText
                            } else {
                                line.text
                            }

                            LyricLineItem(
                                lineText = displayText,
                                isActive = isActive,
                                isUpcoming = isUpcoming
                            )
                        }

                        if (!lyricsState.source.isNullOrBlank()) {
                            item {
                                Spacer(modifier = Modifier.height(24.dp))
                                Text(
                                    text = lyricsState.source,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontStyle = FontStyle.Italic,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                is LyricsUiState.Unavailable -> {
                    Text(
                        text = "Lyrics not available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }

                is LyricsUiState.Error -> {
                    Text(
                        text = "Couldn't load lyrics",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }

                is LyricsUiState.Idle -> {
                    // Nothing to show — shouldn't be visible in Idle state
                }
            }
        }
    }
}

/**
 * Header row with title and optional language toggle button.
 */
@Composable
private fun LyricsHeader(
    title: String,
    hasOriginalScript: Boolean,
    showOriginal: Boolean,
    onToggleLanguage: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Placeholder spacer for centering when toggle is present
        if (hasOriginalScript) {
            Spacer(modifier = Modifier.size(36.dp))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.6f),
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )

        if (hasOriginalScript) {
            IconButton(
                onClick = onToggleLanguage,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = "Toggle Language",
                    tint = if (showOriginal) {
                        Color(0xFF82B1FF)
                    } else {
                        Color.White.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun LyricLineItem(
    lineText: String,
    isActive: Boolean,
    isUpcoming: Boolean
) {
    val targetAlpha = when {
        isActive -> 1f
        isUpcoming -> 0.4f
        else -> 0.7f
    }

    val targetScale = if (isActive) 1.05f else 1f

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 300),
        label = "alpha"
    )
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "scale"
    )

    Text(
        text = lineText,
        style = MaterialTheme.typography.bodyLarge.copy(
            lineHeight = 32.sp,
            letterSpacing = 0.5.sp
        ),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.White,
        textAlign = TextAlign.Center,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
        modifier = Modifier
            .padding(vertical = 8.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
    )
}
