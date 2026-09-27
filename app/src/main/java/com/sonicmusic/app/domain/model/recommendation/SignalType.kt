package com.sonicmusic.app.domain.model.recommendation

/**
 * All user behavior signal types with their raw values and importance weights.
 * Maps directly to the design doc Section 2 signal table.
 *
 * Raw value range: [-1, +1]
 * Weight: how strongly this signal moves the user profile vector.
 */
enum class SignalType(
    val rawValue: Float,
    val weight: Float
) {
    // ═══════════════════════════════════════════
    // POSITIVE SIGNALS
    // ═══════════════════════════════════════════
    SONG_COMPLETED(+1.0f, 1.0f),           // >90% completion — strongest unambiguous positive
    REPLAY(+1.0f, 1.5f),                    // Same-session replay — explicit "again" intent
    LIKE(+1.0f, 1.8f),                      // Explicit deliberate positive
    ADD_TO_LIBRARY(+1.0f, 1.6f),            // Explicit long-term intent
    SHARE(+1.0f, 1.4f),                     // Explicit endorsement
    DOWNLOAD(+0.9f, 1.3f),                  // Intent to re-listen offline
    PLAY_STARTED(+0.3f, 0.3f),             // Weak positive — just started
    COMPLETION_PARTIAL(+0.6f, 0.7f),        // 50-90% completion
    COMPLETION_LOW(+0.2f, 0.2f),            // <30% no explicit skip
    ARTIST_PAGE_OPENED(+0.5f, 0.5f),        // Interest signal
    ALBUM_OPENED(+0.4f, 0.4f),
    PLAYLIST_OPENED(+0.3f, 0.3f),
    SEARCH_MATCH(+0.4f, 0.5f),             // Search query matches artist/genre

    // ═══════════════════════════════════════════
    // NEGATIVE SIGNALS
    // ═══════════════════════════════════════════
    SKIP_LATE(-0.1f, 0.1f),                 // Skip after >90% — barely negative
    SKIP_MID(-0.4f, 0.6f),                  // Skip 30-90%
    SKIP_EARLY(-0.9f, 1.7f),               // <10s or <10% — near-instant rejection
    DISLIKE(-1.0f, 2.0f),                   // Strongest signal overall
    REMOVE_FROM_LIBRARY(-0.7f, 1.2f);       // Explicit reversal of prior positive

    /** Signed contribution = sign(rawValue) * |rawValue| * weight */
    val contribution: Float get() = rawValue * weight

    val isPositive: Boolean get() = rawValue > 0f
    val isNegative: Boolean get() = rawValue < 0f
}
