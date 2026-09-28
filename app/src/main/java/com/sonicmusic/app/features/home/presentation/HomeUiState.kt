package com.sonicmusic.app.features.home.presentation

import androidx.compose.runtime.Immutable
import com.sonicmusic.app.domain.model.HomeContent

/**
 * Standard Home Status states representing the recommendation pipeline lifecycle.
 */
enum class HomeStatus {
    /** Initial load with no cached content available yet */
    LOADING,

    /** Displaying cached Home content while fresh data is being fetched silently */
    CACHED,

    /** User triggered pull-to-refresh or background sync is updating changed sections */
    REFRESHING,

    /** Fresh recommendations loaded and synchronized with cache */
    SUCCESS,

    /** Request failed; previous cached content remains visible */
    ERROR
}

/**
 * Unified Home UI State for smooth, non-blocking UI rendering.
 */
@Immutable
data class HomeUiState(
    val status: HomeStatus = HomeStatus.LOADING,
    val content: HomeContent = HomeContent(),
    val isRefreshing: Boolean = false,
    val lastUpdated: Long? = null,
    val error: String? = null
) {
    /** True if there is any content ready to render immediately */
    val hasContent: Boolean get() = content.hasAnyContent()

    /** True if the user should see a full loading placeholder (only on cold start with 0 cache) */
    val isInitialLoading: Boolean get() = status == HomeStatus.LOADING && !hasContent
}
