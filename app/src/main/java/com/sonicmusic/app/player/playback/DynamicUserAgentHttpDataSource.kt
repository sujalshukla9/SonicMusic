package com.sonicmusic.app.player.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * A custom [HttpDataSource] wrapper that dynamically sets the HTTP User-Agent header
 * based on the client type ('c' query parameter) present in the YouTube stream URL.
 * This resolves the root cause of HTTP 403 / 404 errors caused by mismatches
 * between the URL client parameter (e.g. c=WEB) and the hardcoded client request User-Agent.
 */
@UnstableApi
class DynamicUserAgentHttpDataSource(
    private val delegate: DefaultHttpDataSource
) : HttpDataSource {

    companion object {
        private const val TAG = "DynamicUADataSource"
    }

    override fun open(dataSpec: DataSpec): Long {
        val uriString = dataSpec.uri.toString()
        val userAgent: String
        val isWebClient: Boolean

        when {
            uriString.contains("c=VISIONOS") -> {
                Log.d(TAG, "Using VisionOS User-Agent for streaming URL")
                userAgent = "com.google.ios.youtube/21.22.04 (Apple Vision Pro; U; CPU visionOS 2_4 like Mac OS X; en_US)"
                isWebClient = false
            }
            uriString.contains("c=IOS") -> {
                Log.d(TAG, "Using iOS User-Agent for streaming URL")
                userAgent = "com.google.ios.youtube/21.22.04 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X; en_US)"
                isWebClient = false
            }
            uriString.contains("c=ANDROID_MUSIC") -> {
                Log.d(TAG, "Using Android Music User-Agent for streaming URL")
                userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14; en_US) gzip"
                isWebClient = false
            }
            uriString.contains("c=ANDROID") -> {
                Log.d(TAG, "Using Android User-Agent for streaming URL")
                userAgent = "com.google.android.youtube/21.23.483 (Linux; U; Android 14; en_US) gzip"
                isWebClient = false
            }
            else -> {
                // WEB, WEB_REMIX, MWEB, and other clients use desktop/web UA
                Log.d(TAG, "Using Web/Desktop User-Agent for streaming URL")
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
                isWebClient = true
            }
        }

        delegate.setRequestProperty("User-Agent", userAgent)

        // YouTube CDN validates Origin/Referer for WEB-sourced stream URLs.
        // Without these, stream downloads return HTTP 403.
        if (isWebClient) {
            delegate.setRequestProperty("Origin", " https://www.youtube.com")
            delegate.setRequestProperty("Referer", " https://www.youtube.com/")
        }

        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return delegate.read(buffer, offset, length)
    }

    override fun close() {
        delegate.close()
    }

    override fun getUri(): Uri? {
        return delegate.getUri()
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return delegate.getResponseHeaders()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun setRequestProperty(name: String, value: String) {
        delegate.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        delegate.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        delegate.clearAllRequestProperties()
    }

    override fun getResponseCode(): Int {
        return delegate.responseCode
    }
}

/**
 * A custom [HttpDataSource.Factory] that wraps a delegate [DefaultHttpDataSource.Factory]
 * and returns a [DynamicUserAgentHttpDataSource] instance.
 */
@UnstableApi
class DynamicUserAgentHttpDataSourceFactory(
    private val delegateFactory: DefaultHttpDataSource.Factory
) : HttpDataSource.Factory {

    override fun createDataSource(): HttpDataSource {
        val dataSource = delegateFactory.createDataSource()
        return DynamicUserAgentHttpDataSource(dataSource)
    }

    override fun setDefaultRequestProperties(defaultRequestProperties: Map<String, String>): HttpDataSource.Factory {
        delegateFactory.setDefaultRequestProperties(defaultRequestProperties)
        return this
    }
}
