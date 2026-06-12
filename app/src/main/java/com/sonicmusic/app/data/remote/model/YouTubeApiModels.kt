package com.sonicmusic.app.data.remote.model

import com.google.gson.annotations.SerializedName

/**
 * YouTubei API Response Models
 * Simplified for song-only extraction
 */

data class YouTubeSearchResponse(
    @SerializedName("contents") val contents: Contents?,
    @SerializedName("estimatedResults") val estimatedResults: String?
)

data class Contents(
    @SerializedName("twoColumnSearchResultsRenderer") val twoColumnSearchResultsRenderer: TwoColumnSearchResultsRenderer?
)

data class TwoColumnSearchResultsRenderer(
    @SerializedName("primaryContents") val primaryContents: PrimaryContents?
)

data class PrimaryContents(
    @SerializedName("sectionListRenderer") val sectionListRenderer: SectionListRenderer?
)

data class SectionListRenderer(
    @SerializedName("contents") val contents: List<SectionContent>?
)

data class SectionContent(
    @SerializedName("itemSectionRenderer") val itemSectionRenderer: ItemSectionRenderer?
)

data class ItemSectionRenderer(
    @SerializedName("contents") val contents: List<VideoContent>?
)

data class VideoContent(
    @SerializedName("videoRenderer") val videoRenderer: VideoRenderer?
)

data class VideoRenderer(
    @SerializedName("videoId") val videoId: String?,
    @SerializedName("title") val title: Title?,
    @SerializedName("ownerText") val ownerText: OwnerText?,
    @SerializedName("lengthText") val lengthText: LengthText?,
    @SerializedName("thumbnail") val thumbnail: Thumbnail?,
    @SerializedName("publishedTimeText") val publishedTimeText: PublishedTimeText?,
    @SerializedName("viewCountText") val viewCountText: ViewCountText?,
    @SerializedName("badges") val badges: List<Badge>?
)

data class Title(
    @SerializedName("runs") val runs: List<TextRun>?
)

data class OwnerText(
    @SerializedName("runs") val runs: List<TextRun>?
)

data class LengthText(
    @SerializedName("simpleText") val simpleText: String?
)

data class Thumbnail(
    @SerializedName("thumbnails") val thumbnails: List<ThumbnailItem>?
)

data class ThumbnailItem(
    @SerializedName("url") val url: String?,
    @SerializedName("width") val width: Int?,
    @SerializedName("height") val height: Int?
)

data class PublishedTimeText(
    @SerializedName("simpleText") val simpleText: String?
)

data class ViewCountText(
    @SerializedName("simpleText") val simpleText: String?
)

data class Badge(
    @SerializedName("metadataBadgeRenderer") val metadataBadgeRenderer: MetadataBadgeRenderer?
)

data class MetadataBadgeRenderer(
    @SerializedName("style") val style: String?,
    @SerializedName("label") val label: String?
)

data class TextRun(
    @SerializedName("text") val text: String?
)

/**
 * Next.js API request body structure
 */
data class YouTubeiRequestBody(
    val context: ContextData,
    val query: String? = null,
    val videoId: String? = null
)

data class ContextData(
    @SerializedName("client") val client: ClientData
)

/**
 * Client configuration for YouTubei API
 * Default region set to US as a neutral fallback.
 */
data class ClientData(
    @SerializedName("clientName") val clientName: String = "ANDROID_MUSIC",
    @SerializedName("clientVersion") val clientVersion: String = "6.42.52",
    @SerializedName("androidSdkVersion") val androidSdkVersion: Int = 34,
    @SerializedName("hl") val hl: String = "en",
    @SerializedName("gl") val gl: String = "US",
    @SerializedName("platform") val platform: String = "MOBILE",
    @SerializedName("osName") val osName: String = "Android",
    @SerializedName("osVersion") val osVersion: String = "14"
)

/**
 * Stream URL response
 */
data class StreamUrlResponse(
    @SerializedName("url") val url: String?,
    @SerializedName("quality") val quality: String?,
    @SerializedName("bitrate") val bitrate: Int?,
    @SerializedName("mimeType") val mimeType: String?
)
