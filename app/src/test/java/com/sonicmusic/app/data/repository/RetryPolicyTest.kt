package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.remote.source.InvalidHttpCodeException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class RetryPolicyTest {

    @Test
    fun `execute retries 429 and eventually succeeds`() = runTest {
        val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 1000L)
        var attempts = 0

        val result = policy.execute {
            attempts += 1
            if (attempts < 3) {
                Result.failure(InvalidHttpCodeException(429))
            } else {
                Result.success("ok")
            }
        }

        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
        assertEquals(3, attempts)
    }

    @Test
    fun `execute does not retry unknown host`() = runTest {
        val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 1000L)
        var attempts = 0

        val result = policy.execute {
            attempts += 1
            Result.failure<String>(UnknownHostException("offline"))
        }

        assertTrue(result.isFailure)
        assertEquals(1, attempts)
    }
}
