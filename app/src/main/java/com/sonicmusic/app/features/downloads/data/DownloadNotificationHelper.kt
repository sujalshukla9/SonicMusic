package com.sonicmusic.app.features.downloads.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sonicmusic.app.R
import com.sonicmusic.app.core.util.hasNotificationPermission
import com.sonicmusic.app.app.MainActivity
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
        // minSdk 26 — NotificationChannel is always available
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows download progress"
            setShowBadge(false)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun safeNotify(id: Int, notification: android.app.Notification) {
        if (!context.hasNotificationPermission()) return
        try {
            notificationManager.notify(id, notification)
        } catch (_: SecurityException) {
            // Ignored if notification permission was revoked at runtime
        }
    }

    fun showProgress(songId: String, title: String, progress: Int) {
        val notificationId = songId.hashCode().and(0x7FFFFFFF) // Ensure positive ID, avoid collisions with system IDs

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading $title")
            .setContentText("$progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setGroup(GROUP_KEY_DOWNLOADS)
            .setOnlyAlertOnce(true)

        safeNotify(notificationId, builder.build())
    }

    fun showIndeterminate(songId: String, title: String) {
        val notificationId = songId.hashCode().and(0x7FFFFFFF)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading $title")
            .setContentText("Starting...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setGroup(GROUP_KEY_DOWNLOADS)
            .setOnlyAlertOnce(true)

        safeNotify(notificationId, builder.build())
    }

    fun showComplete(songId: String, title: String) {
        val notificationId = songId.hashCode().and(0x7FFFFFFF)
        
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

        safeNotify(notificationId, builder.build())
    }

    fun showError(songId: String, title: String, error: String?) {
        val notificationId = songId.hashCode().and(0x7FFFFFFF)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download Failed")
            .setContentText(error ?: "Could not download $title")
            .setStyle(NotificationCompat.BigTextStyle().bigText(error ?: "Could not download $title"))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY_DOWNLOADS)

        safeNotify(notificationId, builder.build())
    }
    
    fun cancel(songId: String) {
        notificationManager.cancel(songId.hashCode().and(0x7FFFFFFF))
    }
}
