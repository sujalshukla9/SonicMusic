package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Clean data model representing a song on the home feed.
 */
@Immutable
@Serializable
data class HomeSong(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val thumbnailUrl: String,
    val durationText: String?
)

/**
 * Clean data model representing a section/shelf on the home feed containing a list of [HomeSong]s.
 */
@Immutable
@Serializable
data class HomeSection(
    val title: String,
    val songs: List<HomeSong>
)
