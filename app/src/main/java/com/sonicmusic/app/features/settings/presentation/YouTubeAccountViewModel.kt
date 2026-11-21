package com.sonicmusic.app.features.settings.presentation

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.sonicmusic.app.core.network.HttpStatusException
import com.sonicmusic.app.features.settings.data.NoYouTubeChannelException
import com.sonicmusic.app.features.settings.data.YouTubeAccountChangedException
import com.sonicmusic.app.features.settings.data.YouTubeAccountSync
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@HiltViewModel
class YouTubeAccountViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val accountSync: YouTubeAccountSync
) : ViewModel() {
    private val authorization by lazy { Identity.getAuthorizationClient(context) }
    private val _operation = MutableStateFlow(Operation.Idle)
    val operation = _operation.asStateFlow()
    private val _resolution = MutableStateFlow<PendingIntent?>(null)
    val resolution = _resolution.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    val connection = accountSync.connection.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun sync() = requestAccess(Operation.Syncing)

    fun disconnect() = requestAccess(Operation.Disconnecting)

    private fun requestAccess(operation: Operation) {
        if (_operation.value != Operation.Idle) return
        _operation.value = operation
        _message.value = null
        viewModelScope.launch {
            try {
                val channel = accountSync.connection.first()
                val request = AuthorizationRequest.builder()
                    .setRequestedScopes(listOf(Scope(YOUTUBE_READ_ONLY_SCOPE)))
                    .apply {
                        if (channel == null) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
                    }
                    .build()
                val result = authorization.authorize(request).await()
                val pendingIntent = result.pendingIntent
                if (pendingIntent != null && operation == Operation.Syncing) {
                    _resolution.value = pendingIntent
                    return@launch
                }
                if (pendingIntent != null && operation == Operation.Disconnecting) {
                    accountSync.clearPlaylists()
                    finish("Synced copies removed. Review SonicMusic access in your Google Account.")
                    return@launch
                }
                applyAuthorization(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(errorMessage(e))
            }
        }
    }

    fun resolutionLaunched() {
        _resolution.value = null
    }

    fun authorizationResult(resultCode: Int, data: Intent?) {
        if (_operation.value != Operation.Syncing) return
        if (data?.hasExtra(StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true) {
            authorizationLaunchFailed()
            return
        }
        if (data == null && resultCode != Activity.RESULT_OK) {
            finish("Google didn’t finish authorization. Retry and complete the consent screen. If it closes again, check the app’s OAuth setup.")
            return
        }
        viewModelScope.launch {
            try {
                val result = authorization.getAuthorizationResultFromIntent(data)
                applyAuthorization(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(errorMessage(e))
            }
        }
    }

    fun authorizationLaunchFailed() {
        finish("Couldn’t open Google authorization on this device.")
    }

    private suspend fun applyAuthorization(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank() || YOUTUBE_READ_ONLY_SCOPE !in result.grantedScopes) {
            finish("Allow read access to YouTube to connect your account.")
            return
        }
        try {
            val channel = accountSync.connection.first()
            if (_operation.value == Operation.Disconnecting && channel != null) {
                accountSync.disconnect(token, channel.channelId)
                clearCachedToken(token)
                finish("YouTube Music disconnected.")
            } else if (_operation.value == Operation.Syncing) {
                val summary = accountSync.sync(token, channel?.channelId)
                val playlists = if (summary.playlistCount == 1) "1 playlist" else "${summary.playlistCount} playlists"
                val likedTracks = if (summary.likedCount == 1) "1 liked track" else "${summary.likedCount} liked tracks"
                finish("Synced $playlists and $likedTracks to Library → Playlists.")
            } else {
                finish(null)
            }
        } catch (e: HttpStatusException) {
            if (e.code != 401) throw e
            clearCachedToken(token)
            finish("YouTube access expired. Try again to refresh it.")
        }
    }

    private suspend fun clearCachedToken(token: String) {
        authorization.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
    }

    private fun errorMessage(error: Exception): String = when (error) {
        is HttpStatusException -> when (error.code) {
            403 -> "YouTube denied the request. Check the account’s access and YouTube Data API setup."
            429 -> "YouTube is limiting requests. Try again later."
            else -> "YouTube couldn’t complete the request (HTTP ${error.code})."
        }
        is NoYouTubeChannelException -> "The selected Google account has no YouTube channel."
        is YouTubeAccountChangedException -> "Google returned a different YouTube channel from the connected account."
        is ApiException -> {
            // Log only the status code. Provider messages may include account information.
            Log.w("YouTubeAccount", "Google authorization status ${error.statusCode}")
            when (error.statusCode) {
                CommonStatusCodes.DEVELOPER_ERROR ->
                    "Google OAuth setup is required. Register this build’s package and signing SHA-1."
                CommonStatusCodes.CANCELED, CommonStatusCodes.INTERRUPTED ->
                    "Google ended authorization. Complete the consent screen and check the test-user list if access is blocked."
                CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT ->
                    "Check your internet connection and try again."
                else ->
                    "Google authorization failed (code ${error.statusCode}). Check Google Play services and the app’s OAuth setup."
            }
        }
        else -> if (_operation.value == Operation.Disconnecting) {
            "Couldn’t disconnect from YouTube. Check your connection and try again."
        } else {
            "Couldn’t sync from YouTube. Check your connection and try again."
        }
    }

    private fun finish(message: String?) {
        _resolution.value = null
        _message.value = message
        _operation.value = Operation.Idle
    }

    enum class Operation { Idle, Syncing, Disconnecting }

    private companion object {
        const val YOUTUBE_READ_ONLY_SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
