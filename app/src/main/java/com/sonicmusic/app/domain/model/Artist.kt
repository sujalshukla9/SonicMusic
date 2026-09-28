package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Artist(
    val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val songCount: Int = 0,
    val playCount: Int = 0,
    val topSongIds: List<String> = emptyList()
)