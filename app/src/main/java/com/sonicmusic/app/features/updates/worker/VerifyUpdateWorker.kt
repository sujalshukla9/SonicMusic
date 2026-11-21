package com.sonicmusic.app.features.updates.worker

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sonicmusic.app.BuildConfig
import com.sonicmusic.app.core.updater.UpdateApkVerifier
import com.sonicmusic.app.core.updater.UpdateDownloader
import com.sonicmusic.app.core.updater.hex
import com.sonicmusic.app.features.updates.receiver.AppUpdateReceiver
import com.sonicmusic.app.features.updates.receiver.UpdateNotifications
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VerifyUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val downloader = UpdateDownloader(context)
        val download = downloader.pending()?.takeIf { it.id == inputData.getLong("downloadId", -1L) }
            ?: return@withContext Result.success()
        val installer = context.packageManager.packageInstaller
        var sessionToAbandon: Int? = null
        try {
            if (download.update.versionCode <= BuildConfig.VERSION_CODE) {
                downloader.finish(download.id, "SonicMusic is up to date.")
                UpdateNotifications.clear(context)
                return@withContext Result.success()
            }
            if (download.sessionId >= 0) {
                val session = installer.getSessionInfo(download.sessionId)
                if (session?.isSealed == true) return@withContext Result.success()
                // The process may have stopped between recording and committing the session.
                if (session != null) installer.abandonSession(download.sessionId)
            }
            val status = downloader.manager.query(DownloadManager.Query().setFilterById(download.id))?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
            }
            when (status) {
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_RUNNING ->
                    return@withContext Result.success()
                DownloadManager.STATUS_SUCCESSFUL -> downloader.message("Verifying the downloaded update…")
                else -> error("The update download failed. Check your connection and try again.")
            }

            if (!context.packageManager.canRequestPackageInstalls()) {
                val message = "Allow SonicMusic to install updates in Android settings, then tap Update again."
                downloader.message(message)
                UpdateNotifications.show(context, message, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
                return@withContext Result.success()
            }

            val apk = File.createTempFile("verified-update-", ".apk", context.cacheDir)
            try {
                val sha256 = MessageDigest.getInstance("SHA-256")
                DigestInputStream(ParcelFileDescriptor.AutoCloseInputStream(downloader.manager.openDownloadedFile(download.id)), sha256).use { input ->
                    apk.outputStream().use { output -> input.copyTo(output, bufferSize = 128 * 1024) }
                }
                require(sha256.digest().hex() == download.update.sha256) { "The update checksum is incorrect. Download it again." }
                UpdateApkVerifier.verify(context, apk, download.update)

                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(context.packageName); setSize(apk.length()) }
                val sessionId = installer.createSession(params)
                sessionToAbandon = sessionId
                check(downloader.trackSession(download.id, sessionId)) { "The pending update could not be recorded." }
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        apk.inputStream().use { it.copyTo(output, bufferSize = 128 * 1024) }
                        session.fsync(output)
                    }
                    val statusIntent = Intent(context, AppUpdateReceiver::class.java).apply {
                        action = AppUpdateReceiver.INSTALL_RESULT
                        putExtra(AppUpdateReceiver.EXPECTED_SESSION, sessionId)
                    }
                    val callback = PendingIntent.getBroadcast(
                        context, sessionId, statusIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    session.commit(callback.intentSender)
                    sessionToAbandon = null
                }
            } finally {
                apk.delete()
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("VerifyUpdateWorker", "Update rejected", e)
            val reason = if (e is IllegalArgumentException || e is IllegalStateException) e.message else null
            val message = reason ?: "The update could not be prepared. Try downloading it again."
            downloader.finish(download.id, message)
            UpdateNotifications.show(context, message)
            Result.failure()
        } finally {
            sessionToAbandon?.let { runCatching { installer.abandonSession(it) } }
        }
    }
}
