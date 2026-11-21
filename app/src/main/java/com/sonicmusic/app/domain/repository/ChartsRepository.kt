package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.ChartKind
import com.sonicmusic.app.domain.model.ChartPage

interface ChartsRepository {
    suspend fun cachedChart(kind: ChartKind, scope: HomeFeedScope): ChartPage?
    suspend fun refreshChart(kind: ChartKind, scope: HomeFeedScope, force: Boolean): ChartPage
    suspend fun loadMoreAlbums(scope: HomeFeedScope): ChartPage?
}

