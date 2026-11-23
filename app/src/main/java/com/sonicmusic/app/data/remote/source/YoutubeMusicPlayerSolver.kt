package com.sonicmusic.app.data.remote.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sonicmusic.app.core.network.awaitSuccessfulBody
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Resolves URLs for formats YouTube Music offers to the authenticated player. */
@Singleton
class YoutubeMusicPlayerSolver @Inject constructor(@ApplicationContext private val context: Context) {
    internal data class Solution(
        val signatures: Map<String, String>,
        val throttling: Map<String, String>,
    )

    private data class PlayerScript(val url: String, val source: String, val expiresAt: Long)

    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val lock = Mutex()
    private var script: PlayerScript? = null
    private val main = Handler(Looper.getMainLooper())
    private var runtime: Runtime? = null
    private val solverLibrary by lazy {
        // Pinned yt-dlp/ejs 0.8.0 distribution. License notices are embedded in the assets.
        val lib = readAsset("playback/yt.solver.lib.min.js")
        val core = readAsset("playback/yt.solver.core.js")
        "$lib\nvar meriyah = lib.meriyah, astring = lib.astring;\n$core"
    }

    internal suspend fun solve(videoId: String, signatures: Set<String>, throttling: Set<String>): Solution? {
        if (signatures.isEmpty() && throttling.isEmpty()) return null
        if (!VIDEO_ID.matches(videoId)) return null
        return try {
            withTimeoutOrNull(15_000) {
                lock.withLock {
                    val player = withContext(Dispatchers.IO) { loadPlayer(videoId) } ?: return@withLock null
                    val library = withContext(Dispatchers.IO) { solverLibrary }
                    withContext(Dispatchers.Main.immediate) {
                        val existing = runtime?.takeIf { it.playerUrl == player.url && !it.closed }
                        if (existing == null) closeRuntime()
                        val current = existing ?: Runtime(player.url).also { runtime = it }
                        main.removeCallbacks(current.expire)
                        try {
                            if (existing == null) current.initialize(library, player.source)
                            current.solve(signatures, throttling)?.also {
                                Log.d(TAG, "Music player resolved signatures=${it.signatures.size}, " +
                                    "throttling=${it.throttling.size}")
                            }
                        } catch (error: Exception) {
                            closeRuntime()
                            throw error
                        } finally {
                            if (!current.closed) main.postDelayed(current.expire, RUNTIME_IDLE_MS)
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.d(TAG, "Music player URL solver failed (${error.javaClass.simpleName})")
            null
        }
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private suspend fun loadPlayer(videoId: String): PlayerScript? {
        val now = SystemClock.elapsedRealtime()
        val cached = script
        if (cached != null && cached.expiresAt > now) return cached
        val watch = fetchPublic("https://music.youtube.com/watch?v=$videoId")
        val path = watch?.let { PLAYER_PATH.find(it)?.value } ?: return null
        val url = "https://www.youtube.com$path"
        val source = if (cached?.url == url) cached.source else fetchPublic(url) ?: return null
        if (source.length > MAX_PLAYER_CHARS) return null
        return PlayerScript(url, source, now + SCRIPT_TTL_MS).also { script = it }
    }

    private suspend fun fetchPublic(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", YtmScopeConfig.ClientContext.USER_AGENT)
            .build()
        return client.newCall(request).awaitSuccessfulBody()
    }

    private fun closeRuntime() {
        runtime?.close()
        runtime = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private inner class Runtime(val playerUrl: String) {
        private val view = WebView(context)
        var closed = false
            private set
        val expire = Runnable { if (runtime === this) closeRuntime() }

        init {
            // The WebView receives public JavaScript and individual URL parameters only.
            // Network, file and content access are disabled and no app bridge is installed.
            view.settings.apply {
                javaScriptEnabled = true
                blockNetworkLoads = true
                allowFileAccess = false
                allowContentAccess = false
            }
        }

        suspend fun initialize(library: String, player: String) {
            val ready = suspendCancellableCoroutine { continuation ->
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun onPageFinished(view: WebView, url: String) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        if (continuation.isActive) continuation.resume(false)
                        close()
                        return true
                    }
                }
                view.loadDataWithBaseURL(null, "<!doctype html><title></title>", "text/html", "UTF-8", null)
            }
            check(ready && !closed)
            evaluate(library)
            check(evaluate("typeof jsc") == "\"function\"")
            evaluate("var sonicPlayerChunks = []; var sonicPrepared;")
            // Stay below Android's binder transaction limit when passing the public script.
            for (start in player.indices step PLAYER_CHUNK_CHARS) {
                val chunk = player.substring(start, minOf(start + PLAYER_CHUNK_CHARS, player.length))
                evaluate("sonicPlayerChunks.push(${JSONObject.quote(chunk)});")
            }
        }

        suspend fun solve(signatures: Set<String>, throttling: Set<String>): Solution? {
            val requests = JSONArray()
                .put(JSONObject().put("type", "sig").put("challenges", JSONArray(signatures)))
                .put(JSONObject().put("type", "n").put("challenges", JSONArray(throttling)))
            val result = evaluate(
                """
                (function() {
                    try {
                        var input = sonicPrepared
                            ? {type: "preprocessed", preprocessed_player: sonicPrepared}
                            : {type: "player", player: sonicPlayerChunks.join(""), output_preprocessed: true};
                        input.requests = $requests;
                        var output = jsc(input);
                        sonicPrepared = output.preprocessed_player || sonicPrepared;
                        if (sonicPrepared) sonicPlayerChunks.length = 0;
                        return output.responses.map(function(result) { return result.data || null; });
                    } catch (error) {
                        return null;
                    }
                })()
                """.trimIndent()
            )
            if (result == null || result == "null") return null
            val responses = JSONArray(result)
            return Solution(readSolutions(responses.optJSONObject(0), signatures),
                readSolutions(responses.optJSONObject(1), throttling))
        }

        private suspend fun evaluate(source: String): String? = suspendCancellableCoroutine { continuation ->
            if (closed) {
                continuation.resume(null)
            } else {
                view.evaluateJavascript(source) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            main.removeCallbacks(expire)
            view.destroy()
        }
    }

    private fun readSolutions(data: JSONObject?, inputs: Set<String>): Map<String, String> = buildMap {
        for (input in inputs) {
            val result = data?.opt(input) as? String ?: continue
            if (URL_PARAMETER.matches(result)) put(input, result)
        }
    }

    private companion object {
        const val TAG = "SonicStream"
        const val SCRIPT_TTL_MS = 15 * 60_000L
        const val RUNTIME_IDLE_MS = 45_000L
        const val MAX_PLAYER_CHARS = 5_000_000
        const val PLAYER_CHUNK_CHARS = 64_000
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val PLAYER_PATH = Regex("""/s/player/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+/[A-Za-z0-9_-]+/base\.js""")
        val URL_PARAMETER = Regex("[A-Za-z0-9_.~=%-]{1,2048}")
    }
}
