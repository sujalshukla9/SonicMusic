package com.sonicmusic.app.receiver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

class AppUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppUpdateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId == -1L) return

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)

            downloadManager.query(query).use { cursor ->
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex >= 0 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                        
                        val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        if (uriIndex >= 0) {
                            val uriString = cursor.getString(uriIndex)
                            Log.d(TAG, "Download successful: $uriString")
                            
                            val downloadedUri = Uri.parse(uriString)
                            installApk(context, downloadedUri)
                        }
                    } else {
                        Log.e(TAG, "Download failed or still in progress")
                    }
                }
            }
        }
    }

    private fun installApk(context: Context, uri: Uri) {
        try {
            val file = File(uri.path!!)
            if (!file.exists()) {
                Log.e(TAG, "APK file not found at ${uri.path}")
                return
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(getUriForFile(context, file), "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installation", e)
        }
    }

    private fun getUriForFile(context: Context, file: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
        } else {
            Uri.fromFile(file)
        }
    }
}
