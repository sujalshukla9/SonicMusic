package com.sonicmusic.app.core.updater

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String
)

class GitHubUpdater(private val context: Context) {
    
    companion object {
        private const val GITHUB_API_URL = "https://api.github.com/repos/sujalshukla9/SonicMusic/releases/latest"
    }

    suspend fun checkForUpdates(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                val tagName = json.getString("tag_name")
                // Remove 'v' prefix if present for comparison
                val latestVersion = tagName.removePrefix("v")
                
                val currentVersionClean = currentVersion.removePrefix("v")
                
                // Extremely simple version check (could be improved with SemVer parsing)
                val hasUpdate = latestVersion != currentVersionClean

                var downloadUrl = ""
                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                return@withContext UpdateInfo(
                    hasUpdate = hasUpdate,
                    latestVersion = latestVersion,
                    downloadUrl = downloadUrl,
                    releaseNotes = json.optString("body", "No release notes provided.")
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }
}
