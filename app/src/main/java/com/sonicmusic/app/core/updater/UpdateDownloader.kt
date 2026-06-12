package com.sonicmusic.app.core.updater

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

    fun observeDownloadProgress(downloadId: Long): Flow<Int> = flow {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        var isDownloading = true

        while (isDownloading) {
            val cursor: Cursor = downloadManager.query(query)
            if (cursor.moveToFirst()) {
                val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                if (bytesDownloadedIndex >= 0 && bytesTotalIndex >= 0 && statusIndex >= 0) {
                    val bytesDownloaded = cursor.getInt(bytesDownloadedIndex)
                    val bytesTotal = cursor.getInt(bytesTotalIndex)
                    val status = cursor.getInt(statusIndex)

                    val progress = if (bytesTotal > 0) {
                        (bytesDownloaded * 100L / bytesTotal).toInt()
                    } else {
                        0
                    }

                    emit(progress)

                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                        isDownloading = false
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            emit(100)
                        }
                    }
                }
            } else {
                isDownloading = false
            }
            cursor.close()
            if (isDownloading) {
                delay(500)
            }
        }
    }
}
