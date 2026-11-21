package com.sonicmusic.app.features.updates.receiver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.sonicmusic.app.BuildConfig
import com.sonicmusic.app.core.updater.UpdateDownloader

class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val downloader = UpdateDownloader(context)
        val pending = downloader.pending() ?: return
        when (intent.action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == pending.id) {
                    downloader.scheduleVerification(pending.id)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (BuildConfig.VERSION_CODE >= pending.update.versionCode) {
                    downloader.finish(pending.id, "SonicMusic was updated successfully.")
                    UpdateNotifications.clear(context)
                }
            }
            INSTALL_RESULT -> handleInstallResult(context, intent, downloader, pending)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleInstallResult(context: Context, intent: Intent, downloader: UpdateDownloader, pending: UpdateDownloader.Pending) {
        val sessionId = intent.getIntExtra(EXPECTED_SESSION, -1)
        if (sessionId < 0 || sessionId != pending.sessionId) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            }
            if (confirmation != null) {
                val message = "Update verified. Confirm installation to continue."
                downloader.message(message)
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                UpdateNotifications.requestConfirmation(context, message, confirmation)
                if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    UpdateNotifications.openConfirmation(context)
                }
                return
            }
        }

        val message = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "SonicMusic was updated successfully."
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled. Your installed version is ready to use."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the update. Check your install permissions."
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "This update conflicts with the installed application."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update is incompatible with your Android version or device."
            PackageInstaller.STATUS_FAILURE_INVALID -> "Android rejected the update APK. Download it again."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn’t enough storage to install the update."
            else -> "Installation failed. Your installed version is ready to use."
        }
        downloader.finish(pending.id, message)
        UpdateNotifications.clear(context)
        if (status != PackageInstaller.STATUS_SUCCESS) UpdateNotifications.show(context, message)
    }

    companion object {
        const val INSTALL_RESULT = "com.sonicmusic.app.UPDATE_INSTALL_RESULT"
        const val EXPECTED_SESSION = "sonicmusicUpdateSession"
    }
}
