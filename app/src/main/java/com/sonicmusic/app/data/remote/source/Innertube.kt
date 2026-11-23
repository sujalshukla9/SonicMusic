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

    private val WEB_USER_AGENT = YtmScopeConfig.ClientContext.USER_AGENT
    private const val ANDROID_USER_AGENT =
        "com.google.android.youtube/21.23.483 (Linux; U; Android 14; en_US) gzip"
    private const val ANDROID_CLIENT_NAME = "3"
    private const val ANDROID_CLIENT_VERSION = "21.23.483"

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

    // ── Charts tab params (protobuf-encoded, plain base64 — NOT URL-encoded) ──
    // These select specific tabs within the FEmusic_charts browseId response.
    // Protobuf structure: field 50 (wire type 2) → submessage { field 1 = varint N }
    const val CHARTS_PARAMS_SONGS    = "sgYECgIIAQ=="  // tab 1 = Songs
    const val CHARTS_PARAMS_ARTISTS  = "sgYECgIIAg=="  // tab 2 = Artists
    const val CHARTS_PARAMS_TRENDING = "sgYECgIIAw=="  // tab 3 = Trending
    const val CHARTS_PARAMS_VIDEOS   = "sgYECgIIBA=="  // tab 4 = Top Music Videos

    @JvmInline
    value class SearchFilter(val value: String) {
        companion object {
            val Song = SearchFilter(YtmScopeConfig.SearchCategoryParams.SONGS)
            val Video = SearchFilter(YtmScopeConfig.SearchCategoryParams.VIDEOS)
            val Album = SearchFilter(YtmScopeConfig.SearchCategoryParams.ALBUMS)
            val Artist = SearchFilter(YtmScopeConfig.SearchCategoryParams.ARTISTS)
            val CommunityPlaylist = SearchFilter(YtmScopeConfig.SearchCategoryParams.PLAYLISTS)
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
     * Generate a visitorData token for the Innertube context.
     *
     * Real visitorData tokens from ytcfg are opaque ~120-char strings
     * containing a random visitor ID + timestamp. We generate a
     * realistic-format token: protobuf with field 1 = random ID string,
     * field 5 = timestamp. This is sufficient for charts/browse requests
     * where the `gl` field in context provides the actual region signal.
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun generateVisitorData(region: String): String {
        // Generate a random 11-char visitor ID (alphanumeric, matching YT format)
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val visitorId = (1..11).map { chars.random() }.joinToString("")
        val idBytes = visitorId.toByteArray(Charsets.UTF_8)

        // Protobuf: field 1 (tag=0x0A, length-delimited) = visitor ID string
        //           field 5 (tag=0x28, varint) = current timestamp seconds
        val timestampSeconds = System.currentTimeMillis() / 1000
        val varintBytes = encodeVarint(timestampSeconds)

        val proto = byteArrayOf(0x0A, idBytes.size.toByte()) + idBytes +
                    byteArrayOf(0x28.toByte()) + varintBytes
        return Base64.encodeToString(proto, Base64.NO_WRAP)
    }

    /** Encode a long as a protobuf varint (variable-length integer). */
    private fun encodeVarint(value: Long): ByteArray {
        val bytes = mutableListOf<Byte>()
        var v = value
        while (v > 0x7F) {
            bytes.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        bytes.add((v and 0x7F).toByte())
        return bytes.toByteArray()
    }

    /**
     * Canonical single context builder for all YouTube Music requests across SonicMusic.
     * Enforces WEB_REMIX client identity, user region/language, and visitorData token.
     */
    fun buildYtmContext(language: String, region: String, block: JSONObject.() -> Unit): JSONObject {
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", YtmScopeConfig.ClientContext.CLIENT_NAME)
                    put("clientVersion", YtmScopeConfig.ClientContext.CLIENT_VERSION)
                    put("hl", language)
                    put("gl", region)
                    put("platform", YtmScopeConfig.ClientContext.PLATFORM)
                    put("visitorData", generateVisitorData(region))
                })
            })
            block()
        }
    }

    /**
     * Alias for buildYtmContext for backwards compatibility.
     */
    fun webRemixBody(language: String, region: String, block: JSONObject.() -> Unit): JSONObject {
        return buildYtmContext(language, region, block)
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

    fun musicPost(
        endpoint: String,
        body: JSONObject,
        acceptLanguage: String,
        fieldMask: String? = null,
        cookies: String? = null,
        userSessionId: String? = null
    ): Request {
        val url = endpointUrl(
            endpoint = endpoint,
            defaultHost = MUSIC_HOST,
            key = API_KEY
        )
        require(cookies.isNullOrBlank() || (url.isHttps && url.host == MUSIC_HOST))

        // Extract visitorData from the body context for the HTTP header.
        // Innertube cross-references X-Goog-Visitor-Id header against
        // context.client.visitorData — missing header causes region fallback.
        val visitorData = body.optJSONObject("context")
            ?.optJSONObject("client")
            ?.optString("visitorData")

        return Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Accept-Language", acceptLanguage)
            .apply {
                url.queryParameter("key")?.let { header("X-Goog-Api-Key", it) }
                applyOriginHeaders(this, url, includeReferer = true)
                // Set X-Goog-Visitor-Id header to match body's visitorData
                if (!visitorData.isNullOrBlank()) {
                    header("X-Goog-Visitor-Id", visitorData)
                }
                if (!fieldMask.isNullOrBlank()) {
                    header("X-Goog-FieldMask", fieldMask)
                }
                if (!cookies.isNullOrBlank()) {
                    header("Cookie", cookies)
                    musicAuthorization(cookies, userSessionId)?.let { header("Authorization", it) }
                }
            }
            .build()
    }

    private fun musicAuthorization(cookies: String, userSessionId: String?): String? {
        val values = cookies.split(';').associate { pair ->
            pair.substringBefore('=').trim() to pair.substringAfter('=', "").trim()
        }
        val timestamp = System.currentTimeMillis() / 1000
        val schemes = mapOf(
            "SAPISIDHASH" to values["SAPISID"],
            "SAPISID1PHASH" to values["__Secure-1PAPISID"],
            "SAPISID3PHASH" to values["__Secure-3PAPISID"]
        )
        return schemes.mapNotNull { (scheme, sid) ->
            if (sid.isNullOrBlank()) return@mapNotNull null
            val identity = userSessionId?.let { "$it " }.orEmpty()
            val payload = "${identity}$timestamp $sid https://music.youtube.com"
            val digest = java.security.MessageDigest.getInstance("SHA-1")
                .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val suffix = if (userSessionId == null) "" else "_u"
            "$scheme ${timestamp}_$digest$suffix"
        }.joinToString(" ").takeIf(String::isNotEmpty)
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
        if (parsed.queryParameter("key") == null && key.isNotBlank() && key != "YOUR_API_KEY_HERE") {
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
