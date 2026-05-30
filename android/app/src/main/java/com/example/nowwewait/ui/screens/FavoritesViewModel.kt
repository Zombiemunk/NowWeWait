package com.example.nowwewait.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nowwewait.data.DataRepository
import com.example.nowwewait.data.local.FavoriteStopEntity
import com.example.nowwewait.data.remote.ArrivalDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: DataRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<FavoritesUiState>(FavoritesUiState.Loading)
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var currentFavorites: List<FavoriteStopEntity> = emptyList()

    init {
        // 1. Observe Room Favorites flow
        viewModelScope.launch {
            repository.favorites.collect { favs ->
                currentFavorites = favs
                if (favs.isEmpty()) {
                    _uiState.value = FavoritesUiState.Empty
                } else {
                    refreshArrivalsForFavorites(favs)
                    startPeriodicPoll()
                }
            }
        }
    }

    fun forceRefresh() {
        viewModelScope.launch {
            refreshArrivalsForFavorites(currentFavorites)
        }
    }

    fun removeFavorite(stopId: String) {
        viewModelScope.launch {
            repository.removeFavorite(stopId)
        }
    }

    fun reorderFavorites(reorderedList: List<FavoriteStopEntity>) {
        viewModelScope.launch {
            val updated = reorderedList.mapIndexed { index, stop ->
                stop.copy(displayOrder = index)
            }
            repository.updateFavoritesOrder(updated)
        }
    }

    private fun startPeriodicPoll() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(20000) // Poll every 20 seconds
                if (currentFavorites.isNotEmpty()) {
                    refreshArrivalsForFavorites(currentFavorites)
                }
            }
        }
    }

    private suspend fun refreshArrivalsForFavorites(favs: List<FavoriteStopEntity>) {
        if (favs.isEmpty()) return
        
        try {
            // Fetch arrivals in parallel
            val jobs = favs.map { stop ->
                viewModelScope.async {
                    try {
                        val (arrivals, cacheTime) = repository.getStopArrivals(stop.id)
                        FavoriteWithArrivals(
                            stop = stop,
                            arrivals = arrivals,
                            cacheTime = cacheTime
                        )
                    } catch (e: Exception) {
                        FavoriteWithArrivals(
                            stop = stop,
                            arrivals = emptyList(),
                            cacheTime = 0L,
                            error = e.message
                        )
                    }
                }
            }
            val results = jobs.awaitAll()
            _uiState.value = FavoritesUiState.Success(results)
        } catch (e: Exception) {
            // Keep current successful state if we have it, else show error
            if (_uiState.value !is FavoritesUiState.Success) {
                _uiState.value = FavoritesUiState.Error(e.message ?: "Failed to refresh favorites")
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}

data class FavoriteWithArrivals(
    val stop: FavoriteStopEntity,
    val arrivals: List<ArrivalDto>,
    val cacheTime: Long, // 0 if fresh, timestamp if offline cached
    val error: String? = null
)

sealed interface FavoritesUiState {
    object Loading : FavoritesUiState
    object Empty : FavoritesUiState
    data class Success(val favorites: List<FavoriteWithArrivals>) : FavoritesUiState
    data class Error(val message: String) : FavoritesUiState
}
