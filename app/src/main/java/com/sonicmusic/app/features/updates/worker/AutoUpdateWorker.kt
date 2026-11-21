package com.sonicmusic.app.features.updates.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sonicmusic.app.BuildConfig
import com.sonicmusic.app.core.updater.UpdateDownloader
import com.sonicmusic.app.features.updates.data.AppUpdateService
import com.sonicmusic.app.data.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@HiltWorker
class AutoUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val appUpdateService: AppUpdateService
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "AutoUpdateWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Check if user disabled auto-updates
            val isAutoUpdateEnabled = settingsRepository.autoUpdateEnabled.first()
            if (!isAutoUpdateEnabled) {
                Log.d(TAG, "Auto updates are disabled by user. Skipping check.")
                return@withContext Result.success()
            }

            Log.d(TAG, "Checking for updates via WorkManager...")
            val updateInfo = appUpdateService.checkForUpdates(BuildConfig.VERSION_NAME).getOrElse { throw it }
            val updatePackage = updateInfo.updatePackage
            if (updatePackage != null) {
                UpdateDownloader(context).downloadApk(
                    updatePackage,
                    startMessage = "Version " + updateInfo.latestVersion + " is downloading."
                )
            } else if (updateInfo.isUpdateAvailable) {
                Log.w(TAG, "The latest release does not have a verified APK asset.")
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in AutoUpdateWorker", e)
            Result.retry()
        }
    }

}
