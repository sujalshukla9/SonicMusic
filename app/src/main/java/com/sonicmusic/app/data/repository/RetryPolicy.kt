package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.remote.source.InvalidHttpCodeException
import kotlinx.coroutines.delay
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RetryPolicy(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1000L
) {
    suspend fun <T> execute(block: suspend () -> Result<T>): Result<T> {
        var currentDelay = initialDelayMs
        var lastResult: Result<T> = Result.failure(IllegalStateException("No attempts executed"))

        repeat(maxRetries + 1) { attempt ->
            val result = block()
            if (result.isSuccess) {
                return result
            }
            lastResult = result

            val error = result.exceptionOrNull()
            val shouldRetry = error?.isRetryable() == true && attempt < maxRetries
            if (!shouldRetry) {
                return result
            }

            delay(currentDelay)
            currentDelay *= 2
        }

        return lastResult
    }

    private fun Throwable.isRetryable(): Boolean {
        return when (this) {
            is SocketTimeoutException -> true
            is UnknownHostException -> false
            is InvalidHttpCodeException -> code in setOf(429, 500, 502, 503)
            else -> false
        }
    }
}
