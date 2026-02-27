package com.sonicmusic.app.worker

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sonicmusic.app.BuildConfig
import com.sonicmusic.app.data.remote.source.AppUpdateService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@HiltWorker
class AutoUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val appUpdateService: AppUpdateService
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "AutoUpdateWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking for updates...")
            val result = appUpdateService.checkForUpdates(BuildConfig.APP_VERSION)
            
            result.onSuccess { updateCheck ->
                if (updateCheck.isUpdateAvailable && updateCheck.releaseUrl.endsWith(".apk")) {
                    Log.d(TAG, "Update available: ${updateCheck.latestVersion}. Downloading...")
                    downloadUpdate(updateCheck.releaseUrl, updateCheck.latestVersion)
                } else {
                    Log.d(TAG, "App is up to date.")
                }
                return@withContext Result.success()
            }.onFailure { e ->
                Log.e(TAG, "Failed to check for updates", e)
                return@withContext Result.retry()
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in AutoUpdateWorker", e)
            Result.retry()
        }
    }

    private fun downloadUpdate(downloadUrl: String, version: String) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(downloadUrl)
            
            // Destination file
            val fileName = "SonicMusic_Update_$version.apk"
            
            // Delete old update files if they exist to prevent accumulation
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            dir?.listFiles()?.forEach { file ->
                if (file.name.startsWith("SonicMusic_Update_") && file.name.endsWith(".apk")) {
                    file.delete()
                }
            }

            val request = DownloadManager.Request(uri)
                .setTitle("Downloading Sonic Music Update")
                .setDescription("Preparing to install version $version")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            downloadManager.enqueue(request)
            Log.d(TAG, "Download enqueued for version $version")
            
            // AppUpdateReceiver will handle the ACTION_DOWNLOAD_COMPLETE intent to prompt installation
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download", e)
        }
    }
}
