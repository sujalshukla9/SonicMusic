package com.sonicmusic.app.domain.model

data class RecentSearch(
    val query: String,
    val searchedAt: Long,
    val resultId: String? = null,
    val resultType: String? = null
)