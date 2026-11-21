package com.sonicmusic.app.features.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.core.network.HttpStatusException
import com.sonicmusic.app.data.remote.source.AudioStreamExtractor
import com.sonicmusic.app.data.remote.source.YouTubePlaybackSession
import com.sonicmusic.app.data.remote.source.YouTubePlaybackSessionStore
import com.sonicmusic.app.features.library.data.YouTubeMusicSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class YouTubePlaybackViewModel @Inject constructor(
    private val sessionStore: YouTubePlaybackSessionStore,
    private val extractor: AudioStreamExtractor,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val connected = sessionStore.connected
    private val _busy = MutableStateFlow(true)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                sessionStore.snapshot()
            } finally {
                _busy.value = false
            }
        }
    }

    fun connect(cookieHeader: String, accountIndex: String) {
        if (_busy.value) return
        val session = accountIndex.trim().toIntOrNull()?.let { YouTubePlaybackSession.parse(cookieHeader, it) }
        if (session == null) {
            _message.value = "Enter a valid YouTube Music Cookie header and account index."
            return
        }
        runOperation("Couldn’t verify the playback session. Check your connection and try again.") {
            if (extractor.verifyPlaybackSession(session)) {
                sessionStore.save(session)
                YouTubeMusicSyncWorker.accountConnected(context)
                "Session connected for your YouTube Music Home and high-quality playback."
            } else {
                "YouTube Music did not accept this session. Check the cookie and account index."
            }
        }
    }

    fun disconnect() = runOperation("Couldn’t remove the playback session. Try again.") {
        sessionStore.disconnect()
        null
    }

    private fun runOperation(errorMessage: String, operation: suspend () -> String?) {
        if (_busy.value) return
        _busy.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                _message.value = operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                _message.value = if (e.code == 401 || e.code == 403) {
                    "YouTube Music rejected this session. Check the cookie and account index."
                } else {
                    errorMessage
                }
            } catch (_: Exception) {
                _message.value = errorMessage
            } finally {
                _busy.value = false
            }
        }
    }
}
