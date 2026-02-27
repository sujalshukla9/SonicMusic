package com.sonicmusic.app.data.repository

data class ArtistCachePolicy(
    val artistPageTtlMs: Long = 30 * 60 * 1000L,
    val artistSectionTtlMs: Long = 60 * 60 * 1000L,
    val staleToleranceMs: Long = 24 * 60 * 60 * 1000L,
    val purgeAfterMs: Long = 7 * 24 * 60 * 60 * 1000L
) {
    fun isExpired(fetchedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - fetchedAtMs > artistPageTtlMs

    fun isStaleBeyondTolerance(fetchedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - fetchedAtMs > staleToleranceMs
}
