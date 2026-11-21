package com.sonicmusic.app.features.updates.data

import android.util.Log
import android.net.Uri
import com.sonicmusic.app.BuildConfig
import com.sonicmusic.app.core.network.awaitSuccessfulBody
import com.sonicmusic.app.core.updater.UpdatePackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
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
        val updatePackage: UpdatePackage?
    ) {
        val downloadUrl: String? get() = updatePackage?.downloadUrl
    }

    data class GitHubRelease(
        val tagName: String,
        val name: String,
        val publishedAt: String,
        val body: String,
        val htmlUrl: String,
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
            val release = JSONObject(fetchBody(RELEASES_API_URL))
            val tag = release.optString("tag_name")
            val latest = normalizeVersion(tag.ifBlank { release.optString("name") })
            require(latest.isNotBlank()) { "The latest release has an invalid version." }

            val assets = release.optJSONArray("assets") ?: JSONArray()
            val metadata = (0 until assets.length()).asSequence()
                .map(assets::getJSONObject)
                .singleOrNull { it.optString("name") == "sonicmusic-update.json" }
            val verified = metadata?.let {
                readUpdatePackage(assets, tag, latest, it.optString("browser_download_url"))
            }
            val available = verified?.let { it.versionCode > BuildConfig.VERSION_CODE }
                ?: isRemoteVersionNewer(remoteVersion = latest, currentVersion = normalizedCurrent)
            Result.success(UpdateCheck(available, normalizedCurrent, latest, verified?.takeIf { available }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check app updates", e)
            Result.failure(e)
        }
    }

    private suspend fun readUpdatePackage(
        assets: JSONArray,
        tag: String,
        latest: String,
        metadataUrl: String
    ): UpdatePackage {
        requireReleaseAsset(metadataUrl, tag, "sonicmusic-update.json")
        val metadata = JSONObject(fetchBody(metadataUrl))
        val apkName = metadata.getString("apk")
        require(Regex("[A-Za-z0-9_.-]+[.]apk").matches(apkName)) { "Invalid release APK name." }
        require(metadata.getString("packageName") == BuildConfig.APPLICATION_ID &&
            metadata.getString("versionName") == latest
        ) { "Release metadata does not match SonicMusic or its version." }

        val apk = (0 until assets.length()).asSequence().map(assets::getJSONObject)
            .singleOrNull { it.optString("name") == apkName }
            ?: error("The release APK is missing.")
        val url = apk.getString("browser_download_url")
        requireReleaseAsset(url, tag, apkName)
        val sha = metadata.getString("sha256").lowercase()
        val assetDigest = apk.optString("digest").takeUnless { it.isBlank() || it == "null" }
        require(assetDigest == null || assetDigest.equals("sha256:$sha", ignoreCase = true)) {
            "The release checksum disagrees with GitHub."
        }
        return UpdatePackage(url, metadata.getInt("versionCode"), sha, metadata.getString("signingCertificateSha256").lowercase())
    }

    private fun requireReleaseAsset(url: String, tag: String, name: String) {
        val uri = Uri.parse(url)
        require(
            uri.scheme == "https" && uri.encodedAuthority == "github.com" &&
                uri.pathSegments == listOf("sujalshukla9", "SonicMusic", "releases", "download", tag, name) &&
                uri.query == null && uri.fragment == null
        ) { "Unexpected GitHub release asset URL." }
    }

    private suspend fun fetchBody(url: String): String {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SonicMusic-Android")
            .build()
        return okHttpClient.newCall(request).awaitSuccessfulBody() ?: error("Empty release response.")
    }

    suspend fun getReleases(): Result<List<GitHubRelease>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/sujalshukla9/SonicMusic/releases")
                .get()
                .header("Accept", " application/vnd.github+json")
                .header("User-Agent", " SonicMusic-Android")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Failed to fetch releases (HTTP ${response.code})")
                    )
                }

                val body = response.body?.string()
                    ?: return@withContext Result.failure(IllegalStateException("Empty releases response"))
                
                val jsonArray = JSONArray(body)
                val releases = mutableListOf<GitHubRelease>()
                
                for (i in 0 until jsonArray.length()) {
                    val json = jsonArray.getJSONObject(i)
                    val tagName = json.optString("tag_name", "")
                    val name = json.optString("name", tagName)
                    val publishedAt = json.optString("published_at", "")
                    val releaseBody = json.optString("body", "")
                    val htmlUrl = json.optString("html_url", "")
                    
                    var downloadUrl: String? = null
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val asset = assets.getJSONObject(j)
                            val assetName = asset.optString("name", "")
                            if (assetName.endsWith(".apk")) {
                                downloadUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    
                    releases.add(
                        GitHubRelease(
                            tagName = tagName,
                            name = name,
                            publishedAt = publishedAt,
                            body = releaseBody,
                            htmlUrl = htmlUrl,
                            downloadUrl = downloadUrl
                        )
                    )
                }
                
                Result.success(releases)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch releases list", e)
            Result.failure(e)
        }
    }

    private fun normalizeVersion(rawVersion: String): String {
        return rawVersion.trim()
            .removePrefix("v")
            .removePrefix("V")
            .removePrefix(".")
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
