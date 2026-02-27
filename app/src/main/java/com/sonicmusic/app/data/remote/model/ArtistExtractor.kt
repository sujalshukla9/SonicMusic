package com.sonicmusic.app.data.remote.model

object ArtistExtractor {
    
    private val TYPE_LABELS = setOf(
        "Song", "Video", "EP", "Single", "Album", "Playlist",
        "Podcast", "Episode", "Station", "Profile", "Artist",
        // Localized labels
        "Canción", "Vídeo", "Chanson", "Vidéo",
        "曲", "動画", "歌曲", "影片",
        "Lied", "Musik", "Bài hát", "Песня"
    )
    
    private val YEAR_REGEX = Regex("^\\d{4}$")
    private val DURATION_REGEX = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
    private val VIEW_COUNT_REGEX = Regex(
        "^[\\d,.]+\\s*[KMBkmb]?\\s*(views?|plays?|listeners?).*$",
        RegexOption.IGNORE_CASE
    )
    private val TOPIC_SUFFIX_REGEX = Regex("\\s*-\\s*Topic$")
    private const val SEPARATOR = " • "
    
    /**
     * PRIMARY ENTRY POINT
     * Call this with runs from any InnerTube response
     */
    fun extract(
        runs: List<Run>?,
        shortBylineRuns: List<Run>? = null,
        playerAuthor: String? = null,
        playerChannelId: String? = null
    ): ArtistInfo {
        
        // ── PRIORITY 1: Runs with pageType = ARTIST ──
        runs?.let { r ->
            val artistRuns = r.filter { 
                it.pageType == "MUSIC_PAGE_TYPE_ARTIST" 
            }
            if (artistRuns.isNotEmpty()) {
                // Check if there is only 1 browseId and it spans across a band name separator
                if (isSingleBandEntity(artistRuns)) {
                    val bandRun = artistRuns.first()
                    return ArtistInfo(
                        displayName = bandRun.text.trim(),
                        individuals = listOf(ExtractedArtist(bandRun.text.trim(), bandRun.browseId)),
                        source = ArtistSource.RUNS_PAGE_TYPE,
                        confidence = 1.0f
                    )
                }

                return buildArtistInfo(
                    artistRuns, 
                    ArtistSource.RUNS_PAGE_TYPE, 
                    confidence = 1.0f
                )
            }
        }
        
        // ── PRIORITY 2: shortBylineText (from /next) ──
        shortBylineRuns?.let { r ->
            val artistRuns = r.filter { 
                it.pageType == "MUSIC_PAGE_TYPE_ARTIST" 
                || it.browseId?.startsWith("UC") == true 
            }
            if (artistRuns.isNotEmpty()) {
                if (isSingleBandEntity(artistRuns)) {
                    val bandRun = artistRuns.first()
                    return ArtistInfo(
                        displayName = bandRun.text.trim(),
                        individuals = listOf(ExtractedArtist(bandRun.text.trim(), bandRun.browseId)),
                        source = ArtistSource.SHORT_BYLINE,
                        confidence = 0.9f
                    )
                }
                return buildArtistInfo(
                    artistRuns, 
                    ArtistSource.SHORT_BYLINE, 
                    confidence = 0.9f
                )
            }
            // If shortByline has no endpoints but has text
            val textRuns = r.filter { 
                it.text.trim() !in setOf("•", "&", ",", "")
                && !it.text.trim().startsWith("•")
            }
            if (textRuns.isNotEmpty()) {
                return ArtistInfo(
                    displayName = textRuns.joinToString("") { it.text }.trim(),
                    individuals = textRuns.map { ExtractedArtist(it.text.trim(), it.browseId) },
                    source = ArtistSource.SHORT_BYLINE,
                    confidence = 0.85f
                )
            }
        }
        
        // ── PRIORITY 3: Runs with UC... browseId ──
        runs?.let { r ->
            val artistRuns = r.filter { 
                it.browseId?.startsWith("UC") == true 
            }
            if (artistRuns.isNotEmpty()) {
                if (isSingleBandEntity(artistRuns)) {
                    val bandRun = artistRuns.first()
                    return ArtistInfo(
                        displayName = bandRun.text.trim(),
                        individuals = listOf(ExtractedArtist(bandRun.text.trim(), bandRun.browseId)),
                        source = ArtistSource.RUNS_BROWSE_ID,
                        confidence = 0.85f
                    )
                }
                return buildArtistInfo(
                    artistRuns, 
                    ArtistSource.RUNS_BROWSE_ID, 
                    confidence = 0.85f
                )
            }
        }
        
        // ── PRIORITY 4: Text-based parsing ──
        runs?.let { r ->
            val fullText = r.joinToString("") { it.text }
            val parsed = parseArtistFromText(fullText)
            if (parsed != null) {
                return parsed
            }
        }
        
        // ── PRIORITY 5: Player author fallback ──
        playerAuthor?.let { author ->
            val cleanName = author
                .replace(TOPIC_SUFFIX_REGEX, "")
                .removeSuffix("VEVO")
                .trim()
            if (cleanName.isNotBlank()) {
                return ArtistInfo(
                    displayName = cleanName,
                    individuals = listOf(
                        ExtractedArtist(cleanName, playerChannelId)
                    ),
                    source = ArtistSource.PLAYER_AUTHOR,
                    confidence = 0.5f
                )
            }
        }
        
        // ── PRIORITY 6: Absolute fallback ──
        return ArtistInfo(
            displayName = "Unknown Artist",
            individuals = emptyList(),
            source = ArtistSource.FALLBACK,
            confidence = 0.0f
        )
    }

    private fun isSingleBandEntity(artistRuns: List<Run>): Boolean {
        if (artistRuns.size == 1) {
            val run = artistRuns.first()
            if (run.browseId != null && (run.text.contains("&") || run.text.contains(","))) {
                return true
            }
        }
        return false
    }
    
    private fun buildArtistInfo(
        artistRuns: List<Run>,
        source: ArtistSource,
        confidence: Float
    ): ArtistInfo {
        val artists = artistRuns.map { 
            ExtractedArtist(
                name = it.text.trim(),
                browseId = it.browseId
            )
        }
        return ArtistInfo(
            displayName = formatArtistNames(artists.map { it.name }),
            individuals = artists,
            source = source,
            confidence = confidence
        )
    }
    
    private fun parseArtistFromText(fullText: String): ArtistInfo? {
        val segments = fullText.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        
        val candidates = segments.filter { segment ->
            !isTypeLabel(segment)
            && !isYear(segment)
            && !isDuration(segment)
            && !isViewCount(segment)
            && !segment.equals("Various Artists", ignoreCase = true)
        }
        
        if (segments.any { it.equals("Various Artists", ignoreCase = true) }) {
             return ArtistInfo(
                displayName = "Various Artists",
                individuals = listOf(ExtractedArtist("Various Artists")),
                source = ArtistSource.TEXT_PARSING,
                confidence = 0.6f
            )
        }

        if (candidates.isEmpty()) return null
        
        val artistText = candidates.first()
        val individualNames = splitMultipleArtists(artistText)
        
        return ArtistInfo(
            displayName = artistText.trim(),
            individuals = individualNames.map { ExtractedArtist(it) },
            source = ArtistSource.TEXT_PARSING,
            confidence = 0.6f
        )
    }
    
    private fun splitMultipleArtists(text: String): List<String> {
        val parts = text.split(
            Regex("\\s*(?:feat\\.?|ft\\.?|featuring)\\s*", RegexOption.IGNORE_CASE)
        )
        
        return parts.flatMap { part ->
            part.split(Regex("\\s*[,&]\\s*"))
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length > 1 }
        }
    }
    
    private fun formatArtistNames(names: List<String>): String {
        return when (names.size) {
            0 -> "Unknown Artist"
            1 -> names[0]
            2 -> "${names[0]} & ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
        }
    }
    
    private fun isTypeLabel(text: String) = text.trim() in TYPE_LABELS
    private fun isYear(text: String) = YEAR_REGEX.matches(text.trim()) 
        && text.trim().toIntOrNull() in 1900..2099
    private fun isDuration(text: String) = DURATION_REGEX.matches(text.trim())
    private fun isViewCount(text: String) = VIEW_COUNT_REGEX.matches(text.trim())
}
