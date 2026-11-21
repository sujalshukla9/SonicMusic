package com.sonicmusic.app.features.charts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.core.network.NetworkMonitor
import com.sonicmusic.app.domain.model.ChartKind
import com.sonicmusic.app.domain.model.ChartPage
import com.sonicmusic.app.domain.model.CountryUtils
import com.sonicmusic.app.domain.repository.ChartsRepository
import com.sonicmusic.app.domain.repository.HomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update

internal enum class ChartIssue { Offline, RequestFailed }

internal data class ChartsUiState(
    val page: ChartPage? = null,
    val region: String = "",
    val loading: Boolean = true,
    val issue: ChartIssue? = null
)

@HiltViewModel
class ChartsViewModel @Inject constructor(
    private val charts: ChartsRepository,
    private val home: HomeRepository,
    private val network: NetworkMonitor
) : ViewModel() {
    private data class LoadRequest(val version: Int = 0, val append: Boolean = false)
    private val requests = MutableStateFlow(LoadRequest())

    fun refresh() = requests.update { LoadRequest(it.version + 1) }

    fun loadMore() = requests.update { LoadRequest(it.version + 1, append = true) }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observe(kind: ChartKind) = flow {
        var lastRequest = 0
        val states = combine(home.scopes, network.isOnlineState, requests, ::Triple)
            .transformLatest { (scope, online, request) ->
                val region = CountryUtils.getCountryName(scope.region)
                val cached = charts.cachedChart(kind, scope)
                if (!online) {
                    emit(ChartsUiState(cached, region, loading = false, issue = ChartIssue.Offline))
                    return@transformLatest
                }
                emit(ChartsUiState(cached, region))
                val requested = request.version != lastRequest
                lastRequest = request.version
                try {
                    val page = if (requested && request.append && kind == ChartKind.Albums) {
                        charts.loadMoreAlbums(scope) ?: charts.refreshChart(kind, scope, force = false)
                    } else {
                        charts.refreshChart(kind, scope, force = requested)
                    }
                    emit(ChartsUiState(page, region, loading = false))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emit(ChartsUiState(cached, region, loading = false, issue = ChartIssue.RequestFailed))
                }
            }
        states.collect { emit(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), ChartsUiState())
}

