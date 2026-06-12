package com.sonicmusic.app.data.repository

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.sonicmusic.app.core.util.ThumbnailUrlUtils
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.remote.source.Innertube
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.SearchSuggestionItem
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.SuggestionType
import com.sonicmusic.app.domain.repository.SearchSuggestionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchSuggestionRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore
) : SearchSuggestionRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cache = LruCache<String, CacheEntry>(50)

    override suspend fun getSearchSuggestions(query: String): Result<Pair<List<String>, List<SearchSuggestionItem>>> =
        withContext(Dispatchers.IO) {
            val normalizedQuery = query.trim().lowercase()
            if (normalizedQuery.length < 2) {
                return@withContext Result.success(Pair(emptyList(), emptyList()))
            }

            // Check cache
            cache.get(normalizedQuery)?.let { entry ->
                if (entry.isValid()) {
                    return@withContext Result.success(entry.data)
                } else {
                    cache.remove(normalizedQuery)
                }
            }

            try {
                val region = getRequestRegion()
                val language = getLanguageForRegion(region)

                val requestBody = Innertube.webRemixBody(language, region) {
                    put("input", query.trim())
                }

                val request = Innertube.musicPost(
                    endpoint = Innertube.SEARCH_SUGGESTIONS,
                    body = requestBody,
                    acceptLanguage = getAcceptLanguageHeader(language, region)
                )

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    // Fail silently but gracefully
                    return@withContext Result.success(Pair(emptyList(), emptyList()))
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    return@withContext Result.success(Pair(emptyList(), emptyList()))
                }

                val parsed = parseSuggestionsResponse(body)
                
                // Cache the successful result
                cache.put(normalizedQuery, CacheEntry(parsed))
                
                Result.success(parsed)
            } catch (e: Exception) {
                Log.e("SearchSuggestionRepo", "Error fetching search suggestions", e)
                // Return empty list instead of failure to fail silently as requested
                Result.success(Pair(emptyList(), emptyList()))
            }
        }

    private fun parseSuggestionsResponse(jsonString: String): Pair<List<String>, List<SearchSuggestionItem>> {
        val textSuggestions = linkedSetOf<String>()
        val contentSuggestions = mutableListOf<SearchSuggestionItem>()

        try {
            val json = JSONObject(jsonString)
            collectSuggestions(json, textSuggestions, contentSuggestions)
        } catch (e: Exception) {
            Log.e("SearchSuggestionRepo", "Error parsing search suggestions JSON", e)
        }

        return Pair(textSuggestions.toList(), contentSuggestions.distinctBy { it.id })
    }

    private fun collectSuggestions(
        node: JSONObject,
        textOutput: MutableSet<String>,
        contentOutput: MutableList<SearchSuggestionItem>
    ) {
        // 1. Text-only suggestions (searchSuggestionRenderer)
        val suggestionRenderer = node.optJSONObject("searchSuggestionRenderer")
        if (suggestionRenderer != null) {
            val suggestionObj = suggestionRenderer.optJSONObject("suggestion")
            val suggestionText = extractText(suggestionObj).trim()
            if (suggestionText.isNotEmpty()) {
                textOutput.add(suggestionText)
            }
        }

        // 2. Rich content suggestions (musicResponsiveListItemRenderer)
        val musicItem = node.optJSONObject("musicResponsiveListItemRenderer")
        if (musicItem != null) {
            try {
                val flexColumns = musicItem.optJSONArray("flexColumns")
                if (flexColumns != null && flexColumns.length() > 0) {
                    // Column 0: Title
                    val titleCol = flexColumns.optJSONObject(0)
                    val title = extractMusicText(titleCol).trim()

                    // Column 1: Subtitle/Metadata (Artist Name • Type • SubCount / Year, etc.)
                    val subtitleCol = if (flexColumns.length() > 1) flexColumns.optJSONObject(1) else null
                    val subtitle = if (subtitleCol != null) extractMusicText(subtitleCol).trim() else ""

                    // Thumbnail
                    var thumbnailUrl = ""
                    val thumbnailObj = musicItem.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                    if (thumbnailObj != null && thumbnailObj.length() > 0) {
                        val rawUrl = thumbnailObj.optJSONObject(0).optString("url", "")
                        thumbnailUrl = ThumbnailUrlUtils.toHighQuality(rawUrl) ?: rawUrl
                    }

                    // Navigation Endpoint & Type detection
                    val navigationEndpoint = musicItem.optJSONObject("navigationEndpoint")
                    if (navigationEndpoint != null && title.isNotEmpty()) {
                        val watchEndpoint = navigationEndpoint.optJSONObject("watchEndpoint")
                        val browseEndpoint = navigationEndpoint.optJSONObject("browseEndpoint")

                        var id = ""
                        var type = SuggestionType.SONG // default fallback

                        if (watchEndpoint != null) {
                            id = watchEndpoint.optString("videoId", "")
                            type = if (subtitle.contains("Video", ignoreCase = true)) {
                                SuggestionType.VIDEO
                            } else {
                                SuggestionType.SONG
                            }
                        } else if (browseEndpoint != null) {
                            id = browseEndpoint.optString("browseId", "")
                            type = when {
                                id.startsWith("UC") || subtitle.contains("Artist", ignoreCase = true) -> SuggestionType.ARTIST
                                id.startsWith("MPRE") || subtitle.contains("Album", ignoreCase = true) -> SuggestionType.ALBUM
                                id.startsWith("VL") || subtitle.contains("Playlist", ignoreCase = true) -> SuggestionType.PLAYLIST
                                else -> SuggestionType.SONG
                            }
                        }

                        // Refine type using exact metadata runs
                        val subtitleRuns = subtitleCol
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        if (subtitleRuns != null) {
                            for (i in 0 until subtitleRuns.length()) {
                                val runText = subtitleRuns.optJSONObject(i)?.optString("text", "")?.trim() ?: ""
                                when {
                                    runText.equals("Song", ignoreCase = true) -> type = SuggestionType.SONG
                                    runText.equals("Artist", ignoreCase = true) -> type = SuggestionType.ARTIST
                                    runText.equals("Album", ignoreCase = true) -> type = SuggestionType.ALBUM
                                    runText.equals("Playlist", ignoreCase = true) -> type = SuggestionType.PLAYLIST
                                    runText.equals("Video", ignoreCase = true) -> type = SuggestionType.VIDEO
                                }
                            }
                        }

                        if (id.isNotEmpty()) {
                            contentOutput.add(
                                SearchSuggestionItem(
                                    id = id,
                                    type = type,
                                    title = title,
                                    subtitle = subtitle,
                                    thumbnailUrl = thumbnailUrl
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("SearchSuggestionRepo", "Failed to parse rich suggestion renderer", e)
            }
        }

        // Recursive tree traversal
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = node.opt(key)) {
                is JSONObject -> collectSuggestions(value, textOutput, contentOutput)
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is JSONObject) {
                            collectSuggestions(item, textOutput, contentOutput)
                        }
                    }
                }
            }
        }
    }

    private fun extractText(textObject: JSONObject?): String {
        if (textObject == null) return ""
        textObject.optString("simpleText", "").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = textObject.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            return buildString {
                for (i in 0 until runs.length()) {
                    append(runs.optJSONObject(i)?.optString("text", "") ?: "")
                }
            }
        }
        return ""
    }

    private fun extractMusicText(column: JSONObject?): String {
        val runs = column?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            return buildString {
                for (i in 0 until runs.length()) {
                    append(runs.optJSONObject(i)?.optString("text", "") ?: "")
                }
            }
        }
        return ""
    }

    private suspend fun getRequestRegion(): String {
        val stored = settingsDataStore.countryCode.first()?.trim()?.uppercase()
        if (!stored.isNullOrEmpty() && stored.length == 2 && stored.all { it.isLetter() }) {
            return if (stored == "UK") "GB" else stored
        }
        return "US"
    }

    private fun getLanguageForRegion(region: String): String = "en"

    private fun getAcceptLanguageHeader(language: String, region: String): String {
        return "$language-$region,$language;q=0.9,en;q=0.8"
    }

    private data class CacheEntry(
        val data: Pair<List<String>, List<SearchSuggestionItem>>,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isValid(): Boolean {
            // Suggestion caching for 2 minutes to avoid flicker/over-fetching
            return System.currentTimeMillis() - timestamp < 120_000L
        }
    }
}
