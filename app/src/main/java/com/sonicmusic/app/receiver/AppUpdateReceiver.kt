package com.sonicmusic.app.receiver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.sonicmusic.app.core.updater.UpdateDownloader
import java.io.File

class AppUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppUpdateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return
        val trackedId = UpdateDownloader.getTrackedDownloadId(context)
        if (trackedId != downloadId) {
            Log.d(TAG, "Ignoring unrelated download completion (id=$downloadId, tracked=$trackedId)")
            return
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)

        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return

            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex < 0 || cursor.getInt(statusIndex) != DownloadManager.STATUS_SUCCESSFUL) {
                Log.e(TAG, "Download failed or still in progress")
                UpdateDownloader.clearTrackedDownloadId(context)
                return
            }

            val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (uriIndex < 0) return

            val localUri = cursor.getString(uriIndex) ?: return
            Log.d(TAG, "Download complete: $localUri")

            // Only handle APK downloads
            if (!localUri.endsWith(".apk")) {
                Log.d(TAG, "Downloaded file is not an APK, ignoring.")
                return
            }

            val file = uriToFile(localUri)
            if (file == null || !file.exists()) {
                Log.e(TAG, "APK file not found for URI: $localUri")
                UpdateDownloader.clearTrackedDownloadId(context)
                return
            }

            installApk(context, file)
            UpdateDownloader.clearTrackedDownloadId(context)
        }
    }

    /**
     * Converts a DownloadManager local URI to a File object.
     * Handles both file:// URIs and content:// URIs.
     */
    private fun uriToFile(uriString: String): File? {
        return try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "file" -> File(uri.path!!)
                else -> {
                    // For content:// or other schemes, try path directly
                    uri.path?.let { File(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve file from URI: $uriString", e)
            null
        }
    }

    /**
     * Launches the Android package installer for the given APK file.
     * Uses FileProvider on Android N+ for secure URI sharing.
     */
    private fun installApk(context: Context, file: File) {
        try {
            val contentUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(installIntent)
            Log.d(TAG, "APK installer launched for: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installer", e)
        }
    }
}
