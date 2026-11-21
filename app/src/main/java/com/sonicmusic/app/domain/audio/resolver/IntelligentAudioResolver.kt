package com.sonicmusic.app.domain.audio.resolver

import android.util.Log
import com.sonicmusic.app.data.remote.source.AudioStreamExtractor
import com.sonicmusic.app.data.remote.source.YouTubePlaybackSessionStore
import com.sonicmusic.app.domain.audio.models.AudioResolutionResult
import com.sonicmusic.app.domain.audio.models.TrackIdentity
import com.sonicmusic.app.domain.model.AudioStreamCandidate
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.features.player.audio.PlaybackDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intelligent Audio Resolver.
 *
 * Implements [AudioResolver] with non-blocking fast start:
 * 1. Launches playback immediately via Innertube.
 * 2. Manages lifecycle cancellation so obsolete background searches cannot corrupt new playback.
 */
@Singleton
class IntelligentAudioResolver @Inject constructor(
    private val audioStreamExtractor: AudioStreamExtractor,
    private val audioSourceSelector: AudioSourceSelector,
    private val playbackDiagnostics: PlaybackDiagnostics,
    private val playbackSessionStore: YouTubePlaybackSessionStore,
) : AudioResolver {

    companion object {
        private const val TAG = "IntelligentResolver"
    }

    private val activeJobs = ConcurrentHashMap<String, Job>()

    // In-memory cache for resolved multi-source decisions
    data class CachedResolution(
        val candidate: AudioStreamCandidate,
        val info: AudioStreamInfo,
        val expiresAtMs: Long
    )
    private data class ResolutionKey(val trackId: String, val quality: StreamQuality, val sessionRevision: Long)
    private val resolutionCache = android.util.LruCache<ResolutionKey, CachedResolution>(128)

    /**
     * Cancel any active background resolution for the specified track ID.
     */
    fun cancelResolution(trackId: String) {
        val job = activeJobs.remove(trackId)
        if (job != null && job.isActive) {
            Log.d(TAG, "Cancelled pending resolution job for track $trackId")
            job.cancel()
        }
    }

    /**
     * Cancel all active background resolutions (e.g. queue cleared or playback stopped).
     */
    fun cancelAllResolutions() {
        Log.d(TAG, "Cancelling all active resolution jobs (${activeJobs.size} active)")
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    fun clearCache() {
        resolutionCache.evictAll()
    }

    suspend fun resolveFallback(
        track: TrackIdentity,
        failedCandidate: AudioStreamCandidate,
        quality: StreamQuality = StreamQuality.BEST
    ): Result<Pair<AudioStreamCandidate, AudioStreamInfo>> {
        return resolveStream(
            track = track,
            quality = quality,
            forceRefresh = true,
            excludeUrls = setOf(failedCandidate.url)
        )
    }

    override suspend fun resolveStream(
        track: TrackIdentity,
        quality: StreamQuality,
        forceRefresh: Boolean,
        excludeUrls: Set<String>,
        onUpgradeAvailable: ((AudioStreamCandidate, AudioStreamInfo) -> Unit)?
    ): Result<Pair<AudioStreamCandidate, AudioStreamInfo>> = withContext(Dispatchers.IO) {
        val videoId = track.trackId
        val wantsHighQuality = quality == StreamQuality.HIGH || quality == StreamQuality.BEST
        val snapshot = if (wantsHighQuality) playbackSessionStore.snapshot() else null
        val cacheKey = ResolutionKey(videoId, quality, snapshot?.revision ?: 0L)

        // Cancel any pending discovery job for this track before starting new
        cancelResolution(videoId)

        // 1. Check cached resolution if still valid
        if (!forceRefresh) {
            val cached = resolutionCache.get(cacheKey)
            if (cached != null && !cached.candidate.isExpired() && cached.expiresAtMs > System.currentTimeMillis() &&
                cached.candidate.url !in excludeUrls &&
                (snapshot == null || snapshot.revision == playbackSessionStore.revision)
            ) {
                Log.d(TAG, "Serving cached audio resolution for '$videoId' (${cached.candidate.sourceType})")
                playbackDiagnostics.onCandidateSelected(videoId, cached.candidate)
                return@withContext Result.success(Pair(cached.candidate, cached.info))
            }
        }

        val (publicResult, accountCandidates) = coroutineScope {
            val authenticated = snapshot?.session?.let { session ->
                async { audioStreamExtractor.extractAuthenticatedCandidates(videoId, session) }
            }
            audioStreamExtractor.extractAllCandidates(videoId, forceRefresh, excludeUrls) to authenticated?.await().orEmpty()
        }
        val usableAccountCandidates = if (snapshot?.revision == playbackSessionStore.revision) {
            accountCandidates
        } else {
            emptyList()
        }
        if (usableAccountCandidates.isEmpty() && publicResult.isFailure) {
            return@withContext Result.failure(checkNotNull(publicResult.exceptionOrNull()))
        }
        val candidates = (usableAccountCandidates + publicResult.getOrNull().orEmpty()).distinctBy { it.url }

        val selection = audioSourceSelector.selectBestSource(
            innertubeCandidates = candidates,
            requestedQuality = quality,
            excludeUrls = excludeUrls
        )
        return@withContext handleSelectionResult(cacheKey, selection)
    }

    private fun handleSelectionResult(
        cacheKey: ResolutionKey,
        result: AudioResolutionResult,
    ): Result<Pair<AudioStreamCandidate, AudioStreamInfo>> {
        val videoId = cacheKey.trackId
        return when (result) {
            is AudioResolutionResult.Success -> {
                val candidate = result.selectedCandidate
                val info = candidate.toAudioStreamInfo(cacheKey.quality)
                resolutionCache.put(cacheKey, CachedResolution(
                    candidate = candidate,
                    info = info,
                    expiresAtMs = candidate.expiresAtMs ?: (System.currentTimeMillis() + 15 * 60 * 1000L)
                ))
                playbackDiagnostics.onCandidateSelected(videoId, candidate)
                Result.success(Pair(candidate, info))
            }
            is AudioResolutionResult.Failure -> {
                Log.e(TAG, "Audio resolution failed for $videoId: ${result.failureReason}")
                Result.failure(IllegalStateException(result.failureReason))
            }
        }
    }
}
