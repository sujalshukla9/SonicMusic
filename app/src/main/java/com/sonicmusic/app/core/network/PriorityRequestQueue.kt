package com.sonicmusic.app.core.network

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Priority levels for network requests.
 *
 * Higher priorities get more concurrent slots, ensuring critical requests
 * (e.g., stream URL for immediate playback) are never starved by low-priority
 * prefetch work.
 */
enum class RequestPriority {
    /** Stream URLs for the currently playing track. */
    CRITICAL,
    /** Home feed, search results — user is actively waiting. */
    HIGH,
    /** Background section loading, lazy sections. */
    NORMAL,
    /** Recommendations, artist enrichment. */
    LOW,
    /** Speculative prefetch — next track, upcoming sections. */
    PREFETCH
}

/**
 * Limits concurrent network requests by priority tier.
 *
 * Prevents low-priority prefetch from saturating the connection pool
 * and starving high-priority requests (like stream URL resolution for playback).
 *
 * Slot allocation:
 * - CRITICAL: 8 concurrent (stream resolution must never wait)
 * - HIGH: 6 concurrent (home feed, search)
 * - NORMAL: 4 concurrent (background sections)
 * - LOW: 2 concurrent (recommendations)
 * - PREFETCH: 1 concurrent (speculative, expendable)
 *
 * Usage:
 * ```
 * val result = priorityRequestQueue.execute(RequestPriority.HIGH) {
 *     innertubeService.browse(...)
 * }
 * ```
 */
@Singleton
class PriorityRequestQueue @Inject constructor() {

    private val semaphores = mapOf(
        RequestPriority.CRITICAL to Semaphore(8),
        RequestPriority.HIGH     to Semaphore(6),
        RequestPriority.NORMAL   to Semaphore(4),
        RequestPriority.LOW      to Semaphore(2),
        RequestPriority.PREFETCH to Semaphore(1)
    )

    /**
     * Execute a suspend block under the concurrency limit for [priority].
     *
     * If all slots for the given priority are occupied, the caller suspends
     * until a slot becomes available (FIFO ordering).
     *
     * @param priority The priority tier for this request
     * @param block The suspend work to execute
     * @return Result of the block
     */
    suspend fun <T> execute(
        priority: RequestPriority = RequestPriority.NORMAL,
        block: suspend () -> T
    ): T {
        val semaphore = semaphores[priority]
            ?: semaphores.getValue(RequestPriority.NORMAL)
        return semaphore.withPermit { block() }
    }
}
