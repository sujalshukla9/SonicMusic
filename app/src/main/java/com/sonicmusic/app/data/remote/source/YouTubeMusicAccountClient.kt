package com.sonicmusic.app.data.remote.source

import android.os.SystemClock
import com.sonicmusic.app.domain.repository.HomeSessionRejectedException
import com.sonicmusic.app.core.network.awaitSuccessfulBody
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeMusicAccountClient @Inject constructor() {
    @Volatile private var lastVerification: Pair<WeakReference<YouTubePlaybackSession>, Long>? = null

    // Keep the account cookies out of redirects, shared HTTP caching, and logging interceptors.
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    internal suspend fun verifySession(
        session: YouTubePlaybackSession,
        language: String,
        region: String,
        acceptLanguage: String,
        reuseRecent: Boolean = false
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val verification = lastVerification
        if (reuseRecent && verification != null && verification.first.get() === session &&
            now - verification.second < VERIFICATION_LIFETIME_MS
        ) return true

        val menu = post("/youtubei/v1/account/account_menu", session, language, region, acceptLanguage) {}
        val verified = hasActiveAccount(menu)
        lastVerification = if (verified) WeakReference(session) to SystemClock.elapsedRealtime() else null
        return verified
    }

    internal suspend fun post(
        endpoint: String,
        session: YouTubePlaybackSession,
        language: String,
        region: String,
        acceptLanguage: String,
        body: JSONObject.() -> Unit
    ): JSONObject {
        val requestBody = Innertube.buildYtmContext(language, region) {
            optJSONObject("context")?.optJSONObject("client")?.remove("visitorData")
            body()
        }
        val request = Innertube.musicPost(endpoint, requestBody, acceptLanguage, cookies = session.cookies)
            .newBuilder()
            .header("X-Goog-AuthUser", session.accountIndex.toString())
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", YtmScopeConfig.ClientContext.CLIENT_VERSION)
            .build()
        return JSONObject(checkNotNull(client.newCall(request).awaitSuccessfulBody()))
    }

    private companion object {
        const val VERIFICATION_LIFETIME_MS = 60_000L
    }
}

internal enum class YouTubeMusicLoginState { SignedIn, SignedOut, Unspecified }

internal fun youtubeMusicLoginState(response: JSONObject): YouTubeMusicLoginState {
    val context = response.optJSONObject("responseContext")
    val loggedOut = context?.optJSONObject("mainAppWebResponseContext")?.opt("loggedOut")
        ?: context?.opt("loggedOut")
    return when (loggedOut) {
        false, "false" -> YouTubeMusicLoginState.SignedIn
        true, "true" -> YouTubeMusicLoginState.SignedOut
        else -> YouTubeMusicLoginState.Unspecified
    }
}

internal fun hasActiveAccount(response: JSONObject): Boolean {
    if (youtubeMusicLoginState(response) == YouTubeMusicLoginState.SignedOut) return false
    val actions = response.optJSONArray("actions") ?: return false
    return (0 until actions.length()).any { index ->
        actions.optJSONObject(index)?.optJSONObject("openPopupAction")?.optJSONObject("popup")
            ?.optJSONObject("multiPageMenuRenderer")?.optJSONObject("header")
            ?.optJSONObject("activeAccountHeaderRenderer") != null
    }
}

internal suspend fun verifyHomeResponse(
    response: JSONObject,
    verifyIfUnspecified: Boolean,
    verifySession: suspend () -> Boolean
) {
    when (youtubeMusicLoginState(response)) {
        YouTubeMusicLoginState.SignedIn -> Unit
        YouTubeMusicLoginState.SignedOut -> throw HomeSessionRejectedException()
        YouTubeMusicLoginState.Unspecified -> {
            if (verifyIfUnspecified && !verifySession()) throw HomeSessionRejectedException()
        }
    }
}
