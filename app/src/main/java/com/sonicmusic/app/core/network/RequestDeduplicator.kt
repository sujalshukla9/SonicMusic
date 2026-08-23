package com.sonicmusic.app.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coalesces identical in-flight network requests.
 *
 * Problem: When multiple UI components or coroutines request the same data
 * simultaneously (e.g., home feed on rotation + pull-to-refresh), duplicate
 * network calls fire. This wastes bandwidth, increases latency, and can
 * trigger rate limiting.
 *
 * Solution: The first caller fires the real request. Subsequent callers with
 * the same key join the existing in-flight Deferred instead of creating a new one.
 *
 * Usage:
 * ```
 * val result = requestDeduplicator.deduplicate("home_feed_US", viewModelScope) {
 *     innertubeHomeRepository.getHomeFeed()
 * }
 * ```
 *
 * Thread-safety: Backed by ConcurrentHashMap. Safe for concurrent coroutine access.
 */
@Singleton
class RequestDeduplicator @Inject constructor() {

    private val inflightRequests = ConcurrentHashMap<String, Deferred<*>>()

    /**
     * Execute [block] or join an existing in-flight request with the same [key].
     *
     * @param key Unique identifier for the request (e.g., " home_feed_US", " search_taylor")
     * @param scope CoroutineScope to launch the deferred in (determines lifecycle/cancellation)
     * @param block The actual suspend work to execute
     * @return Result of the block
     */
    @Suppress(" UNCHECKED_CAST")
    suspend fun <T> deduplicate(
        key: String,
        scope: CoroutineScope,
        block: suspend () -> T
    ): T {
        // Fast path: join existing in-flight request
        val existing = inflightRequests[key] as? Deferred<T>
        if (existing != null && existing.isActive) {
            return existing.await()
        }

        // Slow path: fire new request
        val deferred = scope.async(Dispatchers.IO) {
            try {
                block()
            } finally {
                inflightRequests.remove(key)
            }
        }
        inflightRequests[key] = deferred
        return deferred.await()
    }

    /**
     * Check if a request with the given key is currently in-flight.
     */
    fun isInflight(key: String): Boolean {
        val deferred = inflightRequests[key]
        return deferred != null && deferred.isActive
    }

    /**
     * Cancel an in-flight request (e.g., on navigation away).
     */
    fun cancel(key: String) {
        inflightRequests.remove(key)?.cancel()
    }

    /**
     * Cancel all in-flight requests.
     */
    fun cancelAll() {
        inflightRequests.values.forEach { it.cancel() }
        inflightRequests.clear()
    }

    /**
     * Get the number of currently in-flight requests (for diagnostics).
     */
    fun inflightCount(): Int = inflightRequests.count { it.value.isActive }
}
