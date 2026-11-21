package com.sonicmusic.app.features.updates.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sonicmusic.app.core.cache.DiskCacheMaintenance
import com.sonicmusic.app.data.local.dao.BrowseSectionDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Maintains the disk cache away from the app's startup path.
 */
@HiltWorker
class CacheCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val diskCache: DiskCacheMaintenance,
    private val browseSectionDao: BrowseSectionDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "CacheCleanup"
        private const val WORK_NAME = "cache_cleanup_periodic"
        private const val LEGACY_WORK_NAME = " cache_cleanup_periodic"

        /**
         * Schedule the periodic cache cleanup.
         * Call once during app startup (idempotent — KEEP policy).
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CacheCleanupWorker>(
                6, TimeUnit.HOURS,           // repeat interval
                30, TimeUnit.MINUTES         // flex interval
            ).setInitialDelay(15, TimeUnit.MINUTES).build()

            try {
                val workManager = WorkManager.getInstance(context)
                workManager.cancelUniqueWork(LEGACY_WORK_NAME)
                workManager.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule cache maintenance", e)
            }
        }
    }

    override suspend fun doWork(): Result {
        return try {
            browseSectionDao.clearExpired(System.currentTimeMillis())
            diskCache.trimAndMeasure()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, " Cache cleanup failed", e)
            Result.retry()
        }
    }
}
