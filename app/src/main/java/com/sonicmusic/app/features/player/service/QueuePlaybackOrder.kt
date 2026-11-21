package com.sonicmusic.app.features.player.service

import com.sonicmusic.app.domain.model.Song

internal fun upcomingPreloadWindow(queue: List<Song>, currentIndex: Int, limit: Int): List<Song> {
    if (currentIndex !in queue.indices) return emptyList()
    val from = currentIndex + 1
    return queue.subList(from, from + limit.coerceIn(0, queue.size - from)).toList()
}

internal fun currentIndexAfterRemoval(currentIndex: Int, removedIndex: Int): Int =
    if (removedIndex >= 0 && removedIndex < currentIndex) currentIndex - 1 else currentIndex

internal fun currentIndexAfterMove(currentIndex: Int, from: Int, to: Int): Int {
    if (currentIndex == from) return to
    val afterRemoval = currentIndexAfterRemoval(currentIndex, from)
    return if (to <= afterRemoval) afterRemoval + 1 else afterRemoval
}

internal fun <T : Any> resolveReadyQueuePrefix(songs: List<Song>, resolve: (Song) -> T?): List<T> =
    buildList {
        for (song in songs) {
            add(resolve(song) ?: break)
        }
    }

internal fun restoreUpcomingQueueOrder(upcoming: List<Song>, originalQueue: List<Song>): List<Song> {
    val originalPositions = originalQueue.mapIndexed { index, song -> song.id to index }.toMap()
    return upcoming.sortedBy { originalPositions[it.id] ?: Int.MAX_VALUE }
}

internal fun MutableMap<String, Song>.addNewRadioTracks(tracks: List<Song>, excludedIds: Set<String>) {
    for (track in tracks) {
        if (track.id.isNotBlank() && track.id !in excludedIds) putIfAbsent(track.id, track)
    }
}
