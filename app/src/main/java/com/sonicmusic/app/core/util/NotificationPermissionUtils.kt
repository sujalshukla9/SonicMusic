package com.sonicmusic.app.core.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Extension function to check if the app has notification permission.
 *
 * - On Android 13+ (TIRAMISU / API 33): returns `true` only if
 *   [Manifest.permission.POST_NOTIFICATIONS] is granted.
 * - On Android 12 and below: notifications are allowed by default,
 *   so this always returns `true`.
 */
fun Context.hasNotificationPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true // No runtime permission needed before API 33
    }
}
