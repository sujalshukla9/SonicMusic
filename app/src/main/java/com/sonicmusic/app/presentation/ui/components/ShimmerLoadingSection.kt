package com.sonicmusic.app.presentation.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reusable shimmer/skeleton loading effect for home page sections.
 *
 * Displays animated placeholder cards while content is loading.
 * Uses [rememberInfiniteTransition] with a linear gradient sweep
 * to create the shimmer effect.
 */
@Composable
fun ShimmerLoadingSection(
    modifier: Modifier = Modifier,
    itemCount: Int = 5,
    cardStyle: ShimmerCardStyle = ShimmerCardStyle.MEDIUM_CARD
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        start = Offset(shimmerTranslate - 300f, shimmerTranslate - 300f),
        end = Offset(shimmerTranslate, shimmerTranslate)
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Section title placeholder
        Box(
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, bottom = 12.dp)
                .width(120.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerBrush)
        )

        when (cardStyle) {
            ShimmerCardStyle.MEDIUM_CARD -> ShimmerMediumCards(shimmerBrush, itemCount)
            ShimmerCardStyle.LARGE_SQUARE -> ShimmerLargeSquares(shimmerBrush, itemCount)
            ShimmerCardStyle.COMPACT_ROW -> ShimmerCompactRows(shimmerBrush, itemCount)
            ShimmerCardStyle.CIRCLE -> ShimmerCircles(shimmerBrush, itemCount)
            ShimmerCardStyle.CHIP -> ShimmerChips(shimmerBrush, itemCount)
        }
    }
}

/**
 * Card styles for shimmer loading effect.
 */
enum class ShimmerCardStyle {
    MEDIUM_CARD,
    LARGE_SQUARE,
    COMPACT_ROW,
    CIRCLE,
    CHIP
}

@Composable
private fun ShimmerMediumCards(brush: Brush, count: Int) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        items(count) {
            Column {
                Box(
                    modifier = Modifier
                        .size(width = 140.dp, height = 140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(brush)
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(70.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
            }
        }
    }
}

@Composable
private fun ShimmerLargeSquares(brush: Brush, count: Int) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        items(count) {
            Column {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(brush)
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
            }
        }
    }
}

@Composable
private fun ShimmerCompactRows(brush: Brush, count: Int) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(count.coerceAtMost(5)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(brush)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(brush)
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(brush)
                    )
                }
            }
        }
    }
}

@Composable
private fun ShimmerCircles(brush: Brush, count: Int) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false
    ) {
        items(count) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(brush)
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
            }
        }
    }
}

@Composable
private fun ShimmerChips(brush: Brush, count: Int) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false
    ) {
        items(count) {
            Box(
                modifier = Modifier
                    .width(90.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(brush)
            )
        }
    }
}
