package com.sonicmusic.app.data.remote.service

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse
import java.io.IOException
import java.util.concurrent.TimeUnit

class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: NewPipeRequest): NewPipeResponse {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = Request.Builder()
            .url(url)

        // Add headers
        headers.forEach { (key, list) ->
            list.forEach { value ->
                requestBuilder.addHeader(key, value)
            }
        }

        // Handle body
        if (dataToSend != null) {
            val requestBody = RequestBody.create(null, dataToSend)
            requestBuilder.method(httpMethod, requestBody)
        } else if (httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH") {
             requestBuilder.method(httpMethod, RequestBody.create(null, ByteArray(0)))
        } else {
            requestBuilder.method(httpMethod, null)
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body
            val responseCode = response.code
            val responseMessage = response.message
            val responseHeaders = HashMap<String, List<String>>()
            
            response.headers.names().forEach { name ->
                responseHeaders[name] = response.headers.values(name)
            }

            return NewPipeResponse(
                responseCode,
                responseMessage,
                responseHeaders,
                responseBody?.string() ?: "",
                null // simplified for now
            )
        } catch (e: IOException) {
            throw IOException("Download failed", e)
        }
    }
}
