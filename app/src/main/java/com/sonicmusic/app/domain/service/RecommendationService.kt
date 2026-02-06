package com.sonicmusic.app.domain.service

import com.sonicmusic.app.domain.model.Song

interface RecommendationService {
    suspend fun getPersonalizedSongs(limit: Int): Result<List<Song>>
    suspend fun getForgottenFavorites(limit: Int): Result<List<Song>>
    suspend fun getTopArtistSongs(limit: Int): Result<List<Song>> // Simplified return type for now
}
