package com.example.nowwewait.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nowwewait.data.DataRepository
import com.example.nowwewait.data.remote.StopDto
import com.example.nowwewait.location.LocationTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val repository: DataRepository,
    private val locationTracker: LocationTracker
) : ViewModel() {

    private val _uiState = MutableStateFlow<NearbyUiState>(NearbyUiState.Loading)
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    fun loadNearbyStops(hasPermission: Boolean) {
        viewModelScope.launch {
            if (!hasPermission) {
                // Check if we have a last known location in DataStore
                val lastLoc = repository.lastLocation.first()
                if (lastLoc != null) {
                    fetchNearbyStopsFromCoords(lastLoc.first, lastLoc.second, isLastKnownFallback = true)
                } else {
                    _uiState.value = NearbyUiState.PermissionDenied
                }
                return@launch
            }

            // We have permission, try GPS
            _uiState.value = NearbyUiState.Loading
            try {
                val coords = locationTracker.getCurrentLocation()
                if (coords != null) {
                    // Save last known location
                    repository.saveLastLocation(coords.first, coords.second)
                    fetchNearbyStopsFromCoords(coords.first, coords.second, isLastKnownFallback = false)
                } else {
                    // Fall back to DataStore saved location
                    val lastLoc = repository.lastLocation.first()
                    if (lastLoc != null) {
                        fetchNearbyStopsFromCoords(lastLoc.first, lastLoc.second, isLastKnownFallback = true)
                    } else {
                        _uiState.value = NearbyUiState.Error("GPS lock failed and no last-known location saved.")
                    }
                }
            } catch (e: Exception) {
                _uiState.value = NearbyUiState.Error(e.message ?: "Failed to get location")
            }
        }
    }

    private suspend fun fetchNearbyStopsFromCoords(lat: Double, lng: Double, isLastKnownFallback: Boolean) {
        try {
            val stops = repository.getNearbyStops(lat, lng)
            _uiState.value = NearbyUiState.Success(stops, isLastKnownFallback)
            // Restart 20s auto-refresh
            startAutoRefresh(lat, lng, isLastKnownFallback)
        } catch (e: Exception) {
            _uiState.value = NearbyUiState.Error("Network error: ${e.message}")
        }
    }

    private fun startAutoRefresh(lat: Double, lng: Double, isLastKnownFallback: Boolean) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            var lastFetchLat = lat
            var lastFetchLng = lng
            while (true) {
                delay(20000)
                try {
                    // Re-check current GPS position on every cycle
                    val currentCoords = locationTracker.getCurrentLocation()
                    if (currentCoords != null) {
                        val distanceMoved = haversineMeters(
                            lastFetchLat, lastFetchLng,
                            currentCoords.first, currentCoords.second
                        )
                        if (distanceMoved > 150.0) {
                            // User has moved significantly — reload nearby stops from new position
                            repository.saveLastLocation(currentCoords.first, currentCoords.second)
                            val stops = repository.getNearbyStops(currentCoords.first, currentCoords.second)
                            _uiState.value = NearbyUiState.Success(stops, isLastKnownFallback = false)
                            lastFetchLat = currentCoords.first
                            lastFetchLng = currentCoords.second
                        } else {
                            // Still in the same area — just refresh arrivals for current stops
                            val stops = repository.getNearbyStops(lastFetchLat, lastFetchLng)
                            _uiState.value = NearbyUiState.Success(stops, isLastKnownFallback)
                        }
                    } else {
                        // No GPS fix — refresh arrivals only for current stops
                        val stops = repository.getNearbyStops(lastFetchLat, lastFetchLng)
                        _uiState.value = NearbyUiState.Success(stops, isLastKnownFallback)
                    }
                } catch (e: Exception) {
                    // Keep old success state on failure, don't crash or trigger loading spinner
                }
            }
        }
    }

    /** Haversine formula — returns distance in metres between two lat/lng points. */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0 // Earth radius in metres
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dPhi / 2).let { it * it } +
                Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2).let { it * it }
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
    }
}

sealed interface NearbyUiState {
    object Loading : NearbyUiState
    object PermissionDenied : NearbyUiState
    data class Success(val stops: List<StopDto>, val isLastKnownFallback: Boolean) : NearbyUiState
    data class Error(val message: String) : NearbyUiState
}
