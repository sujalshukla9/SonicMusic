package com.sonicmusic.app.core.updater

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

class UpdateDownloader(private val context: Context) {

    fun downloadApk(url: String, fileName: String = "SonicMusic-Update.apk"): Long {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading Sonic Music Update")
            .setDescription("Please wait while the update downloads...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")

        return downloadManager.enqueue(request)
    }
}
