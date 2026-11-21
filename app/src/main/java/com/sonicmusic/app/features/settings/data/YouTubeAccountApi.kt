package com.sonicmusic.app.features.settings.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sonicmusic.app.core.network.awaitSuccessfulBody
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeAccountApi @Inject constructor() {
    private val gson = Gson()

    // Keep account credentials out of the shared client's debug logging and interceptors.
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun channelId(token: String): String? = request(
        token, "channels", "part" to "id", "mine" to "true"
    ).items().firstOrNull()?.text("id")

    suspend fun likedMusic(token: String): List<Song> = pages(
        token, "videos", "part" to VIDEO_PARTS, "myRating" to "like"
    ).mapNotNull(::parseMusic).distinctBy(Song::id)

    suspend fun playlists(token: String): List<YouTubeAccountPlaylist> {
        val playlists = pages(token, "playlists", "part" to "snippet", "mine" to "true")
            .mapNotNull { item ->
                val id = item.text("id") ?: return@mapNotNull null
                val snippet = item.getAsJsonObject("snippet") ?: return@mapNotNull null
                val title = snippet.text("title") ?: return@mapNotNull null
                YouTubeAccountPlaylist(id, title, snippet.thumbnail())
            }
        val trackIds = playlists.associateWith { playlist ->
            pages(token, "playlistItems", "part" to "contentDetails", "playlistId" to playlist.id)
                .mapNotNull { it.getAsJsonObject("contentDetails")?.text("videoId") }
        }
        val tracks = trackIds.values.flatten().distinct().chunked(PAGE_SIZE).flatMap { ids ->
            request(token, "videos", "part" to VIDEO_PARTS, "id" to ids.joinToString(","))
                .items().mapNotNull(::parseMusic)
        }.associateBy(Song::id)
        return playlists.mapNotNull { playlist ->
            val songs = trackIds.getValue(playlist).mapNotNull(tracks::get)
            playlist.copy(songs = songs).takeIf { songs.isNotEmpty() }
        }
    }

    suspend fun revoke(token: String) {
        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/revoke")
            .post(FormBody.Builder().add("token", token).build())
            .build()
        client.newCall(request).awaitSuccessfulBody()
    }

    private suspend fun pages(
        token: String,
        endpoint: String,
        vararg params: Pair<String, String>
    ): List<JsonObject> = buildList {
        var pageToken: String? = null
        val seenPageTokens = mutableSetOf<String>()
        do {
            val pageParams = params.toList() + ("maxResults" to PAGE_SIZE.toString())
            val nextPage = pageToken?.let { pageParams + ("pageToken" to it) } ?: pageParams
            val page = request(token, endpoint, *nextPage.toTypedArray())
            addAll(page.items())
            pageToken = page.text("nextPageToken")
        } while (pageToken != null && seenPageTokens.add(pageToken))
        check(pageToken == null) { "YouTube returned a repeated page" }
    }

    private suspend fun request(token: String, endpoint: String, vararg params: Pair<String, String>): JsonObject {
        val url = API_BASE.newBuilder().addPathSegment(endpoint).apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        val body = checkNotNull(client.newCall(request).awaitSuccessfulBody())
        return gson.fromJson(body, JsonObject::class.java)
    }

    private fun parseMusic(video: JsonObject): Song? {
        val id = video.text("id") ?: return null
        val snippet = video.getAsJsonObject("snippet") ?: return null
        if (snippet.text("categoryId") != MUSIC_CATEGORY) return null
        if (video.getAsJsonObject("status")?.text("privacyStatus") != "public") return null
        val title = snippet.text("title") ?: return null
        val duration = video.getAsJsonObject("contentDetails")?.text("duration") ?: return null
        val seconds = runCatching { Duration.parse(duration).seconds.toInt() }.getOrNull() ?: return null
        if (seconds <= 0) return null
        return Song(
            id = id,
            title = title,
            artist = snippet.text("channelTitle").orEmpty().removeSuffix(" - Topic"),
            duration = seconds,
            thumbnailUrl = snippet.thumbnail() ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            contentType = ContentType.SONG
        )
    }

    private fun JsonObject.items(): List<JsonObject> = getAsJsonArray("items").map { it.asJsonObject }

    private fun JsonObject.text(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)

    private fun JsonObject.thumbnail(): String? {
        val thumbnails = getAsJsonObject("thumbnails") ?: return null
        return sequenceOf("high", "medium", "default")
            .mapNotNull { thumbnails.getAsJsonObject(it)?.text("url") }.firstOrNull()
    }

    private companion object {
        val API_BASE = "https://www.googleapis.com/youtube/v3/".toHttpUrl()
        const val PAGE_SIZE = 50
        const val MUSIC_CATEGORY = "10"
        const val VIDEO_PARTS = "snippet,contentDetails,status"
    }
}

data class YouTubeAccountPlaylist(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val songs: List<Song> = emptyList()
)
