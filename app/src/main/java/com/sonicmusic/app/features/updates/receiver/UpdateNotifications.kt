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
import java.util.concurrent.atomic.AtomicReference

internal object UpdateNotifications {
    const val CHANNEL = "update_channel"
    private const val ID = 1001
    private data class Confirmation(val sessionId: Int, val intent: Intent)
    private val confirmation = AtomicReference<Confirmation?>()

    fun requestConfirmation(context: Context, sessionId: Int, message: String, action: Intent) {
        confirmation.set(Confirmation(sessionId, action))
        show(context, message, action)
    }

    fun hasConfirmation(sessionId: Int) = confirmation.get()?.sessionId == sessionId

    fun openConfirmation(context: Context, sessionId: Int): Boolean {
        val action = confirmation.get()?.takeIf { it.sessionId == sessionId } ?: return false
        return runCatching { context.startActivity(action.intent) }
            .onFailure { confirmation.compareAndSet(action, null) }
            .isSuccess
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
        confirmation.set(null)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
    }
}
