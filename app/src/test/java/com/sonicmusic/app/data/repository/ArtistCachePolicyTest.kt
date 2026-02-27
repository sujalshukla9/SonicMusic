package com.sonicmusic.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistCachePolicyTest {

    private val policy = ArtistCachePolicy(
        artistPageTtlMs = 30 * 60 * 1000L,
        staleToleranceMs = 24 * 60 * 60 * 1000L
    )

    @Test
    fun `isExpired returns false within ttl`() {
        val now = 1_000_000L
        val fetchedAt = now - (29 * 60 * 1000L)

        assertFalse(policy.isExpired(fetchedAt, now))
    }

    @Test
    fun `isExpired returns true after ttl`() {
        val now = 1_000_000L
        val fetchedAt = now - (31 * 60 * 1000L)

        assertTrue(policy.isExpired(fetchedAt, now))
    }

    @Test
    fun `isStaleBeyondTolerance returns true after 24h window`() {
        val now = 2_000_000L
        val fetchedAt = now - (24 * 60 * 60 * 1000L + 1)

        assertTrue(policy.isStaleBeyondTolerance(fetchedAt, now))
    }
}
