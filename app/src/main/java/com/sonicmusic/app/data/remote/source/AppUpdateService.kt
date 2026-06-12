package com.sonicmusic.app.data.remote.source

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class AppUpdateService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    data class UpdateCheck(
        val isUpdateAvailable: Boolean,
        val currentVersion: String,
        val latestVersion: String,
        val downloadUrl: String?
    )

    companion object {
        private const val TAG = "AppUpdateService"
        private const val RELEASES_API_URL = "https://api.github.com/repos/sujalshukla9/SonicMusic/releases/latest"
    }

    suspend fun checkForUpdates(currentVersion: String): Result<UpdateCheck> = withContext(Dispatchers.IO) {
        val normalizedCurrent = normalizeVersion(currentVersion)
        if (normalizedCurrent.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Current version is empty"))
        }

        try {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .get()
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SonicMusic-Android")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Failed to check updates (HTTP ${response.code})")
                    )
                }

                val body = response.body?.string()
                    ?: return@withContext Result.failure(IllegalStateException("Empty update response"))
                val json = JSONObject(body)

                val latestRaw = json.optString("tag_name")
                    .ifBlank { json.optString("name") }
                val normalizedLatest = normalizeVersion(latestRaw)
                if (normalizedLatest.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Invalid latest version"))
                }

                // Find APK download URL from assets
                var downloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }

                val updateAvailable = isRemoteVersionNewer(
                    remoteVersion = normalizedLatest,
                    currentVersion = normalizedCurrent
                )

                Result.success(
                    UpdateCheck(
                        isUpdateAvailable = updateAvailable,
                        currentVersion = normalizedCurrent,
                        latestVersion = normalizedLatest,
                        downloadUrl = downloadUrl?.takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check app updates", e)
            Result.failure(e)
        }
    }

    private fun normalizeVersion(rawVersion: String): String {
        return rawVersion.trim()
            .removePrefix("v")
            .removePrefix("V")
    }

    private fun isRemoteVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = parseVersionParts(remoteVersion)
        val currentParts = parseVersionParts(currentVersion)
        val maxSize = max(remoteParts.size, currentParts.size)

        for (index in 0 until maxSize) {
            val remote = remoteParts.getOrElse(index) { 0 }
            val current = currentParts.getOrElse(index) { 0 }
            if (remote > current) return true
            if (remote < current) return false
        }

        return false
    }

    private fun parseVersionParts(version: String): List<Int> {
        return Regex("\\d+")
            .findAll(version)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
    }
}
