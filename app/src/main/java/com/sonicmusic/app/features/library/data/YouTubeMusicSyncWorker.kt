package com.sonicmusic.app.features.library.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class YouTubeMusicSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val library: YouTubeMusicLibrary
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        if (library.syncActiveAccount() || runAttemptCount >= 3) Result.success() else Result.retry()

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "youtube_music_periodic_sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<YouTubeMusicSyncWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                    .setConstraints(online).setInitialDelay(30, TimeUnit.SECONDS)
                    .build()
            )
        }

        fun accountConnected(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "youtube_music_account_sync", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<YouTubeMusicSyncWorker>().setConstraints(online).build()
            )
        }
    }
}
