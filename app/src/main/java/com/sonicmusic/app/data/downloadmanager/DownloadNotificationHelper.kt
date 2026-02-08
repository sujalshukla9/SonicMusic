package com.sonicmusic.app.data.downloadmanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sonicmusic.app.R
import com.sonicmusic.app.presentation.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class to manage download notifications
 */
@Singleton
class DownloadNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val CHANNEL_ID = "sonic_music_downloads"
        const val CHANNEL_NAME = "Downloads"
        const val GROUP_KEY_DOWNLOADS = "com.sonicmusic.DOWNLOADS"
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress"
                setShowBadge(false)
            }
            // Use system service directly for channel creation
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showProgress(songId: String, title: String, progress: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val notificationId = songId.hashCode()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading $title")
            .setContentText("$progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setGroup(GROUP_KEY_DOWNLOADS)
            .setOnlyAlertOnce(true)

        notificationManager.notify(notificationId, builder.build())
    }

    fun showIndeterminate(songId: String, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        
        val notificationId = songId.hashCode()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading $title")
            .setContentText("Starting...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setGroup(GROUP_KEY_DOWNLOADS)
            .setOnlyAlertOnce(true)

        notificationManager.notify(notificationId, builder.build())
    }

    fun showComplete(songId: String, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val notificationId = songId.hashCode()
        
        // Intent to open downloads (or main activity for now)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY_DOWNLOADS)

        notificationManager.notify(notificationId, builder.build())
    }

    fun showError(songId: String, title: String, error: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val notificationId = songId.hashCode()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download Failed")
            .setContentText(error ?: "Could not download $title")
            .setStyle(NotificationCompat.BigTextStyle().bigText(error ?: "Could not download $title"))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY_DOWNLOADS)

        notificationManager.notify(notificationId, builder.build())
    }
    
    fun cancel(songId: String) {
        notificationManager.cancel(songId.hashCode())
    }
}
