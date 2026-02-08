package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.HomeContent
import kotlinx.coroutines.flow.Flow

interface RecommendationRepository {
    suspend fun getQuickPicks(limit: Int = 20): Result<List<com.sonicmusic.app.domain.model.Song>>
    suspend fun getForgottenFavorites(limit: Int = 15): Result<List<com.sonicmusic.app.domain.model.Song>>
    suspend fun getTopArtistSongs(limit: Int = 8): Result<List<com.sonicmusic.app.domain.model.ArtistSection>>
    fun getHomeContent(): Flow<Result<HomeContent>>
}