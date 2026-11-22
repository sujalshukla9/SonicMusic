package com.sonicmusic.app.core.updater

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sonicmusic.app.BuildConfig
import com.sonicmusic.app.features.updates.receiver.UpdateNotifications
import com.sonicmusic.app.features.updates.worker.VerifyUpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.UUID

class UpdateDownloader(private val context: Context) {
    internal data class Pending(
        val id: Long,
        val update: UpdatePackage,
        val sessionId: Int,
        val awaitingConfirmation: Boolean
    )

    private val preferences by lazy {
        context.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)
    }
    internal val manager get() = requireNotNull(context.getSystemService(DownloadManager::class.java))

    fun downloadApk(update: UpdatePackage, startMessage: String? = null): Long = synchronized(lock) {
        require(update.versionCode > BuildConfig.VERSION_CODE) { "This update is already installed or is older than SonicMusic." }
        val existing = pending()
        if (existing != null) {
            require(existing.update == update) { "Finish the pending SonicMusic update before starting a different one." }
            scheduleVerification(existing.id)
            return@synchronized existing.id
        }

        val previousId = preferences.getLong(KEY_ID, -1)
        if (previousId >= 0) manager.remove(previousId)
        message(null)
        val fileName = "SonicMusic-" + update.versionCode + "-" + UUID.randomUUID() + ".apk"
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("Downloading SonicMusic Update")
            .setDescription("Please wait while the update downloads…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")

        val id = manager.enqueue(request)
        val stored = preferences.edit()
            .putLong(KEY_ID, id)
            .putString("url", update.downloadUrl)
            .putInt("version", update.versionCode)
            .putString("sha256", update.sha256)
            .putString("certificate", update.certificateSha256)
            .remove(KEY_SESSION)
            .commit()
        if (!stored) {
            manager.remove(id)
            error("Could not save the update verification information.")
        }
        if (startMessage != null) UpdateNotifications.show(context, startMessage)
        scheduleVerification(id)
        id
    }

    internal fun pending(): Pending? = synchronized(lock) {
        val id = preferences.getLong(KEY_ID, -1)
        if (id < 0) return@synchronized null
        val update = runCatching {
            UpdatePackage(
                preferences.getString("url", "").orEmpty(),
                preferences.getInt("version", 0),
                preferences.getString("sha256", "").orEmpty(),
                preferences.getString("certificate", "").orEmpty()
            )
        }.getOrNull() ?: return@synchronized null
        val sessionId = preferences.getInt(KEY_SESSION, -1)
        Pending(id, update, sessionId, sessionId >= 0 && preferences.getString(KEY_STATUS, null) == CONFIRMATION_REQUIRED)
    }

    internal fun trackSession(downloadId: Long, sessionId: Int): Boolean = synchronized(lock) {
        if (pending()?.id != downloadId) return@synchronized false
        preferences.edit().putInt(KEY_SESSION, sessionId).commit()
    }

    internal fun markAwaitingConfirmation(downloadId: Long, sessionId: Int): Boolean = synchronized(lock) {
        val pending = pending() ?: return@synchronized false
        if (pending.id != downloadId || pending.sessionId != sessionId) return@synchronized false
        preferences.edit().putString(KEY_STATUS, CONFIRMATION_REQUIRED).commit()
    }

    internal fun resetSession(downloadId: Long, sessionId: Int): Boolean = synchronized(lock) {
        val pending = pending() ?: return@synchronized false
        if (pending.id != downloadId || pending.sessionId != sessionId) return@synchronized false
        preferences.edit().remove(KEY_SESSION)
            .putString(KEY_STATUS, "Preparing the verified update again…").commit()
    }

    internal fun finish(downloadId: Long, status: String, sessionId: Int? = null): Boolean = synchronized(lock) {
        val pending = pending() ?: return@synchronized false
        if (pending.id != downloadId || (sessionId != null && sessionId != pending.sessionId)) {
            return@synchronized false
        }
        val saved = preferences.edit().remove(KEY_ID).remove(KEY_SESSION)
            .remove("url").remove("version").remove("sha256").remove("certificate")
            .putString(KEY_STATUS, status).commit()
        runCatching { manager.remove(downloadId) }
        saved
    }

    internal fun message(status: String?) {
        preferences.edit().putString(KEY_STATUS, status).apply()
    }

    fun observeStatus() = callbackFlow<String?> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == KEY_STATUS) trySend(prefs.getString(KEY_STATUS, null))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(preferences.getString(KEY_STATUS, null))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun resumePending() {
        pending()?.let { scheduleVerification(it.id) }
    }

    internal fun scheduleVerification(downloadId: Long, recoverConfirmation: Boolean = false) {
        if (pending()?.id != downloadId) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "verify-sonicmusic-apk-$downloadId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<VerifyUpdateWorker>()
                .setInputData(workDataOf("downloadId" to downloadId, RECOVER_CONFIRMATION to recoverConfirmation))
                .build()
        )
    }

    fun observeDownloadProgress(downloadId: Long) = flow {
        val query = DownloadManager.Query().setFilterById(downloadId)
        while (true) {
            val progress = manager.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> 100
                    DownloadManager.STATUS_FAILED -> null
                    else -> {
                        val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (total > 0) (bytes.toDouble() * 100 / total).toInt().coerceIn(0, 99) else 0
                    }
                }
            }
            if (progress == null || progress == 100) {
                scheduleVerification(downloadId)
                break
            }
            emit(progress)
            delay(350)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    internal companion object {
        const val CONFIRMATION_REQUIRED = "Update verified. Confirm installation to continue."
        const val RECOVER_CONFIRMATION = "recoverConfirmation"
        private val lock = Any()
        private const val KEY_ID = "pending_update_download_id"
        private const val KEY_SESSION = "pending_update_session"
        private const val KEY_STATUS = "update_status"
    }
}
