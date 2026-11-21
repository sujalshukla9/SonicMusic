package com.sonicmusic.app.domain.model

import kotlinx.serialization.Serializable

enum class ChartKind { Songs, Artists, Albums }

@Serializable
enum class ChartOrigin { Chart, MusicVideos, NewReleases }

@Serializable
data class ChartItem(val media: Song, val rank: Int? = null)

@Serializable
data class ChartPage(
    val items: List<ChartItem>,
    val origin: ChartOrigin = ChartOrigin.Chart,
    val sourceTitle: String? = null,
    val hasMore: Boolean = false
)

