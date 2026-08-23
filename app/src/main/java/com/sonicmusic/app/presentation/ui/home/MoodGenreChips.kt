package com.sonicmusic.app.presentation.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonicmusic.app.domain.model.MoodGenre

import androidx.compose.foundation.border

/**
 * Horizontal scrollable row of mood/genre chips for the home page.
 *
 * Each chip has a gradient background (using the API-provided color or a
 * default palette) and tapping it invokes [onMoodGenreClick] with the
 * [MoodGenre.params] for loading the mood-specific playlist.
 */
@Composable
fun MoodGenreChips(
    moodGenres: List<MoodGenre>,
    onMoodGenreClick: (MoodGenre) -> Unit,
    modifier: Modifier = Modifier,
    selectedMoodGenre: MoodGenre? = null
) {
    if (moodGenres.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        // Section header
        Text(
            text = "Moods & Genres",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = moodGenres,
                key = { it.params }
            ) { moodGenre ->
                val isSelected = selectedMoodGenre?.params == moodGenre.params
                MoodGenreChip(
                    moodGenre = moodGenre,
                    isSelected = isSelected,
                    onClick = { onMoodGenreClick(moodGenre) }
                )
            }
        }
    }
}

/**
 * Individual mood/genre chip with gradient background.
 */
@Composable
private fun MoodGenreChip(
    moodGenre: MoodGenre,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chipColor = remember(moodGenre.color) {
        moodGenre.color?.let { Color(it) } ?: getMoodColor(moodGenre.title)
    }

    val gradientBrush = Brush.horizontalGradient(
        colors = if (isSelected) {
            listOf(
                chipColor.copy(alpha = 1.0f),
                chipColor.copy(alpha = 0.85f)
            )
        } else {
            listOf(
                chipColor.copy(alpha = 0.85f),
                chipColor.copy(alpha = 0.55f)
            )
        }
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(20.dp)
                    )
                } else Modifier
            )
            .background(gradientBrush)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = moodGenre.title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Default color palette for mood/genre chips when the API doesn't
 * provide a color. Maps common mood/genre names to curated colors.
 */
private fun getMoodColor(title: String): Color {
    val lower = title.lowercase()
    return when {
        lower.contains("chill") || lower.contains("relax") -> Color(0xFF5B86E5)
        lower.contains("workout") || lower.contains("energy") || lower.contains("pump") -> Color(0xFFFF6B35)
        lower.contains("romance") || lower.contains("love") -> Color(0xFFE84393)
        lower.contains("party") || lower.contains("dance") -> Color(0xFFA855F7)
        lower.contains("sad") || lower.contains("melancholy") -> Color(0xFF6B7280)
        lower.contains("focus") || lower.contains("study") -> Color(0xFF10B981)
        lower.contains("sleep") || lower.contains("calm") -> Color(0xFF3B82F6)
        lower.contains("happy") || lower.contains("feel good") -> Color(0xFFFBBF24)
        lower.contains("rock") -> Color(0xFFDC2626)
        lower.contains("pop") -> Color(0xFFEC4899)
        lower.contains("hip") || lower.contains("rap") -> Color(0xFF8B5CF6)
        lower.contains("country") -> Color(0xFFF59E0B)
        lower.contains("jazz") || lower.contains("blues") -> Color(0xFF0EA5E9)
        lower.contains("classical") -> Color(0xFF7C3AED)
        lower.contains("indie") || lower.contains("alternative") -> Color(0xFF14B8A6)
        lower.contains("r&b") || lower.contains("soul") -> Color(0xFFD946EF)
        lower.contains("electronic") || lower.contains("edm") -> Color(0xFF06B6D4)
        lower.contains("metal") -> Color(0xFF991B1B)
        lower.contains("folk") || lower.contains("acoustic") -> Color(0xFF78716C)
        lower.contains("latin") || lower.contains("reggaeton") -> Color(0xFFF97316)
        lower.contains("bollywood") || lower.contains("hindi") -> Color(0xFFEF4444)
        lower.contains("k-pop") || lower.contains("korean") -> Color(0xFFF472B6)
        else -> {
            // Hash-based color for consistent but varied colors
            val hue = (title.hashCode() and 0x7FFFFFFF) % 360
            Color.hsl(hue.toFloat(), 0.6f, 0.5f)
        }
    }
}
