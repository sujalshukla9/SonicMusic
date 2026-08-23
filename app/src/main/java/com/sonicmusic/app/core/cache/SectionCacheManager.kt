package com.sonicmusic.app.core.cache

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-section typed cache with individual TTLs.
 *
 * Replaces a monolithic ConcurrentHashMap with fine-grained expiration
 * policies per content type (Quick Picks 15m, Charts 1h, Artists 24h, etc.).
 *
 * Thread-safe: backed by ConcurrentHashMap, safe for concurrent coroutine access.
 *
 * Usage:
 * ```
 * val cached: List<Song>? = sectionCacheManager.get("quick_picks_US")
 * if (cached == null) {
 *     val fresh = fetchFromNetwork()
 *     sectionCacheManager.put("quick_picks_US", fresh, CacheTTL.QUICK_PICKS)
 * }
 * ```
 */
@Singleton
class SectionCacheManager @Inject constructor() {

    private data class CacheEntry<T>(
        val data: T,
        val cachedAt: Long,
        val ttlMs: Long
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
            (now - cachedAt) > ttlMs

        fun ageMs(now: Long = System.currentTimeMillis()): Long =
            now - cachedAt
    }

    private val cache = ConcurrentHashMap<String, CacheEntry<*>>()

    /**
     * Retrieve a cached value if it exists and hasn't expired.
     *
     * @param key Section cache key (e.g., " quick_picks_US")
     * @return The cached value, or null if missing or expired.
     */
    @Suppress(" UNCHECKED_CAST")
    fun <T> get(key: String): T? {
        val entry = cache[key] as? CacheEntry<T> ?: return null
        if (entry.isExpired()) {
            cache.remove(key)
            return null
        }
        return entry.data
    }

    /**
     * Retrieve a cached value even if expired (for stale-while-revalidate patterns).
     *
     * @param key Section cache key
     * @return Pair of (data, isExpired), or null if not cached at all.
     */
    @Suppress(" UNCHECKED_CAST")
    fun <T> getWithStaleness(key: String): Pair<T, Boolean>? {
        val entry = cache[key] as? CacheEntry<T> ?: return null
        return entry.data to entry.isExpired()
    }

    /**
     * Store a value with a specific TTL.
     *
     * @param key Section cache key
     * @param data The data to cache
     * @param ttlMs Time-to-live in milliseconds (use [CacheTTL] constants)
     */
    fun <T> put(key: String, data: T, ttlMs: Long) {
        cache[key] = CacheEntry(data, System.currentTimeMillis(), ttlMs)
    }

    /**
     * Invalidate a specific cache entry.
     */
    fun invalidate(key: String) {
        cache.remove(key)
    }

    /**
     * Invalidate all entries matching a prefix (e.g., " quick_picks_" to clear all regions).
     */
    fun invalidateByPrefix(prefix: String) {
        cache.keys.removeIf { it.startsWith(prefix) }
    }

    /**
     * Evict only expired entries — call periodically to reclaim memory.
     *
     * @return Number of entries evicted.
     */
    fun evictExpired(): Int {
        val now = System.currentTimeMillis()
        var count = 0
        cache.entries.removeIf { entry ->
            val expired = (entry.value as CacheEntry<*>).isExpired(now)
            if (expired) count++
            expired
        }
        return count
    }

    /**
     * Clear the entire cache (e.g., on logout or force-refresh).
     */
    fun clearAll() {
        cache.clear()
    }

    /**
     * Get diagnostic info: total entries, expired entries, cache keys.
     */
    fun diagnostics(): CacheDiagnostics {
        val now = System.currentTimeMillis()
        val entries = cache.entries.toList()
        val expired = entries.count { (it.value as CacheEntry<*>).isExpired(now) }
        return CacheDiagnostics(
            totalEntries = entries.size,
            expiredEntries = expired,
            activeEntries = entries.size - expired,
            keys = entries.map { it.key }
        )
    }

    data class CacheDiagnostics(
        val totalEntries: Int,
        val expiredEntries: Int,
        val activeEntries: Int,
        val keys: List<String>
    )
}
