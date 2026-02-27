package com.sonicmusic.app.core.util

object ThumbnailUrlUtils {
    private val videoIdRegex = Regex("(?:vi(?:_webp)?/|v=|youtu\\.be/)([A-Za-z0-9_-]{11})")
    private val iYtImgPathRegex = Regex("https?://i\\.ytimg\\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/([^?]+)")
    private val iYtImgUrlRegex = Regex("https?://i\\.ytimg\\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/[^\\s]+")

    fun toHighQuality(url: String?, videoId: String? = null): String? {
        val cleanedUrl = url?.trim().orEmpty().takeIf { it.isNotEmpty() }
        val id = normalizeVideoId(videoId) ?: extractVideoId(cleanedUrl)

        if (cleanedUrl != null) {
            iYtImgPathRegex.find(cleanedUrl)?.groupValues?.getOrNull(1)?.let { matchedId ->
                val lower = cleanedUrl.lowercase()
                // Keep extractor-provided tuned URLs (often square with crop hints).
                if (
                    lower.contains("sqp=") ||
                    lower.contains("rs=") ||
                    lower.contains("w120-h120") ||
                    lower.contains("w544-h544") ||
                    lower.contains("w1080-h1080")
                ) {
                    return cleanedUrl
                }
                // Upgrade non-tuned i.ytimg URLs to high resolution.
                return "https://i.ytimg.com/vi/$matchedId/maxresdefault.jpg"
            }

            var upgraded = cleanedUrl
            if (upgraded.contains("googleusercontent.com", ignoreCase = true)) {
                // Keep Innertube-selected URL and bump render size hints for higher detail.
                upgraded = upgraded
                    .replace(Regex("=w\\d+-h\\d+[^&]*"), "=w1080-h1080-l90-rj")
                    .replace(Regex("=s\\d+[^&]*"), "=s1080")
                    .replace("w120-h120", "w1080-h1080")
                    .replace("w60-h60", "w1080-h1080")
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
        val iYtImgMatch = cleanedUrl?.let { iYtImgPathRegex.find(it) }
        val iYtImgVideoId = iYtImgMatch?.groupValues?.getOrNull(1) ?: id

        val upgraded = toHighQuality(cleanedUrl, id)
        if (!upgraded.isNullOrBlank() && !isLowQualityVariant(upgraded)) {
            candidates.add(upgraded)
        }

        if (!cleanedUrl.isNullOrBlank() && !isLowQualityVariant(cleanedUrl)) {
            candidates.add(cleanedUrl)
        }

        // After trying the server-provided URL, fan out to fallback variants.
        if (iYtImgVideoId != null) {
            appendVideoIdFallbackVariants(videoId = iYtImgVideoId, output = candidates)
        }

        // Keep low-quality fallbacks at the end.
        if (!upgraded.isNullOrBlank() && isLowQualityVariant(upgraded)) {
            candidates.add(upgraded)
        }
        if (!cleanedUrl.isNullOrBlank() && isLowQualityVariant(cleanedUrl)) {
            candidates.add(cleanedUrl)
        }

        if (iYtImgVideoId == null && !cleanedUrl.isNullOrBlank()) {
            appendYouTubeFallbackVariants(baseUrl = cleanedUrl, output = candidates)
        }

        return candidates.toList()
    }

    /**
     * Display-oriented thumbnail URL normalization.
     * Prefers broadly available variants first so list/grid artwork appears faster.
     */
    fun toDisplayQuality(url: String?, videoId: String? = null): String? {
        val cleanedUrl = url?.trim().orEmpty().takeIf { it.isNotEmpty() }
        val id = normalizeVideoId(videoId) ?: extractVideoId(cleanedUrl)

        if (cleanedUrl != null) {
            iYtImgPathRegex.find(cleanedUrl)?.groupValues?.getOrNull(1)?.let { matchedId ->
                val lower = cleanedUrl.lowercase()
                if (
                    lower.contains("sqp=") ||
                    lower.contains("rs=") ||
                    lower.contains("w120-h120") ||
                    lower.contains("w544-h544") ||
                    lower.contains("w1080-h1080")
                ) {
                    return cleanedUrl
                }
                return "https://i.ytimg.com/vi/$matchedId/hqdefault.jpg"
            }

            if (cleanedUrl.contains("googleusercontent.com", ignoreCase = true)) {
                return cleanedUrl
                    .replace(Regex("=w\\d+-h\\d+[^&]*"), "=w640-h640-l90-rj")
                    .replace(Regex("=s\\d+[^&]*"), "=s640")
                    .replace("w120-h120", "w640-h640")
                    .replace("w60-h60", "w640-h640")
            }

            return cleanedUrl
        }

        return id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    }

    /**
     * Candidate order tuned for fast first-paint in feed/list UIs.
     */
    fun buildDisplayCandidates(url: String?, videoId: String? = null): List<String> {
        val cleanedUrl = url?.trim().orEmpty().takeIf { it.isNotEmpty() }
        val id = normalizeVideoId(videoId) ?: extractVideoId(cleanedUrl)
        val candidates = LinkedHashSet<String>()

        val displayUrl = toDisplayQuality(cleanedUrl, id)
        if (!displayUrl.isNullOrBlank()) {
            candidates.add(displayUrl)
        }

        if (!cleanedUrl.isNullOrBlank()) {
            candidates.add(cleanedUrl)
        }

        if (id != null) {
            appendDisplayFallbackVariants(id, candidates)
        } else if (!cleanedUrl.isNullOrBlank()) {
            appendYouTubeFallbackVariants(baseUrl = cleanedUrl, output = candidates)
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
        iYtImgUrlRegex.find(url)?.groupValues?.getOrNull(1)?.let { return it }
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
            "https://i.ytimg.com/vi/$videoId/default.jpg",
            "https://i.ytimg.com/vi_webp/$videoId/maxresdefault.webp",
            "https://i.ytimg.com/vi_webp/$videoId/hq720.webp",
            "https://i.ytimg.com/vi_webp/$videoId/hqdefault.webp",
            "https://i.ytimg.com/vi_webp/$videoId/mqdefault.webp"
        ).forEach(output::add)
    }

    private fun appendDisplayFallbackVariants(videoId: String, output: MutableSet<String>) {
        listOf(
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/sddefault.jpg",
            "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            "https://i.ytimg.com/vi_webp/$videoId/hqdefault.webp",
            "https://i.ytimg.com/vi/$videoId/hq720.jpg",
            "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
        ).forEach(output::add)
    }

    private fun isLowQualityVariant(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/default.jpg") ||
            lower.contains("/mqdefault.jpg") ||
            lower.contains("/hqdefault.jpg") ||
            lower.contains("/default.webp") ||
            lower.contains("/mqdefault.webp") ||
            lower.contains("/hqdefault.webp")
    }
}
