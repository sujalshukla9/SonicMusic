package com.sonicmusic.app.data.remote.source

/**
 * Centralized configuration for YouTube Music InnerTube integration across SonicMusic.
 * 
 * Satisfies the core architecture requirements:
 * 1. Single source-of-truth for YouTube Music client identity (WEB_REMIX, music.youtube.com, WEB).
 * 2. Dynamic, configurable recommendation weights (Region, History, Liked, Artists, Recent, Discovery).
 * 3. Configurable song filtering and optional title filters without rigid hardcoding.
 * 4. Cache TTL, retry limits, and pagination parameters.
 */
object InnertubeConfig {

    const val SERVICE = "YouTube Music"

    object ClientContext {
        const val CLIENT_NAME = "WEB_REMIX"
        const val CLIENT_VERSION = "1.20260601.01.00"
        const val HOST = "music.youtube.com"
        // HardwarePlatform enum in InnerTube requires DESKTOP (or MOBILE) for WEB_REMIX; WEB causes HTTP 400
        const val PLATFORM = "DESKTOP"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    }

    /**
     * Dynamic recommendation weights for personalized recommendation scoring.
     * All weights sum to 1.0 and are configurable at runtime.
     */
    data class RecommendationWeights(
        val region: Double = 0.20,
        val listeningHistory: Double = 0.25,
        val likedSongs: Double = 0.20,
        val favoriteArtists: Double = 0.15,
        val recentBehavior: Double = 0.10,
        val discovery: Double = 0.10
    ) {
        init {
            // Ensure positive weights
            require(region >= 0 && listeningHistory >= 0 && likedSongs >= 0 &&
                    favoriteArtists >= 0 && recentBehavior >= 0 && discovery >= 0) {
                "Recommendation weights must be non-negative"
            }
        }
    }

    @Volatile
    var recommendationWeights: RecommendationWeights = RecommendationWeights()

    /**
     * Maximum duration for an individual song (11 minutes / 660 seconds).
     * Any track exceeding this is virtually always a full album, DJ set, nonstop mix, or jukebox.
     */
    const val MAX_INDIVIDUAL_SONG_DURATION_SECONDS = 660

    /**
     * Regex matching non-single compilations, spam channels, full albums, DJ mixes, and jukeboxes.
     * Note: Does not reject normal song titles with "remix", "hits", or "mashup".
     */
    val COMPILATION_OR_SPAM_REGEX = Regex(
        "(?i)(\\bjukebox\\b|\\baudio\\s+jukebox\\b|\\bvideo\\s+jukebox\\b|\\bfull\\s+album\\b|\\bcomplete\\s+album\\b|\\bnon-?stop\\s+(audio|jukebox|mix|party)\\b|\\b\\d{2,}\\s+songs\\s+in\\s+one\\b|\\b\\d{2,}\\s+songs\\s+jukebox\\b|journeys?\\s+of\\s+melod)"
    )

    /**
     * Configurable song filtering rules.
     */
    data class SongFilterConfig(
        val preferOfficialAudio: Boolean = true,
        val rejectUgc: Boolean = true,
        val rejectPodcasts: Boolean = true,
        val applyTitleFilters: Boolean = true,
        val optionalTitleFilterKeywords: Set<String> = setOf(
            "DJ",
            "Remix",
            "Mashup",
            "Best Of",
            "Hits Of",
            "Top 10"
        )
    )

    @Volatile
    var songFilterConfig: SongFilterConfig = SongFilterConfig()

    /**
     * Single source-of-truth validator for individual authentic music tracks.
     * Rejects compilations ("Jukebox", "Full Album", "Nonstop Mix" exceeding 11 minutes).
     */
    fun isAuthenticSong(title: String, artist: String = "", durationSeconds: Int = 0): Boolean {
        if (title.isBlank()) return false
        if (durationSeconds > MAX_INDIVIDUAL_SONG_DURATION_SECONDS) return false
        if (COMPILATION_OR_SPAM_REGEX.containsMatchIn(title)) return false
        if (artist.isNotBlank() && (artist.contains("jukebox", ignoreCase = true) || artist.contains("full album", ignoreCase = true))) {
            return false
        }
        return true
    }

    /**
     * Network and caching configuration.
     */
    object NetworkAndCache {
        const val DEFAULT_TIMEOUT_SECONDS = 15L
        const val MAX_RETRIES = 2
        const val RETRY_BACKOFF_BASE_MS = 500L
        const val SEARCH_PAGE_SIZE = 40
        const val HOME_PAGE_SIZE = 50
        const val IN_MEMORY_CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
        const val REGION_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours before refresh
    }

    /**
     * Home section visibility and ordering configuration.
     */
    object HomeSections {
        const val SECTION_LISTEN_AGAIN = "Listen Again"
        const val SECTION_QUICK_PICKS = "Quick Picks"
        const val SECTION_RECOMMENDED = "Recommended"
        const val SECTION_NEW_RELEASES = "New Releases"
        const val SECTION_CHARTS = "Charts"
        const val SECTION_TRENDING_MUSIC = "Trending Music"
        const val SECTION_TRENDING_ALBUMS = "Trending Albums"
        const val SECTION_TRENDING_ARTISTS = "Trending Artists"
        const val SECTION_MOODS_GENRES = "Moods & Genres"

        val ACTIVE_SECTIONS = listOf(
            SECTION_LISTEN_AGAIN,
            SECTION_QUICK_PICKS,
            SECTION_RECOMMENDED,
            SECTION_NEW_RELEASES,
            SECTION_CHARTS,
            SECTION_TRENDING_MUSIC,
            SECTION_TRENDING_ALBUMS,
            SECTION_TRENDING_ARTISTS,
            SECTION_MOODS_GENRES
        )

        val REMOVED_SECTIONS = emptySet<String>()
    }
}
