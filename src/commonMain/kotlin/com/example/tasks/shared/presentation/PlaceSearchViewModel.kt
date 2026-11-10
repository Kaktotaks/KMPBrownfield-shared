package com.example.tasks.shared.presentation

import androidx.lifecycle.viewModelScope
import com.example.tasks.shared.domain.Place
import com.example.tasks.shared.geocoding.Geocoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaceSearchUiState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val isSearching: Boolean = false,
    /** The last search failed (offline / server error). */
    val failed: Boolean = false,
    /** A search finished without results for a query of at least [PlaceSearchViewModel.MIN_QUERY_LENGTH] characters. */
    val noResults: Boolean = false,
)

/**
 * Address search of the Editor's Place row (the accessible alternative to long-press / drag).
 * Debounced 500 ms, starts at 3 characters, only the latest query counts.
 */
class PlaceSearchViewModel(
    private val geocoder: Geocoder,
    private val language: String,
) : TasksViewModel() {

    private val _uiState = MutableStateFlow(PlaceSearchUiState())
    val uiState: StateFlow<PlaceSearchUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    fun onQueryChange(value: String) {
        job?.cancel()
        val query = value.trim()
        if (query.length < MIN_QUERY_LENGTH) {
            _uiState.value = PlaceSearchUiState(query = value)
            return
        }
        _uiState.value = PlaceSearchUiState(query = value, isSearching = true)
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            try {
                val results = geocoder.search(query, language)
                _uiState.value = PlaceSearchUiState(query = value, results = results, noResults = results.isEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = PlaceSearchUiState(query = value, failed = true)
            }
        }
    }

    fun onRetry() = onQueryChange(_uiState.value.query)

    companion object {
        const val MIN_QUERY_LENGTH = 3
        const val DEBOUNCE_MS = 500L
    }
}
