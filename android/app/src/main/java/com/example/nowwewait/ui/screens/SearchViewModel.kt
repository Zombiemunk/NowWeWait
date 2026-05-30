package com.example.nowwewait.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nowwewait.data.DataRepository
import com.example.nowwewait.data.remote.StopDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: DataRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val uiState: StateFlow<SearchUiState> = _query
        .debounce(300)
        .distinctUntilChanged()
        .flatMapLatest { text ->
            flow {
                if (text.trim().length < 2) {
                    emit(SearchUiState.Idle)
                    return@flow
                }
                emit(SearchUiState.Searching)
                try {
                    val results = repository.searchStops(text)
                    emit(SearchUiState.Success(results))
                } catch (e: Exception) {
                    emit(SearchUiState.Error(e.message ?: "Search failed"))
                }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SearchUiState.Idle
        )

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
    }
}

sealed interface SearchUiState {
    object Idle : SearchUiState
    object Searching : SearchUiState
    data class Success(val results: List<StopDto>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}
