package com.sonicmusic.app.features.recommendations.engine

import kotlin.math.ceil

/**
 * Maximal Marginal Relevance (MMR) diversity reranker.
 * Design doc Section 13.
 *
 * After initial scoring, reranks to avoid homogeneous lists:
 *   FinalScore(s | already_selected) =
 *       RecommendationScore(s) - λ_diversity · max_{s' ∈ selected} ContentSim(s, s')
 *
 * Also enforces hard artist cap: no more than ⌈list_length × 0.25⌉ from one artist.
 */
object DiversityReranker {

    /** λ_diversity presets per section type */
    const val LAMBDA_MILD = 0.1f        // Mostly relevance-driven
    const val LAMBDA_DEFAULT = 0.2f     // Good for Quick Picks / Made for You
    const val LAMBDA_STRONG = 0.4f      // Discover section

    /**
     * Apply MMR-style greedy reranking to a pre-scored candidate list.
     *
     * @param rankedCandidates candidates already sorted by RecommendationScore descending
     * @param targetSize number of items to select for the final list
     * @param lambdaDiversity diversity strength (0 = pure relevance, 1 = pure diversity)
     * @param maxArtistRatio max fraction of list from a single artist (default 0.25)
     * @return reranked list of targetSize candidates
     */
    fun rerank(
        rankedCandidates: List<ScoredCandidate>,
        targetSize: Int,
        lambdaDiversity: Float = LAMBDA_DEFAULT,
        maxArtistRatio: Float = 0.25f
    ): List<ScoredCandidate> {
        if (rankedCandidates.isEmpty()) return emptyList()
        if (rankedCandidates.size <= targetSize && lambdaDiversity == 0f) {
            return rankedCandidates.take(targetSize)
        }

        val maxPerArtist = ceil(targetSize * maxArtistRatio).toInt().coerceAtLeast(1)
        val selected = mutableListOf<ScoredCandidate>()
        val remaining = rankedCandidates.toMutableList()
        val artistCounts = mutableMapOf<String, Int>()

        // Step 1: Pick the single highest-scored candidate
        val first = remaining.first()
        selected.add(first)
        remaining.remove(first)
        artistCounts[first.artist.lowercase()] = 1

        // Steps 2-N: Greedy selection with diversity penalty
        while (selected.size < targetSize && remaining.isNotEmpty()) {
            var bestCandidate: ScoredCandidate? = null
            var bestMMRScore = Float.NEGATIVE_INFINITY

            for (candidate in remaining) {
                // Hard artist cap check
                val artistKey = candidate.artist.lowercase()
                if ((artistCounts[artistKey] ?: 0) >= maxPerArtist) continue

                // MMR score = relevance - λ · max similarity to already-selected
                val maxSimilarityToSelected = selected.maxOf { s ->
                    candidate.songVector.cosineSimilarity(s.songVector)
                }
                val mmrScore = candidate.score - lambdaDiversity * maxSimilarityToSelected

                if (mmrScore > bestMMRScore) {
                    bestMMRScore = mmrScore
                    bestCandidate = candidate
                }
            }

            if (bestCandidate == null) break // No valid candidates left

            selected.add(bestCandidate)
            remaining.remove(bestCandidate)
            val artistKey = bestCandidate.artist.lowercase()
            artistCounts[artistKey] = (artistCounts[artistKey] ?: 0) + 1
        }

        return selected
    }

    /**
     * Compute content similarity between two candidates.
     * Used externally for diversity analysis/metrics.
     */
    fun contentSimilarity(a: ScoredCandidate, b: ScoredCandidate): Float {
        return a.songVector.cosineSimilarity(b.songVector)
    }

    /**
     * Calculate the diversity score (Shannon entropy) of a list of candidates
     * based on artist distribution. Higher = more diverse.
     */
    fun artistDiversityScore(candidates: List<ScoredCandidate>): Float {
        if (candidates.isEmpty()) return 0f
        val artistCounts = candidates.groupBy { it.artist.lowercase() }
            .mapValues { it.value.size }
        val total = candidates.size.toFloat()

        var entropy = 0f
        for ((_, count) in artistCounts) {
            val p = count / total
            if (p > 0) {
                entropy -= p * kotlin.math.ln(p)
            }
        }
        return entropy
    }
}
