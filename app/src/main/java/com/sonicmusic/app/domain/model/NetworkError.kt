package com.sonicmusic.app.domain.model

sealed class NetworkError {
    data object NoConnection : NetworkError()
    data object RateLimited : NetworkError()
    data object Timeout : NetworkError()
    data object ServerError : NetworkError()
    data class Unknown(val message: String) : NetworkError()
}