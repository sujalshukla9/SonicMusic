package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class LocalSong(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Int,
    val filePath: String,
    val albumId: Long? = null,
    val dateAdded: Long,
    val fileSize: Long
)