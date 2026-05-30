package com.example.nowwewait.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nowwewait.data.DataRepository
import com.example.nowwewait.data.remote.AlertDto
import com.example.nowwewait.data.remote.ArrivalDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StopDetailViewModel @Inject constructor(
    private val repository: DataRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<StopDetailUiState>(StopDetailUiState.Loading)
    val uiState: StateFlow<StopDetailUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var stopId: String? = null

    val favoritesFlow = repository.favorites

    fun isFavorite(stopId: String): StateFlow<Boolean> {
        return favoritesFlow.map { list -> list.any { it.id == stopId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    }

    fun init(stopId: String) {
        if (this.stopId == stopId) return
        this.stopId = stopId
        startAutoRefresh(stopId)
    }

    fun refresh() {
        stopId?.let { fetchArrivalsAndAlerts(it) }
    }

    fun toggleFavorite(
        stopId: String,
        name: String,
        municipality: String,
        address: String,
        lat: Double,
        lng: Double,
        lines: List<String>
    ) {
        viewModelScope.launch {
            val list = repository.getFavoritesList()
            val isFav = list.any { it.id == stopId }
            if (isFav) {
                repository.removeFavorite(stopId)
            } else {
                val dummyStop = com.example.nowwewait.data.remote.StopDto(
                    id = stopId,
                    name = name,
                    lat = lat,
                    lng = lng,
                    municipality = municipality,
                    address = address,
                    lines = lines
                )
                repository.addFavorite(dummyStop)
            }
        }
    }

    private fun startAutoRefresh(stopId: String) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                fetchArrivalsAndAlerts(stopId)
                delay(20000) // Auto-refresh every 20 seconds
            }
        }
    }

    private fun fetchArrivalsAndAlerts(stopId: String) {
        viewModelScope.launch {
            try {
                // Get detail/metadata
                val detail = repository.getStopDetail(stopId)
                
                // Get arrivals (returns Pair of Arrivals List and cache timestamp if offline)
                val (arrivals, cacheTime) = repository.getStopArrivals(stopId)
                
                // Get alerts
                val alerts = repository.getAlertsForStop(stopId)
                
                _uiState.value = StopDetailUiState.Success(
                    stopId = stopId,
                    stopName = detail.name,
                    municipality = detail.municipality,
                    address = detail.address,
                    lat = detail.lat,
                    lng = detail.lng,
                    lines = detail.lines.map { it.line_code },
                    arrivals = arrivals,
                    alerts = alerts,
                    cacheTime = cacheTime
                )
            } catch (e: Exception) {
                if (_uiState.value is StopDetailUiState.Loading) {
                    _uiState.value = StopDetailUiState.Error(e.message ?: "Unknown error")
                } else {
                    // If already Success, keep Success state but maybe we can update cacheTime
                    val current = _uiState.value
                    if (current is StopDetailUiState.Success) {
                        // Attempt to read cache fallback
                        try {
                            val (arrivals, cacheTime) = repository.getStopArrivals(stopId)
                            _uiState.value = current.copy(arrivals = arrivals, cacheTime = cacheTime)
                        } catch (cacheEx: Exception) {
                            // Do nothing, keep old state
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
    }
}

sealed interface StopDetailUiState {
    object Loading : StopDetailUiState
    data class Error(val message: String) : StopDetailUiState
    data class Success(
        val stopId: String,
        val stopName: String,
        val municipality: String,
        val address: String,
        val lat: Double,
        val lng: Double,
        val lines: List<String>,
        val arrivals: List<ArrivalDto>,
        val alerts: List<AlertDto>,
        val cacheTime: Long
    ) : StopDetailUiState
}
