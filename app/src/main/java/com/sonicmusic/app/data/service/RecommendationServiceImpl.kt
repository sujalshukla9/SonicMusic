package com.sonicmusic.app.data.service

import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.service.RecommendationService
import javax.inject.Inject

class RecommendationServiceImpl @Inject constructor() : RecommendationService {
    override suspend fun getPersonalizedSongs(limit: Int): Result<List<Song>> {
        return Result.success(emptyList())
    }

    override suspend fun getForgottenFavorites(limit: Int): Result<List<Song>> {
        return Result.success(emptyList())
    }

    override suspend fun getTopArtistSongs(limit: Int): Result<List<Song>> {
        return Result.success(emptyList())
    }
}
