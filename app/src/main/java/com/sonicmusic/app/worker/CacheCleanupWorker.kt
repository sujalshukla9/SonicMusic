package com.sonicmusic.app.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sonicmusic.app.core.cache.SectionCacheManager
import com.sonicmusic.app.data.local.dao.BrowseSectionDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that cleans up expired cache entries.
 *
 * Runs every 6 hours to:
 * 1. Evict expired entries from in-memory SectionCacheManager
 * 2. Delete expired rows from Room browse_section_cache table
 *
 * This prevents unbounded memory/disk growth without requiring
 * manual cleanup calls from the app code.
 */
@HiltWorker
class CacheCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sectionCacheManager: SectionCacheManager,
    private val browseSectionDao: BrowseSectionDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "CacheCleanup"
        private const val WORK_NAME = " cache_cleanup_periodic"

        /**
         * Schedule the periodic cache cleanup.
         * Call once during app startup (idempotent — KEEP policy).
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CacheCleanupWorker>(
                6, TimeUnit.HOURS,           // repeat interval
                30, TimeUnit.MINUTES         // flex interval
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already scheduled
                request
            )
            Log.d(TAG, " Scheduled periodic cache cleanup (every 6h)")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()

            // 1. Evict expired in-memory cache entries
            val memEvicted = sectionCacheManager.evictExpired()

            // 2. Delete expired Room DB cache entries
            val dbEvicted = browseSectionDao.clearExpired(now)

            // 3. Log diagnostics
            val diag = sectionCacheManager.diagnostics()
            Log.d(
                TAG,
                " Cache cleanup complete: " +
                    " memEvicted=$memEvicted dbEvicted=$dbEvicted " +
                    " memActive=${diag.activeEntries} memTotal=${diag.totalEntries}"
            )

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, " Cache cleanup failed", e)
            Result.retry()
        }
    }
}
