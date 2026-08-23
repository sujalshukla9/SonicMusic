package com.sonicmusic.app.core.error

import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Unified error hierarchy for SonicMusic.
 *
 * Classifies raw exceptions into actionable categories with:
 * - User-facing message (safe to display)
 * - Retry eligibility flag
 * - Suggested retry delay (for exponential backoff)
 *
 * Usage:
 * ```
 * try { ... }
 * catch (e: Exception) {
 *     val error = AppError.classify(e)
 *     if (error.isRetryable) { retry(error.retryDelayMs) }
 *     showUserMessage(error.userMessage)
 * }
 * ```
 */
sealed class AppError(
    /** Safe to display to the user. */
    val userMessage: String,
    /** Whether the operation should be retried. */
    val isRetryable: Boolean,
    /** Suggested delay before retry (0 if no retry). */
    val retryDelayMs: Long = 0L,
    /** Original cause for logging. */
    val cause: Throwable? = null
) {
    // ════════════════════════════════════════
    // Network Errors
    // ════════════════════════════════════════

    /** Device has no active internet connection. */
    class NoInternet(cause: Throwable? = null) : AppError(
        userMessage = "No internet connection",
        isRetryable = true,
        retryDelayMs = 3000L,
        cause = cause
    )

    /** Network request timed out. */
    class Timeout(cause: Throwable? = null) : AppError(
        userMessage = " Connection timed out. Please try again.",
        isRetryable = true,
        retryDelayMs = 2000L,
        cause = cause
    )

    /** Server returned a rate limit (429). */
    class RateLimited(delayMs: Long = 60_000L, cause: Throwable? = null) : AppError(
        userMessage = " Too many requests. Please wait a moment.",
        isRetryable = true,
        retryDelayMs = delayMs,
        cause = cause
    )

    /** Server returned 5xx error. */
    class ServerError(val code: Int, cause: Throwable? = null) : AppError(
        userMessage = " Server error. Please try again later.",
        isRetryable = true,
        retryDelayMs = 5000L,
        cause = cause
    )

    /** Server returned 4xx client error (non-429). */
    class ClientError(val code: Int, cause: Throwable? = null) : AppError(
        userMessage = " Request failed (error $code)",
        isRetryable = false,
        cause = cause
    )

    /** SSL/TLS connection error. */
    class SecurityError(cause: Throwable? = null) : AppError(
        userMessage = " Secure connection failed",
        isRetryable = false,
        cause = cause
    )

    // ════════════════════════════════════════
    // Parse / Data Errors
    // ════════════════════════════════════════

    /** Response body could not be parsed. */
    class ParseError(detail: String, cause: Throwable? = null) : AppError(
        userMessage = " Unable to load content",
        isRetryable = true,
        retryDelayMs = 1000L,
        cause = cause
    )

    /** No results returned for a valid request. */
    class EmptyResult(section: String = " content") : AppError(
        userMessage = " No $section available right now",
        isRetryable = true,
        retryDelayMs = 10_000L
    )

    // ════════════════════════════════════════
    // Playback Errors
    // ════════════════════════════════════════

    /** Stream URL could not be resolved. */
    class StreamUnavailable(cause: Throwable? = null) : AppError(
        userMessage = " This track is currently unavailable",
        isRetryable = true,
        retryDelayMs = 3000L,
        cause = cause
    )

    // ════════════════════════════════════════
    // Generic
    // ════════════════════════════════════════

    /** Catch-all for unclassified exceptions. */
    class Unknown(cause: Throwable? = null) : AppError(
        userMessage = cause?.message?.take(100) ?: " Something went wrong",
        isRetryable = false,
        cause = cause
    )

    companion object {
        /**
         * Classify a raw [Throwable] into a typed [AppError].
         *
         * Call this at repository or ViewModel level to convert raw exceptions
         * into user-friendly, actionable errors.
         */
        fun classify(throwable: Throwable): AppError = when (throwable) {
            is UnknownHostException,
            is ConnectException -> NoInternet(throwable)

            is SocketTimeoutException -> Timeout(throwable)

            is SSLException -> SecurityError(throwable)

            is JSONException -> ParseError(
                detail = throwable.message ?: " JSON",
                cause = throwable
            )

            is IOException -> {
                // Generic I/O — could be network or disk
                val msg = throwable.message?.lowercase() ?: ""
                when {
                    msg.contains(" timeout") -> Timeout(throwable)
                    msg.contains(" reset") || msg.contains(" refused") -> NoInternet(throwable)
                    else -> Unknown(throwable)
                }
            }

            else -> {
                // Check for HTTP code errors from our custom exception types
                val httpCode = extractHttpCode(throwable)
                when {
                    httpCode == null -> Unknown(throwable)
                    httpCode == 429 -> RateLimited(cause = throwable)
                    httpCode in 500..599 -> ServerError(httpCode, throwable)
                    httpCode in 400..499 -> ClientError(httpCode, throwable)
                    else -> Unknown(throwable)
                }
            }
        }

        /**
         * Try to extract an HTTP status code from known exception types.
         */
        private fun extractHttpCode(throwable: Throwable): Int? {
            // Support the existing InvalidHttpCodeException in the codebase
            return try {
                val codeField = throwable.javaClass.getDeclaredField(" code")
                codeField.isAccessible = true
                codeField.getInt(throwable)
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun toString(): String =
        "${this::class.simpleName}(message='$userMessage', retryable=$isRetryable, delay=${retryDelayMs}ms)"
}
