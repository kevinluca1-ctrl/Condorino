package com.condorino.weekend.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.condorino.weekend.domain.repository.TripRepository
import com.condorino.weekend.domain.repository.WeekendSearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class CalendarUiState(
    val from: LocalDate = LocalDate.now(),
    val to: LocalDate = LocalDate.now().plusMonths(3),
    val weekends: List<WeekendSearchResult> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
) {
    /** Weekends that produced at least one trip, best first — the "Beste Wochenenden" list. */
    val ranked: List<WeekendSearchResult>
        get() = weekends.filter { it.trips.isNotEmpty() }.sortedByDescending { it.topScore }

    val byMonth: Map<String, List<WeekendSearchResult>>
        get() = weekends.groupBy { com.condorino.weekend.core.Formatting.month(it.friday) }
}

/**
 * Drives both the calendar overview and the multi-weekend search (spec §15 / §16): the two are
 * the same computation — score every weekend in a range — rendered two different ways.
 */
class CalendarViewModel(
    private val repository: TripRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        search(_state.value.from, _state.value.to)
    }

    fun search(from: LocalDate, to: LocalDate) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(from = from, to = to, isLoading = true, message = null)
            val results = repository.searchRange(from, to)
            _state.value = _state.value.copy(
                weekends = results,
                isLoading = false,
                message = when {
                    results.isEmpty() ->
                        "Im gewählten Zeitraum liegt kein Wochenende."
                    results.all { it.trips.isEmpty() } ->
                        "Für keines dieser Wochenenden liegen passende Verbindungen vor. " +
                            "Aktualisiere die Daten oder erweitere deine Filter."
                    else -> null
                },
            )
        }
    }

    fun setRange(from: LocalDate, to: LocalDate) = search(from, to)

    companion object {
        fun factory(repository: TripRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CalendarViewModel(repository) as T
            }
    }
}
