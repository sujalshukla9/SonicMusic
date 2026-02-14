package com.sonicmusic.app.data.util

object ThumbnailUrlUtils {
    private val videoIdRegex = Regex("(?:vi(?:_webp)?/|v=|youtu\\.be/)([A-Za-z0-9_-]{11})")
    private val iYtImgPathRegex = Regex("https?://i\\.ytimg\\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/([^?]+)")

    fun toHighQuality(url: String?, videoId: String? = null): String? {
        val cleanedUrl = url?.trim().orEmpty().takeIf { it.isNotEmpty() }
        val id = normalizeVideoId(videoId) ?: extractVideoId(cleanedUrl)

        if (cleanedUrl != null) {
            var upgraded = cleanedUrl
            if (upgraded.contains("googleusercontent.com", ignoreCase = true)) {
                // Keep Innertube-selected URL and bump render size hints for higher detail.
                upgraded = upgraded
                    .replace(Regex("=w\\d+-h\\d+[^&]*"), "=w1920-h1920-l90-rj")
                    .replace(Regex("=s\\d+[^&]*"), "=s1920")
                    .replace("w120-h120", "w1920-h1920")
                    .replace("w60-h60", "w1920-h1920")
            }
            return upgraded
        }

        // Fallback when Innertube response has no thumbnail URL.
        return id?.let { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" }
    }

    fun buildCandidates(url: String?, videoId: String? = null): List<String> {
        val cleanedUrl = url?.trim().orEmpty().takeIf { it.isNotEmpty() }
        val id = normalizeVideoId(videoId) ?: extractVideoId(cleanedUrl)
        val candidates = LinkedHashSet<String>()

        val upgraded = toHighQuality(cleanedUrl, id)
        if (!upgraded.isNullOrBlank()) {
            candidates.add(upgraded)
        }
        if (!cleanedUrl.isNullOrBlank()) {
            candidates.add(cleanedUrl)
            appendYouTubeFallbackVariants(baseUrl = cleanedUrl, output = candidates)
        }
        if (id != null) {
            appendVideoIdFallbackVariants(videoId = id, output = candidates)
        }

        return candidates.toList()
    }

    private fun normalizeVideoId(value: String?): String? {
        val normalized = value?.trim()
        if (normalized.isNullOrEmpty()) return null
        return normalized.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
    }

    private fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return videoIdRegex.find(url)?.groupValues?.getOrNull(1)
    }

    private fun appendYouTubeFallbackVariants(baseUrl: String, output: MutableSet<String>) {
        val match = iYtImgPathRegex.find(baseUrl) ?: return
        val id = match.groupValues.getOrNull(1) ?: return
        appendVideoIdFallbackVariants(videoId = id, output = output)
    }

    private fun appendVideoIdFallbackVariants(videoId: String, output: MutableSet<String>) {
        listOf(
            "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/hq720.jpg",
            "https://i.ytimg.com/vi/$videoId/sddefault.jpg",
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/default.jpg"
        ).forEach(output::add)
    }
}
