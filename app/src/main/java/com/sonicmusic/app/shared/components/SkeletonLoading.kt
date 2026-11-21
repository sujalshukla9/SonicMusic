package com.sonicmusic.app.shared.components

import androidx.compose.runtime.getValue
import com.sonicmusic.app.theme.MotionTokens
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════════
// SHIMMER EFFECT — M3 Expressive
// ═══════════════════════════════════════════════════════════════════

private val LocalShimmerProgress = compositionLocalOf<State<Float>?> { null }

@Composable
private fun rememberShimmerProgress(): State<Float> {
    if (com.sonicmusic.app.theme.shouldReduceMotion()) {
        return remember { mutableFloatStateOf(-1f) }
    }
    return rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = MotionTokens.linearTween(MotionTokens.ShimmerPeriod),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )
}

@Composable
private fun SkeletonShimmer(content: @Composable () -> Unit) {
    val progress = LocalShimmerProgress.current ?: rememberShimmerProgress()
    CompositionLocalProvider(LocalShimmerProgress provides progress, content = content)
}

/**
 * A single shimmer placeholder box with M3 expressive shapes.
 */
@Composable
private fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp)
) {
    val progress = LocalShimmerProgress.current ?: rememberShimmerProgress()
    val base = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f)
    Box(modifier.clip(shape).drawWithCache {
        val bandWidth = 120.dp.toPx()
        val brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, highlight, Color.Transparent),
            start = Offset.Zero,
            end = Offset(bandWidth, bandWidth)
        )
        onDrawBehind {
            drawRect(base)
            val fraction = progress.value
            if (fraction >= 0f) {
                val x = fraction * (size.width + size.height + 2 * bandWidth) - size.height - 2 * bandWidth
                translate(left = x) {
                    drawRect(brush, topLeft = Offset(-x, 0f), size = size)
                }
            }
        }
    })
}

// ═══════════════════════════════════════════════════════════════════
// SKELETON SCREENS
// ═══════════════════════════════════════════════════════════════════

/**
 * Song list skeleton — used by LikedSongs, RecentlyPlayed, Downloads,
 * LocalSongs, PlaylistDetail, HomeSectionDetail, Queue
 */
@Composable
fun SongListSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 8,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) = SkeletonShimmer {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        userScrollEnabled = false
    ) {
        items(itemCount) {
            SongRowSkeleton()
        }
    }
}

/**
 * Single song row skeleton — thumbnail + title line + artist line
 */
@Composable
fun SongRowSkeleton(modifier: Modifier = Modifier) = SkeletonShimmer {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail placeholder
        ShimmerBox(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Title line
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp),
                shape = RoundedCornerShape(6.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Artist line
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(11.dp),
                shape = RoundedCornerShape(6.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Duration / action placeholder
        ShimmerBox(
            modifier = Modifier
                .width(32.dp)
                .height(11.dp),
            shape = RoundedCornerShape(6.dp)
        )
    }
}

/**
 * Artist list skeleton — circular avatar + name + subtitle
 */
@Composable
fun ArtistListSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 10,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) = SkeletonShimmer {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        userScrollEnabled = false
    ) {
        items(itemCount) {
            ArtistRowSkeleton()
        }
    }
}

@Composable
fun ArtistRowSkeleton(modifier: Modifier = Modifier) = SkeletonShimmer {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular avatar
        ShimmerBox(
            modifier = Modifier.size(56.dp),
            shape = CircleShape
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(14.dp),
                shape = RoundedCornerShape(6.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(11.dp),
                shape = RoundedCornerShape(6.dp)
            )
        }
    }
}

/**
 * Home screen skeleton — greeting + horizontal card rows + song list
 */
@Composable
fun HomeScreenSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) = SkeletonShimmer {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        userScrollEnabled = false
    ) {
        // Greeting placeholder
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                ShimmerBox(
                    modifier = Modifier
                        .width(140.dp)
                        .height(18.dp),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                ShimmerBox(
                    modifier = Modifier
                        .width(200.dp)
                        .height(14.dp),
                    shape = RoundedCornerShape(6.dp)
                )
            }
        }

        // Two horizontal card row sections
        items(2) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                // Section title
                ShimmerBox(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .width(120.dp)
                        .height(16.dp),
                    shape = RoundedCornerShape(6.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Horizontal cards
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false
                ) {
                    items(4) {
                        Column {
                            ShimmerBox(
                                modifier = Modifier.size(140.dp),
                                shape = RoundedCornerShape(14.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            ShimmerBox(
                                modifier = Modifier
                                    .width(100.dp)
                                    .height(12.dp),
                                shape = RoundedCornerShape(6.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            ShimmerBox(
                                modifier = Modifier
                                    .width(70.dp)
                                    .height(10.dp),
                                shape = RoundedCornerShape(6.dp)
                            )
                        }
                    }
                }
            }
        }

        // Song list rows
        items(4) {
            SongRowSkeleton()
        }
    }
}

/**
 * Artist profile skeleton — hero image + info + song list
 */
@Composable
fun ArtistProfileSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) = SkeletonShimmer {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        userScrollEnabled = false
    ) {
        // Hero image
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ShimmerBox(
                    modifier = Modifier.size(160.dp),
                    shape = CircleShape
                )
                Spacer(modifier = Modifier.height(16.dp))
                ShimmerBox(
                    modifier = Modifier
                        .width(180.dp)
                        .height(22.dp),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                ShimmerBox(
                    modifier = Modifier
                        .width(120.dp)
                        .height(14.dp),
                    shape = RoundedCornerShape(6.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Action buttons placeholder
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ShimmerBox(
                        modifier = Modifier
                            .width(100.dp)
                            .height(36.dp),
                        shape = RoundedCornerShape(18.dp)
                    )
                    ShimmerBox(
                        modifier = Modifier
                            .width(100.dp)
                            .height(36.dp),
                        shape = RoundedCornerShape(18.dp)
                    )
                }
            }
        }

        // Section title
        item {
            ShimmerBox(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .width(100.dp)
                    .height(16.dp),
                shape = RoundedCornerShape(6.dp)
            )
        }

        // Top songs
        items(5) {
            SongRowSkeleton()
        }

        // Album section title
        item {
            ShimmerBox(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .width(80.dp)
                    .height(16.dp),
                shape = RoundedCornerShape(6.dp)
            )
        }

        // Album horizontal cards
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = false
            ) {
                items(4) {
                    Column {
                        ShimmerBox(
                            modifier = Modifier.size(140.dp),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        ShimmerBox(
                            modifier = Modifier
                                .width(100.dp)
                                .height(12.dp),
                            shape = RoundedCornerShape(6.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Library screen skeleton — grid of cards
 */
@Composable
fun LibraryScreenSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) = SkeletonShimmer {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Quick stats row
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp),
                shape = RoundedCornerShape(16.dp)
            )
            ShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Grid cards
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShimmerBox(
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp),
                    shape = RoundedCornerShape(14.dp)
                )
                ShimmerBox(
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }

        // Recent songs section
        ShimmerBox(
            modifier = Modifier
                .width(140.dp)
                .height(16.dp),
            shape = RoundedCornerShape(6.dp)
        )
        repeat(3) {
            SongRowSkeleton()
        }
    }
}

/**
 * Search results skeleton
 */
@Composable
fun SearchResultsSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) = SkeletonShimmer {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        userScrollEnabled = false
    ) {
        // Top result card
        item {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(100.dp),
                shape = RoundedCornerShape(16.dp)
            )
        }

        items(8) {
            SongRowSkeleton()
        }
    }
}
