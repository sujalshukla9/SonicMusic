package com.sonicmusic.app.features.search.presentation

import com.sonicmusic.app.domain.repository.SearchResult
import com.sonicmusic.app.shared.state.PaginationState

internal fun SearchResult.canAdvanceFrom(offset: Int, continuation: String?): Boolean =
    hasMore && (
        (!continuationToken.isNullOrBlank() && continuationToken != continuation) ||
            (nextOffset != null && nextOffset > offset)
        )

internal fun paginationAfterPage(canAdvance: Boolean, addedItems: Boolean): PaginationState = when {
    !canAdvance -> PaginationState.NoMoreData
    addedItems -> PaginationState.Idle
    else -> PaginationState.LoadMore
}
