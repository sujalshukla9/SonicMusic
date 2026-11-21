package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.AlbumDetails

interface AlbumRepository {
    suspend fun getAlbumDetails(browseId: String, forceRefresh: Boolean = false, append: Boolean = false): Result<AlbumDetails>
    suspend fun getCachedAlbumDetails(browseId: String): AlbumDetails? = null
}
