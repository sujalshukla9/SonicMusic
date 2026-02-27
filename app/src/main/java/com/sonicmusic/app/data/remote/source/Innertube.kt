package com.sonicmusic.app.data.remote.source

import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.sonicmusic.app.BuildConfig

object Innertube {
    private const val API_KEY = BuildConfig.INNERTUBE_API_KEY
    private const val YOUTUBE_API_KEY = BuildConfig.YOUTUBE_API_KEY

    private const val MUSIC_HOST = "music.youtube.com"
    private const val YOUTUBE_HOST = "www.youtube.com"
    private const val YOUTUBEI_HOST = "youtubei.googleapis.com"

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val ANDROID_USER_AGENT =
        "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip"
    private const val ANDROID_CLIENT_NAME = "3"
    private const val ANDROID_CLIENT_VERSION = "19.09.37"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private const val BASE = "/youtubei/v1"
    internal const val BROWSE = "$BASE/browse"
    internal const val NEXT = "$BASE/next"
    internal const val PLAYER = "https://youtubei.googleapis.com/youtubei/v1/player"
    internal const val PLAYER_MUSIC = "$BASE/player"
    internal const val QUEUE = "$BASE/music/get_queue"
    internal const val SEARCH = "$BASE/search"
    internal const val SEARCH_SUGGESTIONS = "$BASE/music/get_search_suggestions"
    internal const val MUSIC_RESPONSIVE_LIST_ITEM_RENDERER_MASK =
        "musicResponsiveListItemRenderer(flexColumns,fixedColumns,thumbnail,navigationEndpoint,badges)"
    internal const val MUSIC_TWO_ROW_ITEM_RENDERER_MASK =
        "musicTwoRowItemRenderer(thumbnailRenderer,title,subtitle,navigationEndpoint)"

    @Suppress("MaximumLineLength")
    internal const val PLAYLIST_PANEL_VIDEO_RENDERER_MASK =
        "playlistPanelVideoRenderer(title,navigationEndpoint,longBylineText,shortBylineText,thumbnail,lengthText,badges)"

    @JvmInline
    value class SearchFilter(val value: String) {
        companion object {
            val Song = SearchFilter("EgWKAQIIAWoOEAMQBBAJEAoQBRAQEBU%3D")
            val Video = SearchFilter("EgWKAQIQAWoOEAMQBBAJEAoQBRAQEBU%3D")
            val Album = SearchFilter("EgWKAQIYAWoOEAMQBBAJEAoQBRAQEBU%3D")
            val Artist = SearchFilter("EgWKAQIgAWoOEAMQBBAJEAoQBRAQEBU%3D")
            val CommunityPlaylist = SearchFilter("EgeKAQQoAEABag4QAxAEEAkQChAFEBAQFQ%3D%3D")
        }
    }

    data class Thumbnail(
        val url: String?
    )

    data class Info<T>(
        val name: String?,
        val endpoint: T? = null
    )

    data class SongItem(
        val info: Info<Any?>? = null,
        val authors: List<Info<Any?>>? = null,
        val album: Info<Any?>? = null,
        val durationText: String? = null,
        val explicit: Boolean = false,
        val thumbnail: Thumbnail? = null
    )

    data class ArtistItem(
        val info: Info<Any?>? = null,
        val subscribersCountText: String? = null,
        val thumbnail: Thumbnail? = null
    )

    /**
     * Generate a visitorData token that encodes the user's region.
     * This is a lightweight protobuf-like blob that tells YouTube
     * "this visitor is in region X" — without it, `gl` is often ignored
     * and default US content is returned.
     */
    internal fun generateVisitorData(region: String): String {
        // Minimal protobuf: field 1 (string) = region code
        // Wire format: tag=0x0A (field 1, length-delimited), len, bytes
        val regionBytes = region.uppercase().toByteArray(Charsets.UTF_8)
        val proto = byteArrayOf(0x0A, regionBytes.size.toByte()) + regionBytes
        return Base64.encodeToString(proto, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun webRemixBody(language: String, region: String, block: JSONObject.() -> Unit): JSONObject {
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20240101.01.00")
                    put("hl", language)
                    put("gl", region)
                    put("platform", "DESKTOP")
                    put("visitorData", generateVisitorData(region))
                })
            })
            block()
        }
    }

    /**
     * Convenience builder for artist/album browse requests.
     * Applies field mask "contents,header" to reduce response payload
     * from ~200KB to ~30KB by stripping unused fields.
     */
    fun musicBrowsePost(
        body: JSONObject,
        acceptLanguage: String
    ): Request {
        return musicPost(
            endpoint = BROWSE,
            body = body,
            acceptLanguage = acceptLanguage,
            fieldMask = "contents,header"
        )
    }

    fun androidBody(language: String, region: String, block: JSONObject.() -> Unit): JSONObject {
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", ANDROID_CLIENT_VERSION)
                    put("androidSdkVersion", 34)
                    put("hl", language)
                    put("gl", region)
                    put("platform", "MOBILE")
                })
            })
            block()
        }
    }

    fun musicPost(
        endpoint: String,
        body: JSONObject,
        acceptLanguage: String,
        fieldMask: String? = null
    ): Request {
        val url = endpointUrl(
            endpoint = endpoint,
            defaultHost = MUSIC_HOST,
            key = API_KEY
        )
        return Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", WEB_USER_AGENT)
            .header("X-Goog-Api-Key", API_KEY)
            .header("Accept-Language", acceptLanguage)
            .apply {
                applyOriginHeaders(this, url, includeReferer = true)
                if (!fieldMask.isNullOrBlank()) {
                    header("X-Goog-FieldMask", fieldMask)
                }
            }
            .build()
    }

    fun youtubeAndroidPost(
        endpoint: String,
        body: JSONObject,
        acceptLanguage: String,
        includeClientHeaders: Boolean = false
    ): Request {
        val url = endpointUrl(
            endpoint = endpoint,
            defaultHost = YOUTUBE_HOST,
            key = YOUTUBE_API_KEY
        )
        return Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", ANDROID_USER_AGENT)
            .header("Accept-Language", acceptLanguage)
            .apply {
                if (includeClientHeaders) {
                    header("X-YouTube-Client-Name", ANDROID_CLIENT_NAME)
                    header("X-YouTube-Client-Version", ANDROID_CLIENT_VERSION)
                }
                applyOriginHeaders(this, url, includeReferer = false)
            }
            .build()
    }

    private fun endpointUrl(endpoint: String, defaultHost: String, key: String): HttpUrl {
        val raw = if (endpoint.startsWith("http")) {
            endpoint
        } else {
            "https://$defaultHost$endpoint"
        }

        val parsed = raw.toHttpUrl()
        val builder = parsed.newBuilder()
        if (parsed.queryParameter("prettyPrint") == null) {
            builder.addQueryParameter("prettyPrint", "false")
        }
        if (parsed.queryParameter("key") == null) {
            builder.addQueryParameter("key", key)
        }
        return builder.build()
    }

    private fun applyOriginHeaders(
        builder: Request.Builder,
        url: HttpUrl,
        includeReferer: Boolean
    ) {
        val host = if (url.host == YOUTUBEI_HOST) YOUTUBE_HOST else url.host
        val origin = "${url.scheme}://$host"
        builder.header("host", host)
        builder.header("x-origin", origin)
        builder.header("origin", origin)
        if (includeReferer) {
            builder.header("Referer", "$origin/")
        }
    }
}

data class InvalidHttpCodeException(val code: Int) :
    IllegalStateException("Invalid http code received: $code")
