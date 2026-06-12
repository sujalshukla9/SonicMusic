package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Domain model for a Charts section on the home page.
 *
 * Wraps a list of charting songs with optional continuation token
 * for pagination and the country identifier for display purposes.
 *
 * @param title Section title (e.g. "Top Songs", "Trending")
 * @param songs Charting songs in ranked order
 * @param continuationToken Token for loading the next page of chart results
 * @param country Country code or display name for the chart region
 */
@Immutable
data class ChartSection(
    val title: String,
    val songs: List<Song>,
    val continuationToken: String? = null,
    val country: String? = null
)
