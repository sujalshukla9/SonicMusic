package com.sonicmusic.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for caching browse-endpoint section data.
 *
 * Stores serialized JSON for each browse section (moods & genres, charts,
 * trending albums, trending artists, mixed for you) to support offline-first
 * architecture. Each entry has a TTL-based expiry for smart refresh.
 *
 * @param sectionKey Unique key for the section (e.g. "moods_and_genres", "charts_top_songs")
 * @param jsonData Serialized section data (JSON string)
 * @param cachedAt Timestamp when the data was cached
 * @param expiresAt Timestamp when the cache expires
 */
@Entity(tableName = "browse_section_cache")
data class BrowseSectionEntity(
    @PrimaryKey
    val sectionKey: String,
    val jsonData: String,
    val cachedAt: Long,
    val expiresAt: Long
)
