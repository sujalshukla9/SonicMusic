package com.sonicmusic.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sonicmusic.app.BuildConfig
import com.sonicmusic.app.R
import com.sonicmusic.app.core.updater.GitHubUpdater
import com.sonicmusic.app.core.updater.UpdateDownloader
import com.sonicmusic.app.data.repository.SettingsRepository
import com.sonicmusic.app.presentation.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@HiltWorker
class AutoUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "AutoUpdateWorker"
        private const val CHANNEL_ID = "update_channel"
        private const val NOTIFICATION_ID = 1001
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
            val updater = GitHubUpdater(context)
            val updateInfo = updater.checkForUpdates(BuildConfig.APP_VERSION)
            
            if (updateInfo?.hasUpdate == true && updateInfo.downloadUrl.isNotBlank()) {
                Log.d(TAG, "Update available: ${updateInfo.latestVersion}. Downloading APK...")
                
                // Download the APK directly — AppUpdateReceiver will handle installation
                val downloader = UpdateDownloader(context)
                downloader.downloadApk(
                    url = updateInfo.downloadUrl,
                    fileName = "SonicMusic-${updateInfo.latestVersion}.apk"
                )
                
                // Also show a notification so the user knows
                showUpdateNotification(updateInfo.latestVersion)
            } else {
                Log.d(TAG, "App is up to date.")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in AutoUpdateWorker", e)
            Result.retry()
        }
    }

    private fun showUpdateNotification(version: String) {
        val notificationManager = NotificationManagerCompat.from(context)
        
        // Ensure POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission. Cannot show update notification.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for new app updates"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Updating SonicMusic")
            .setContentText("Version $version is downloading. Tap to open app.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
