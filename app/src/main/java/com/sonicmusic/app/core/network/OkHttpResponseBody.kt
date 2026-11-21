package com.sonicmusic.app.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

internal class HttpStatusException(val code: Int) : IllegalStateException("Request failed with HTTP $code")

internal data class HttpResponseBody(val code: Int, val body: String?) {
    val isSuccessful: Boolean get() = code in 200..299
}

internal suspend fun Call.awaitSuccessfulBody(): String? {
    val response = awaitResponseBody()
    if (!response.isSuccessful) throw HttpStatusException(response.code)
    return response.body
}

internal suspend fun Call.awaitResponseBody(): HttpResponseBody =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use {
                        HttpResponseBody(it.code, if (it.isSuccessful) it.body?.string() else null)
                    }
                })
            }
        })
    }
