package com.sonicmusic.app.core.cache

/**
 * Cache TTL constants for different content types.
 *
 * These values balance freshness vs. network usage:
 * - Quick Picks: Short TTL — personalization changes with each play
 * - Charts/Trending: Medium TTL — updated multiple times per day
 * - Albums/Artists: Long TTL — metadata rarely changes
 * - Search: Very short TTL — results change with context
 */
object CacheTTL {
    /** Quick Picks — highly personalized, refresh often. */
    const val QUICK_PICKS = 15 * 60 * 1000L          // 15 minutes

    /** Home feed — balanced freshness/performance. */
    const val HOME_FEED = 30 * 60 * 1000L             // 30 minutes

    /** Trending songs — updates throughout the day. */
    const val TRENDING = 30 * 60 * 1000L              // 30 minutes

    /** New releases — updates a few times daily. */
    const val NEW_RELEASES = 30 * 60 * 1000L          // 30 minutes

    /** Charts — official charts update hourly. */
    const val CHARTS = 60 * 60 * 1000L                // 1 hour

    /** Personalized mixes — linked to taste profile. */
    const val PERSONALIZED = 30 * 60 * 1000L          // 30 minutes

    /** Mixed for you — curated playlists. */
    const val MIXED_FOR_YOU = 60 * 60 * 1000L         // 1 hour

    /** Mood & genres — catalog rarely changes. */
    const val MOOD_GENRES = 6 * 60 * 60 * 1000L       // 6 hours

    /** Album detail — metadata is mostly static. */
    const val ALBUMS = 12 * 60 * 60 * 1000L           // 12 hours

    /** Artist detail — bio/discography rarely changes. */
    const val ARTISTS = 24 * 60 * 60 * 1000L          // 24 hours

    /** Listen Again — local history, changes with play events. */
    const val LISTEN_AGAIN = 10 * 60 * 1000L          // 10 minutes

    /** English Hits — regional, moderate refresh. */
    const val ENGLISH_HITS = 60 * 60 * 1000L          // 1 hour

    /** Forgotten Favorites — recommendation-based, slow-changing. */
    const val FORGOTTEN_FAVORITES = 2 * 60 * 60 * 1000L  // 2 hours

    /** Search results — context-sensitive, short-lived. */
    const val SEARCH_RESULT = 5 * 60 * 1000L          // 5 minutes

    /** Search suggestions — very short-lived. */
    const val SEARCH_SUGGESTIONS = 2 * 60 * 1000L     // 2 minutes

    /** Stream URLs — expire per YouTube policy. */
    const val STREAM_URL = 30 * 60 * 1000L            // 30 minutes

    /** Trending albums — updates throughout the day. */
    const val TRENDING_ALBUMS = 60 * 60 * 1000L       // 1 hour
}
