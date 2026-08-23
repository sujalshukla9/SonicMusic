package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.HomeSection

interface HomeRepository {
    suspend fun getHomeFeed(customCookies: String? = null): List<HomeSection>
    suspend fun refreshQuickPicks(): HomeSection
    suspend fun refreshMadeForYou(): HomeSection
    suspend fun refreshListenAgain(): HomeSection
    suspend fun refreshForgottenFavorites(): HomeSection
}
