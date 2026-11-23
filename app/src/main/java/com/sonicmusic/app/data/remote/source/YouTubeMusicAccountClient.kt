package com.sonicmusic.app.data.remote.source

import android.os.SystemClock
import android.util.Log
import com.sonicmusic.app.core.network.HttpStatusException
import com.sonicmusic.app.core.network.awaitResponseBody
import com.sonicmusic.app.domain.repository.HomeSessionRejectedException
import com.sonicmusic.app.core.network.awaitSuccessfulBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener
import org.json.JSONException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeMusicAccountClient @Inject constructor() {
    @Volatile private var lastVerification: Pair<WeakReference<YouTubePlaybackSession>, Long>? = null
    private class PlayerContext(
        val version: String,
        val login: YouTubeMusicLoginState,
        val visitor: String?,
        val pageId: String?,
        val userSessionId: String?,
    )
    private val contextLock = Mutex()
    private var contextSession = WeakReference<YouTubePlaybackSession>(null)
    private var cachedContext: PlayerContext? = null
    private var refreshContextAt = 0L

    // Obtain the Music web client's current identity and visitor data for the selected account.
    // Retain only the fields used by the player request, in memory.
    private suspend fun playerContext(session: YouTubePlaybackSession): PlayerContext? = contextLock.withLock {
        if (contextSession.get() === session && SystemClock.elapsedRealtime() < refreshContextAt) {
            return@withLock cachedContext
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/?authuser=${session.accountIndex}")
            .header("User-Agent", YtmScopeConfig.ClientContext.USER_AGENT)
            .header("Cookie", session.cookies)
            .header("X-Goog-AuthUser", session.accountIndex.toString())
            .build()
        val context = try {
            val page = withTimeoutOrNull(6_000L) { fetchPlayerPage(request) }.orEmpty()
            CONFIG_START.findAll(page).mapNotNull { start ->
                val config = try {
                    JSONTokener(page.substring(start.range.last + 1)).nextValue() as? JSONObject
                } catch (_: JSONException) {
                    null
                } ?: return@mapNotNull null
                val web = config.optJSONObject("INNERTUBE_CONTEXT")?.optJSONObject("client")
                    ?: return@mapNotNull null
                if (web.optString("clientName") != YtmScopeConfig.ClientContext.CLIENT_NAME) return@mapNotNull null
                val version = web.optString("clientVersion").takeIf { CLIENT_VERSION.matches(it) }
                    ?: return@mapNotNull null
                // Some Music pages omit SESSION_INDEX for the primary account.
                val pageIndex = config.optInt("SESSION_INDEX", -1).takeIf { it >= 0 }
                val login = when (config.opt("LOGGED_IN")) {
                    true -> when {
                        (pageIndex ?: 0) == session.accountIndex -> YouTubeMusicLoginState.SignedIn
                        pageIndex == null -> {
                            Log.d(TAG, "Music player web context omitted the account index")
                            YouTubeMusicLoginState.Unspecified
                        }
                        else -> {
                            Log.d(TAG, "Music player web context account mismatch " +
                                "requested=${session.accountIndex}, reported=$pageIndex")
                            YouTubeMusicLoginState.SignedOut
                        }
                    }
                    false -> YouTubeMusicLoginState.SignedOut
                    else -> YouTubeMusicLoginState.Unspecified
                }
                val dataSyncId = config.opt("DATASYNC_ID") as? String
                val primaryId = dataSyncId?.substringBefore("||")
                val secondaryId = dataSyncId?.substringAfter("||", "")?.takeIf(String::isNotEmpty)
                val signedIn = login == YouTubeMusicLoginState.SignedIn
                val pageId = (config.opt("DELEGATED_SESSION_ID") as? String)
                    ?: primaryId?.takeIf { secondaryId != null }
                val userSessionId = (config.opt("USER_SESSION_ID") as? String) ?: secondaryId ?: primaryId
                PlayerContext(
                    version = version,
                    login = login,
                    visitor = (web.opt("visitorData") as? String)?.takeIf { signedIn && it.isNotBlank() },
                    pageId = pageId?.takeIf { signedIn && SESSION_ID.matches(it) },
                    userSessionId = userSessionId?.takeIf { signedIn && SESSION_ID.matches(it) },
                )
            }.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Log.d(TAG, "Music player web context network failure")
            null
        } catch (e: HttpStatusException) {
            Log.d(TAG, "Music player web context HTTP ${e.code}")
            null
        }
        contextSession = WeakReference(session)
        cachedContext = context
        Log.d(TAG, "Music player web context version=${context?.version}, login=${context?.login}")
        refreshContextAt = SystemClock.elapsedRealtime() +
            if (context?.login == YouTubeMusicLoginState.SignedIn) 3_600_000L else 60_000L
        context
    }

    internal suspend fun playbackWebLoginState(session: YouTubePlaybackSession): YouTubeMusicLoginState =
        playerContext(session)?.login ?: YouTubeMusicLoginState.Unspecified

    // Follow only checked same-origin redirects when loading the Music page.
    private suspend fun fetchPlayerPage(initial: Request): String? {
        var request = initial
        repeat(4) {
            val response = client.newCall(request).awaitResponseBody()
            if (response.isSuccessful) return response.body
            if (response.code !in 300..399) throw HttpStatusException(response.code)
            val next = response.location?.let(request.url::resolve)
            if (next == null || !next.isHttps || next.host != initial.url.host ||
                next.port != initial.url.port || next.username.isNotEmpty() || next.password.isNotEmpty()
            ) {
                Log.d(TAG, "Music player web context redirected outside Music")
                return null
            }
            request = request.newBuilder().url(next).build()
        }
        Log.d(TAG, "Music player web context redirect limit reached")
        return null
    }

    // Keep the account cookies out of cross-origin redirects, shared caches, and logging interceptors.
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
        val player = if (endpoint == Innertube.PLAYER_MUSIC) playerContext(session) else null
        val requestBody = Innertube.buildYtmContext(language, region) {
            getJSONObject("context").getJSONObject("client").apply {
                remove("visitorData")
                if (player != null) {
                    put("clientVersion", player.version)
                    player.visitor?.let { put("visitorData", it) }
                }
            }
            body()
        }
        val request = Innertube.musicPost(
            endpoint, requestBody, acceptLanguage, cookies = session.cookies, userSessionId = player?.userSessionId
        )
            .newBuilder()
            .header("X-Goog-AuthUser", session.accountIndex.toString())
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", player?.version ?: YtmScopeConfig.ClientContext.CLIENT_VERSION)
            .apply {
                if (player?.login == YouTubeMusicLoginState.SignedIn) {
                    header("X-Youtube-Bootstrap-Logged-In", "true")
                    player.pageId?.let { header("X-Goog-PageId", it) }
                }
            }
            .build()
        return JSONObject(checkNotNull(client.newCall(request).awaitSuccessfulBody()))
    }

    private companion object {
        const val TAG = "SonicStream"
        val CONFIG_START = Regex("""ytcfg\.set\(\s*(?=\{)""")
        val CLIENT_VERSION = Regex("""[0-9.]{3,40}""")
        val SESSION_ID = Regex("""[A-Za-z0-9_-]{1,80}""")
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
