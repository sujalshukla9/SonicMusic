package com.sonicmusic.app.presentation.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sonicmusic.app.presentation.ui.theme.DynamicGradientBackground
import com.sonicmusic.app.presentation.ui.theme.PlayerColorScheme
import com.sonicmusic.app.presentation.ui.theme.expressiveShapes
import com.sonicmusic.app.presentation.ui.theme.expressiveTypography
import com.sonicmusic.app.presentation.ui.theme.expressiveMotion
import com.sonicmusic.app.presentation.ui.theme.playerColorScheme

/**
 * A beautiful, full NowPlayingScreen showcase demonstrating:
 * 1. Animated vertical dynamic gradient background using [DynamicGradientBackground]
 * 2. Emphasized Expressive Typography Scale (e.g. titleLargeEmphasized)
 * 3. Expressive Shapes Morphing for cards and buttons
 * 4. Settings toggle for dynamic album art theme
 */
@Composable
fun NowPlayingScreen(
    title: String,
    artist: String,
    albumUrl: String?,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    dynamicThemeEnabled: Boolean,
    onToggleDynamicTheme: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // Access the extracted color scheme provided dynamically through CompositionLocal
    val playerColors = MaterialTheme.playerColorScheme
    val shapes = MaterialTheme.expressiveShapes
    val typography = MaterialTheme.expressiveTypography

    // We render the dynamic animated gradient as the backmost layer
    DynamicGradientBackground(
        colorScheme = playerColors,
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. TOP HEADER ROW (Title & Dynamic Theme Toggle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NOW PLAYING",
                    style = typography.labelLargeEmphasized,
                    color = playerColors.onSurface.copy(alpha = 0.7f),
                    letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified
                )
                
                // Toggle showcasing the option to enable/disable dynamic extraction
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(shapes.chipMorph)
                        .background(playerColors.onSurface.copy(alpha = 0.08f))
                        .clickable { onToggleDynamicTheme(!dynamicThemeEnabled) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Dynamic Art",
                        style = typography.labelSmallEmphasized,
                        color = playerColors.onSurface
                    )
                    Switch(
                        checked = dynamicThemeEnabled,
                        onCheckedChange = onToggleDynamicTheme,
                        modifier = Modifier.scale(0.7f),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = playerColors.primary,
                            checkedTrackColor = playerColors.primary.copy(alpha = 0.3f),
                            uncheckedThumbColor = playerColors.onSurface.copy(alpha = 0.4f),
                            uncheckedTrackColor = playerColors.onSurface.copy(alpha = 0.1f)
                        )
                    )
                }
            }

            // 2. ALBUM ART CARD WITH EXPRESSIVE CARD MORPH SHAPE
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = shapes.cardMorph, // Morph shape with asymmetric rounded corners
                    colors = CardDefaults.cardColors(
                        containerColor = playerColors.secondary.copy(alpha = 0.2f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                    modifier = Modifier
                        .aspectRatio(1f)
                        .fillMaxWidth(0.85f)
                ) {
                    if (!albumUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = albumUrl,
                            contentDescription = "Artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Fallback icon placeholder
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(playerColors.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Music",
                                tint = playerColors.primary,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }
                }
            }

            // 3. TITLE & ARTIST TEXT DETAILS
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = typography.titleLargeEmphasized, // Emphasized bold scale
                    color = playerColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    text = artist,
                    style = typography.bodyLargeEmphasized,
                    color = playerColors.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            // 4. PROGRESS SEEK BAR SLIDER
            var progressVal by remember { mutableFloatStateOf(0.35f) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 12.dp)
            ) {
                Slider(
                    value = progressVal,
                    onValueChange = { progressVal = it },
                    colors = SliderDefaults.colors(
                        activeTrackColor = playerColors.primary,
                        inactiveTrackColor = playerColors.onSurface.copy(alpha = 0.2f),
                        thumbColor = playerColors.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "1:24",
                        style = typography.labelSmallEmphasized,
                        color = playerColors.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "3:42",
                        style = typography.labelSmallEmphasized,
                        color = playerColors.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            // 5. PLAYBACK CONTROLS WITH EXPRESSIVE BUTTON MORPHS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Skip Previous
                IconButton(
                    onClick = onSkipPrevious,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = playerColors.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Play / Pause using M3E shapes.buttonMorph
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(shapes.buttonMorph) // Morph shape button
                        .background(playerColors.primary)
                        .clickable { onTogglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Skip Next
                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = playerColors.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

// Extension modifier to scale switch down slightly for smaller top row size
private fun Modifier.scale(scale: Float): Modifier = this.graphicsLayer {
    scaleX = scale
    scaleY = scale
}
