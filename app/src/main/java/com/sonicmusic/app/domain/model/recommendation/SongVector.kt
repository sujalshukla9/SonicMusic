package com.sonicmusic.app.domain.model.recommendation

import kotlin.math.sqrt

/**
 * A fixed-dimension feature vector representing a song or user in the shared
 * recommendation feature space. L2-normalized after construction.
 *
 * Feature layout (MVP, hand-crafted, ~40 dimensions):
 *   [genre_0..genre_K, mood_0..mood_M, tempo_bucket_0..4,
 *    language_0..L, era_decade_0..D, popularity_percentile]
 *
 * @see Section 3 of the design doc
 */
data class SongVector(
    val values: FloatArray
) {
    val dimension: Int get() = values.size

    /** L2 norm of the vector */
    val norm: Float by lazy {
        sqrt(values.fold(0f) { acc, v -> acc + v * v })
    }

    /** Return a new L2-normalized copy (unit vector) */
    fun normalized(): SongVector {
        val n = norm
        if (n == 0f) return this
        return SongVector(FloatArray(dimension) { values[it] / n })
    }

    /**
     * Dot product with another vector of the same dimension.
     * Since both should be pre-normalized, this IS cosine similarity.
     */
    fun dot(other: SongVector): Float {
        require(dimension == other.dimension) { "Dimension mismatch: $dimension vs ${other.dimension}" }
        var sum = 0f
        for (i in values.indices) {
            sum += values[i] * other.values[i]
        }
        return sum
    }

    /**
     * Cosine similarity (safe version — handles un-normalized vectors).
     */
    fun cosineSimilarity(other: SongVector): Float {
        val dotProd = dot(other)
        val denom = norm * other.norm
        return if (denom == 0f) 0f else dotProd / denom
    }

    /**
     * Element-wise addition, returning a new vector.
     */
    operator fun plus(other: SongVector): SongVector {
        require(dimension == other.dimension)
        return SongVector(FloatArray(dimension) { values[it] + other.values[it] })
    }

    /**
     * Scalar multiplication, returning a new vector.
     */
    operator fun times(scalar: Float): SongVector {
        return SongVector(FloatArray(dimension) { values[it] * scalar })
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SongVector) return false
        return values.contentEquals(other.values)
    }

    override fun hashCode(): Int = values.contentHashCode()

    companion object {
        /** Create a zero vector of the given dimension */
        fun zero(dimension: Int) = SongVector(FloatArray(dimension))
    }
}
