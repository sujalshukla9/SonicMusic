package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class HomeContent(
    val shelves: List<HomeShelf> = emptyList(),
    val continuation: HomeContinuation? = null
)

@Serializable
data class CachedHomeFeed(
    val firstPage: HomeContent,
    val loadedPages: List<CachedHomePage> = emptyList(),
    val schemaVersion: Int = 0
) {
    companion object {
        const val CURRENT_VERSION = 4
    }
}

@Serializable
data class CachedHomePage(val requestToken: String, val content: HomeContent)

@Immutable
@Serializable
data class HomeContinuation(
    val token: String,
    val useQueryParameters: Boolean,
    val visitorData: String? = null
)

@Immutable
@Serializable
data class HomeShelf(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val layout: Layout = Layout.Carousel,
    val items: List<HomeShelfItem>,
    val kind: Kind = Kind.Other
) {
    enum class Layout { Carousel, Grid, List }
    enum class Kind { Other, ListenAgain, QuickPicks, ForgottenFavorites }

    fun songs(): List<Song> = items.mapNotNull(HomeShelfItem::playableSong).distinctBy(Song::id)
}

@Immutable
@Serializable
sealed interface HomeShelfItem {
    val key: String
    val title: String
    val subtitle: String
    val artwork: String
    val playableSong: Song? get() = null

    @Serializable
    data class Media(val song: Song) : HomeShelfItem {
        override val key get() = "${song.contentType}:${song.id}"
        override val title get() = song.title
        override val subtitle get() = song.artist
        override val artwork get() = song.thumbnailUrl
        override val playableSong get() = song.takeIf { it.contentType == ContentType.SONG }
    }

    @Serializable
    data class Playlist(val playlist: CommunityPlaylist) : HomeShelfItem {
        override val key get() = "playlist:${playlist.id}"
        override val title get() = playlist.title
        override val subtitle get() = playlist.creator
        override val artwork get() = playlist.thumbnailUrl
    }
}
