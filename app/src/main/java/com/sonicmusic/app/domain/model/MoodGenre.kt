package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Domain model for a Mood or Genre category.
 *
 * Fetched from the YouTube Music `FEmusic_moods_and_genres` browse endpoint.
 * Each mood/genre chip links to a browse params string that can be used to
 * load a curated playlist of songs for that mood.
 *
 * @param title Display name (e.g. "Chill", "Workout", "Romance")
 * @param params Browse params token for loading the mood playlist
 * @param color Optional accent color (ARGB long) from the API for chip styling
 */
@Immutable
data class MoodGenre(
    val title: String,
    val params: String,
    val color: Long? = null
)
