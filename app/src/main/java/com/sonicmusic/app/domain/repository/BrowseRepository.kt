package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.ChartSection
import com.sonicmusic.app.domain.model.MoodGenre
import com.sonicmusic.app.domain.model.Song

/**
 * Repository interface for YouTube Music browse-endpoint content.
 *
 * Handles fetching from `FEmusic_moods_and_genres`, `FEmusic_charts`,
 * `FEmusic_home` and artist/album discovery endpoints with caching
 * and continuation token support.
 */
interface BrowseRepository {

    /**
     * Fetch moods & genres from `FEmusic_moods_and_genres`.
     * Returns a list of [MoodGenre] chips for the home page.
     */
    suspend fun getMoodsAndGenres(): Result<List<MoodGenre>>

    /**
     * Fetch charts (Top Songs) with optional continuation.
     * Uses `FEmusic_charts` browse endpoint.
     *
     * @param continuationToken Token from a previous response for pagination
     * @return [ChartSection] with songs and optional next continuation token
     */
    suspend fun getCharts(continuationToken: String? = null): Result<ChartSection>

    /**
     * Fetch trending albums from YouTube Music.
     * Uses `getTopAlbums` from YouTubeiService.
     *
     * @param limit Maximum number of albums to return
     */
    suspend fun getTrendingAlbums(limit: Int = 20): Result<List<Song>>

    /**
     * Fetch trending artists from YouTube Music.
     * Uses `getTopArtists` from YouTubeiService.
     *
     * @param limit Maximum number of artists to return
     */
    suspend fun getTrendingArtists(limit: Int = 20): Result<List<Song>>

    /**
     * Fetch auto-generated mixes from `FEmusic_home` shelves.
     * Falls back to local history-based mixing if personalized
     * content is unavailable.
     *
     * @param limit Maximum number of songs to return
     */
    suspend fun getMixedForYou(limit: Int = 15): Result<List<Song>>

    /**
     * Fetch songs for a specific mood/genre playlist.
     *
     * @param params Browse params from [MoodGenre.params]
     * @param limit Maximum number of songs to return
     */
    suspend fun getMoodGenreSongs(params: String, limit: Int = 20): Result<List<Song>>
}
