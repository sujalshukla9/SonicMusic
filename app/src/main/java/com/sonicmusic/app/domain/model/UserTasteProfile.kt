package com.sonicmusic.app.domain.model

/**
 * User's listening taste profile based on playback history analysis
 */
data class UserTasteProfile(
    val topArtists: List<String> = emptyList(),
    val preferredLanguages: List<String> = listOf("Hindi", "English"),
    val listeningPattern: ListeningPattern = ListeningPattern.MIXED,
    val completionRate: Float = 0.5f,
    val avgSessionDuration: Long = 0L,
    val topSearchQueries: List<String> = emptyList()
) {
    companion object {
        val DEFAULT = UserTasteProfile()
    }
}

enum class ListeningPattern {
    MORNING_LISTENER,   // Active 5AM-12PM
    AFTERNOON_LISTENER, // Active 12PM-5PM
    EVENING_LISTENER,   // Active 5PM-9PM
    NIGHT_LISTENER,     // Active 9PM-2AM
    MIXED               // No clear pattern
}
