package com.sonicmusic.app.core.updater

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log

class UpdateDownloader(private val context: Context) {

    companion object {
        private const val TAG = "UpdateDownloader"
        private const val PREFS_NAME = "app_update_prefs"
        private const val KEY_PENDING_DOWNLOAD_ID = "pending_update_download_id"

        fun getTrackedDownloadId(context: Context): Long {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_PENDING_DOWNLOAD_ID, -1L)
        }

        fun clearTrackedDownloadId(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PENDING_DOWNLOAD_ID)
                .apply()
        }
    }

    /**
     * Downloads the APK to the app-private external files directory.
     * This path is served by our FileProvider so AppUpdateReceiver
     * can hand a content:// URI to the package installer.
     */
    fun downloadApk(url: String, fileName: String = "SonicMusic-Update.apk"): Long {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Clean up old APK files to prevent accumulation
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        dir?.listFiles()?.forEach { file ->
            if (file.name.startsWith("SonicMusic") && file.name.endsWith(".apk")) {
                file.delete()
                Log.d(TAG, "Cleaned up old APK: ${file.name}")
            }
        }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading SonicMusic Update")
            .setDescription("Please wait while the update downloads...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadId = downloadManager.enqueue(request)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
            .apply()
        Log.d(TAG, "Download enqueued (id=$downloadId) for $fileName")
        return downloadId
    }
}
