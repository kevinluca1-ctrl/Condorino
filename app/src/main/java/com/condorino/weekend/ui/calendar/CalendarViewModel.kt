package com.condorino.weekend.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.condorino.weekend.domain.repository.DataStatus
import com.condorino.weekend.domain.repository.TripRepository
import com.condorino.weekend.domain.repository.WeekendSearchResult
import com.condorino.weekend.scoring.DestinationPick
import com.condorino.weekend.scoring.DestinationRanking
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Empty-state reasons for the calendar, kept language-free. */
enum class CalendarMessage { NO_WEEKEND_IN_RANGE, NO_CONNECTIONS }

data class CalendarUiState(
    val from: LocalDate = LocalDate.now(),
    val to: LocalDate = LocalDate.now().plusMonths(3),
    val weekends: List<WeekendSearchResult> = emptyList(),
    val isLoading: Boolean = false,
    /** Why the range came back empty, if it did. Rendered by the UI. */
    val message: CalendarMessage? = null,
    /** Same provenance/freshness contract as every other data-bearing screen (spec §4). */
    val status: DataStatus = DataStatus.EMPTY,
) {
    /**
     * The list the "best" section actually shows: one entry per destination, at its own best
     * weekend. See [DestinationRanking] for why ranking weekends themselves says nothing useful
     * when the underlying source is a repeating weekly timetable.
     */
    val bestDestinations: List<DestinationPick>
        get() = DestinationRanking.bestDestinations(weekends)

    /** True when every weekend in the range offers the same thing — see [DestinationRanking]. */
    val weekendsAreInterchangeable: Boolean
        get() = DestinationRanking.weekendsAreInterchangeable(weekends)

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

    /**
     * Cached-first, then a single refresh across the whole range.
     *
     * The refresh is not optional here: the cache only ever holds the weekends the user has
     * already opened, so without it a three-month overview would be empty on first use — and the
     * overview is the whole point of this screen.
     */
    fun search(from: LocalDate, to: LocalDate) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(from = from, to = to, isLoading = true, message = null) }

            val cached = repository.searchRange(from, to)
            _state.update { it.copy(weekends = cached, status = cached.firstOrNull()?.status ?: it.status) }

            val results = repository.refreshRange(from, to)
            _state.update {
                it.copy(
                    weekends = results,
                    isLoading = false,
                    message = messageFor(results),
                    status = results.firstOrNull()?.status ?: it.status,
                )
            }
        }
    }

    private fun messageFor(results: List<WeekendSearchResult>): CalendarMessage? = when {
        results.isEmpty() -> CalendarMessage.NO_WEEKEND_IN_RANGE
        results.all { it.trips.isEmpty() } -> CalendarMessage.NO_CONNECTIONS
        else -> null
    }

    fun setRange(from: LocalDate, to: LocalDate) = search(from, to)

    fun refresh() = search(_state.value.from, _state.value.to)

    companion object {
        fun factory(repository: TripRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CalendarViewModel(repository) as T
            }
    }
}
