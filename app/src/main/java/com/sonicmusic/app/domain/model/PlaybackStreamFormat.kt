package com.sonicmusic.app.domain.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Identifies a YouTube stream across URL refreshes while preserving other client variants. */
data class PlaybackStreamFormat(val itag: Int, val client: String?) {
    companion object {
        fun fromUrl(url: String, itag: Int? = null): PlaybackStreamFormat? {
            val mediaUrl = url.toHttpUrlOrNull() ?: return null
            if (mediaUrl.host != "googlevideo.com" && !mediaUrl.host.endsWith(".googlevideo.com")) {
                return null
            }
            val format = itag ?: mediaUrl.queryParameter("itag")?.toIntOrNull() ?: return null
            return PlaybackStreamFormat(format, mediaUrl.queryParameter("c")?.uppercase())
        }
    }
}
