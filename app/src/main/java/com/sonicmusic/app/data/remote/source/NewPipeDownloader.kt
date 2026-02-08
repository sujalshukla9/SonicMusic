package com.sonicmusic.app.data.remote.source

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * Custom Downloader for NewPipe Extractor
 * 
 * Required by NewPipe Extractor library to make HTTP requests.
 * Uses OkHttp for network calls.
 */
class NewPipeDownloader private constructor() : Downloader() {

    companion object {
        private var instance: NewPipeDownloader? = null
        
        @Synchronized
        fun getInstance(): NewPipeDownloader {
            if (instance == null) {
                instance = NewPipeDownloader()
            }
            return instance!!
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun execute(request: NewPipeRequest): Response {
        val url = request.url()
        val httpMethod = request.httpMethod()
        val dataToSend = request.dataToSend()
        val requestHeaders = request.headers()

        val requestBuilder = Request.Builder()
            .url(url)
            .method(
                httpMethod,
                if (dataToSend != null) dataToSend.toRequestBody() else null
            )

        // Add headers
        requestHeaders.forEach { (key, values) ->
            values.forEach { value ->
                requestBuilder.addHeader(key, value)
            }
        }

        // Add default User-Agent if not present
        if (!requestHeaders.containsKey("User-Agent")) {
            requestBuilder.addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        }

        val response = client.newCall(requestBuilder.build()).execute()
        
        // Read body ONCE (OkHttp body can only be consumed once)
        val responseBody = response.body?.string()

        // Check for ReCaptcha (using already-read body)
        if (response.code == 429 || (responseBody?.contains("recaptcha") == true)) {
            response.close()
            throw ReCaptchaException("reCAPTCHA challenge", url)
        }

        // Build response headers map
        val responseHeaders = mutableMapOf<String, List<String>>()
        response.headers.names().forEach { name ->
            responseHeaders[name] = response.headers.values(name)
        }

        return Response(
            response.code,
            response.message,
            responseHeaders,
            responseBody,
            response.request.url.toString()
        )
    }
}
