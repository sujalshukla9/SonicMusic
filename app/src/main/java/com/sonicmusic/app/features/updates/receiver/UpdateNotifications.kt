package com.sonicmusic.app.features.updates.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sonicmusic.app.R
import com.sonicmusic.app.app.MainActivity
import com.sonicmusic.app.core.util.hasNotificationPermission

internal object UpdateNotifications {
    private const val CHANNEL = "update_channel"
    private const val ID = 1001
    private var confirmation: Intent? = null

    fun requestConfirmation(context: Context, message: String, action: Intent) {
        confirmation = action
        show(context, message, action)
    }

    fun openConfirmation(context: Context): Boolean {
        val action = confirmation ?: return false
        return runCatching { context.startActivity(action) }.isSuccess
    }

    fun show(context: Context, message: String, action: Intent = Intent(context, MainActivity::class.java)) {
        if (!context.hasNotificationPermission()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(context, ID, action, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SonicMusic update")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ID, notification) }
    }

    fun clear(context: Context) {
        confirmation = null
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
    }
}
