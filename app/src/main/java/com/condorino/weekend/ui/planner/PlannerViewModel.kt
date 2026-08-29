package com.condorino.weekend.ui.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.condorino.weekend.data.prefs.PreferencesStore
import com.condorino.weekend.data.source.CommercialPriceResult
import com.condorino.weekend.data.source.CommercialPriceSource
import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.CommercialPriceQuote
import com.condorino.weekend.domain.model.DestinationType
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.WeekendPattern
import com.condorino.weekend.domain.model.WeekendTrip
import com.condorino.weekend.domain.repository.DataStatus
import com.condorino.weekend.domain.repository.FavoriteRepository
import com.condorino.weekend.domain.repository.StandbyPriceRepository
import com.condorino.weekend.domain.repository.TripRepository
import com.condorino.weekend.scoring.RandomDestinationSelector
import com.condorino.weekend.scoring.RandomMode
import com.condorino.weekend.scoring.RejectionReason
import com.condorino.weekend.scoring.WeekendCalendar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Filters applied on top of the already-scored candidate list (spec §13). */
data class TripFilters(
    val patterns: Set<WeekendPattern> = WeekendPattern.entries.toSet(),
    val cabins: Set<Cabin> = Cabin.entries.toSet(),
    val maxPriceCents: Long? = null,
    val minScore: Int = 0,
    val destinationTypes: Set<DestinationType> = DestinationType.entries.toSet(),
    val favoritesOnly: Boolean = false,
) {
    val isActive: Boolean
        get() = patterns.size != WeekendPattern.entries.size ||
            maxPriceCents != null || minScore > 0 || favoritesOnly ||
            destinationTypes.size != DestinationType.entries.size
}

/**
 * On-demand commercial-price lookup state for one trip (keyed by [WeekendTrip.id] in
 * [PlannerUiState.commercialPrices]). Absent from the map entirely means "never asked for" —
 * that's the normal state for almost every trip, since this is only fetched on a tap.
 */
sealed interface CommercialPriceUiState {
    data object Loading : CommercialPriceUiState
    data class Success(val quote: CommercialPriceQuote) : CommercialPriceUiState
    data class NotConfigured(val reason: String, val howToFix: String) : CommercialPriceUiState
    data class Failure(val message: String) : CommercialPriceUiState
}

/** Why a trip list came back empty. Rendered by the UI, see `ui/text/DomainText.kt`. */
sealed interface EmptyReason {
    data object NoFlightData : EmptyReason
    data class Rejected(val reason: RejectionReason) : EmptyReason
    data object NoMatch : EmptyReason
    data object FiltersTooTight : EmptyReason
}

data class PlannerUiState(
    val friday: LocalDate = WeekendCalendar.anchorFriday(LocalDate.now()),
    val allTrips: List<WeekendTrip> = emptyList(),
    val rejections: Map<RejectionReason, Int> = emptyMap(),
    val status: DataStatus = DataStatus.EMPTY,
    val filters: TripFilters = TripFilters(),
    val preferences: UserPreferences = UserPreferences.DEFAULT,
    val prices: Map<String, StandbyPrice> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val surprise: WeekendTrip? = null,
    val surpriseMode: RandomMode = RandomMode.ANY,
    /** True when the last draw found nothing for the selected mode. */
    val surpriseFailed: Boolean = false,
    val compareSelection: List<String> = emptyList(),
    /**
     * The trip the detail screen is showing. Kept here rather than in the navigation route: trip
     * ids contain timestamps and slashes, and round-tripping them through URL encoding is a source
     * of subtle breakage for no benefit.
     */
    val selectedTripId: String? = null,
    /** On-demand commercial-price lookups keyed by trip id — see [CommercialPriceUiState]. */
    val commercialPrices: Map<String, CommercialPriceUiState> = emptyMap(),
) {
    /** Trips after the on-screen filters — this is what every list renders. */
    val trips: List<WeekendTrip>
        get() = allTrips.filter { trip ->
            if (trip.pattern !in filters.patterns) return@filter false
            if (trip.score.total < filters.minScore) return@filter false
            if (filters.favoritesOnly && trip.iata !in favorites) return@filter false

            val maxPrice = filters.maxPriceCents
            if (maxPrice != null) {
                val cheapest = filters.cabins.mapNotNull { trip.priceFor(it)?.cents }.minOrNull()
                if (cheapest == null || cheapest > maxPrice) return@filter false
            }

            val profile = trip.destination.profile
            if (profile != null && filters.destinationTypes.size != DestinationType.entries.size) {
                if (profile.types.none { it in filters.destinationTypes }) return@filter false
            }
            true
        }

    /**
     * Why the list is empty, as a value rather than a sentence — the UI turns it into the
     * reader's language.
     */
    val emptyReason: EmptyReason
        get() = when {
            allTrips.isEmpty() && rejections.isEmpty() -> EmptyReason.NoFlightData
            allTrips.isEmpty() -> {
                val dominant = rejections.keys.maxWithOrNull(
                    compareBy<RejectionReason> { it.informativeness }.thenBy { rejections[it] ?: 0 },
                )
                if (dominant == null) EmptyReason.NoMatch else EmptyReason.Rejected(dominant)
            }
            else -> EmptyReason.FiltersTooTight
        }

    val selectedTrip: WeekendTrip?
        get() = selectedTripId?.let { id -> allTrips.firstOrNull { it.id == id } }

    /**
     * One trip per destination, best first.
     *
     * A destination usually has several candidate trips — one per weekend pattern — and offering
     * all of them made the compare picker list "Budapest" three times over. Comparing is a question
     * about destinations, so each one appears once, represented by its best-scoring trip.
     */
    val comparableTrips: List<WeekendTrip>
        get() = trips
            .groupBy { it.iata }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.score.total } }
            .sortedByDescending { it.score.total }

    val comparedTrips: List<WeekendTrip>
        get() = compareSelection.mapNotNull { iata -> comparableTrips.firstOrNull { it.iata == iata } }
}

/** Returns this trip unchanged if its favourite flag already matches; copies it otherwise. */
private fun WeekendTrip.withFavoriteFlag(isFavorite: Boolean): WeekendTrip =
    if (destination.isFavorite == isFavorite) this
    else copy(destination = destination.copy(isFavorite = isFavorite))

class PlannerViewModel(
    private val repository: TripRepository,
    private val preferencesStore: PreferencesStore,
    private val standbyPriceRepository: StandbyPriceRepository,
    private val favoriteRepository: FavoriteRepository,
    private val commercialPriceSource: CommercialPriceSource,
    private val randomSelector: RandomDestinationSelector = RandomDestinationSelector(),
) : ViewModel() {

    private val _state = MutableStateFlow(PlannerUiState())
    val state: StateFlow<PlannerUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeSideChannels()
        // Cached-first: show whatever we have immediately, then refresh in the background.
        load(_state.value.friday, refresh = true)
    }

    private fun observeSideChannels() {
        viewModelScope.launch {
            repository.dataStatus.collectLatest { status ->
                _state.update { it.copy(status = status) }
            }
        }
        viewModelScope.launch {
            preferencesStore.preferences.collectLatest { prefs ->
                val previous = _state.value.preferences
                _state.update { current ->
                    current.copy(
                        preferences = prefs,
                        filters = current.filters.copy(
                            patterns = prefs.enabledPatterns,
                            destinationTypes = prefs.enabledDestinationTypes,
                        ),
                    )
                }
                // Scoring depends on preferences, so a settings change re-runs the search.
                if (previous != prefs) load(_state.value.friday, refresh = false)
            }
        }
        viewModelScope.launch {
            standbyPriceRepository.prices.collectLatest { prices ->
                val changed = prices != _state.value.prices
                _state.update { it.copy(prices = prices) }
                if (changed) load(_state.value.friday, refresh = false)
            }
        }
        viewModelScope.launch {
            favoriteRepository.favorites.collectLatest { favs ->
                // Every screen reads a trip's heart state off `trip.destination.isFavorite`, baked
                // in once when TripBuilder assembled the list. Toggling a favourite must not wait
                // for the next full reload to show — the already-held trips are corrected in place.
                _state.update { current ->
                    current.copy(
                        favorites = favs,
                        allTrips = current.allTrips.map { trip -> trip.withFavoriteFlag(trip.iata in favs) },
                        surprise = current.surprise?.let { trip -> trip.withFavoriteFlag(trip.iata in favs) },
                    )
                }
            }
        }
    }

    fun load(friday: LocalDate, refresh: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(friday = friday, isLoading = true) }
            val cached = repository.searchWeekend(friday)
            _state.update { it.copy(
                allTrips = cached.trips,
                rejections = cached.rejections,
                status = cached.status,
                isLoading = refresh,
            ) }
            if (refresh) {
                val fresh = repository.refresh(friday)
                _state.update { it.copy(
                    allTrips = fresh.trips,
                    rejections = fresh.rejections,
                    status = fresh.status,
                    isLoading = false,
                ) }
            }
        }
    }

    fun refresh() = load(_state.value.friday, refresh = true)

    fun nextWeekend() = load(WeekendCalendar.nextFriday(_state.value.friday), refresh = true)

    fun previousWeekend() = load(WeekendCalendar.previousFriday(_state.value.friday), refresh = true)

    fun selectFriday(friday: LocalDate) = load(WeekendCalendar.anchorFriday(friday), refresh = true)

    fun updateFilters(transform: (TripFilters) -> TripFilters) {
        _state.update { it.copy(filters = transform(it.filters)) }
    }

    fun selectTrip(id: String) {
        _state.update { it.copy(selectedTripId = id) }
    }

    fun toggleFavorite(iata: String) {
        viewModelScope.launch { favoriteRepository.toggle(iata) }
    }

    fun savePrice(price: StandbyPrice) {
        viewModelScope.launch { standbyPriceRepository.save(price) }
    }

    // ---------------------------------------------------------------- commercial price

    /**
     * Fetches what a real ticket for this exact trip would cost today, on demand — never called
     * automatically (see [CommercialPriceSource] doc). Re-tapping while a lookup is already in
     * flight for this trip is a no-op rather than firing a second request.
     */
    fun checkCommercialPrice(trip: WeekendTrip) {
        val tripId = trip.id
        if (_state.value.commercialPrices[tripId] is CommercialPriceUiState.Loading) return
        _state.update { it.copy(commercialPrices = it.commercialPrices + (tripId to CommercialPriceUiState.Loading)) }
        viewModelScope.launch {
            val result = commercialPriceSource.quote(
                origin = trip.outbound.origin,
                destination = trip.outbound.destination,
                outboundDate = trip.outbound.departureDateLocal,
                returnDate = trip.inbound.departureDateLocal,
                cabin = _state.value.preferences.preferredCabin,
            )
            val next = when (result) {
                is CommercialPriceResult.Success -> CommercialPriceUiState.Success(result.quote)
                is CommercialPriceResult.NotConfigured -> CommercialPriceUiState.NotConfigured(result.reason, result.howToFix)
                is CommercialPriceResult.Failure -> CommercialPriceUiState.Failure(
                    result.userMessage + (result.technicalDetail?.let { " ($it)" } ?: ""),
                )
            }
            _state.update { it.copy(commercialPrices = it.commercialPrices + (tripId to next)) }
        }
    }

    // ---------------------------------------------------------------- surprise me

    fun setSurpriseMode(mode: RandomMode) {
        _state.update { it.copy(surpriseMode = mode) }
    }

    fun surpriseMe() {
        _state.update { current ->
            val pick = randomSelector.pick(current.trips, current.surpriseMode, current.preferences)
            current.copy(surprise = pick, surpriseFailed = pick == null)
        }
    }

    // ---------------------------------------------------------------- compare

    fun toggleCompare(iata: String) {
        val current = _state.value.compareSelection
        val next = when {
            iata in current -> current - iata
            current.size >= MAX_COMPARE -> current.drop(1) + iata
            else -> current + iata
        }
        _state.update { it.copy(compareSelection = next) }
    }

    fun clearCompare() {
        _state.update { it.copy(compareSelection = emptyList()) }
    }

    companion object {
        const val MAX_COMPARE = 6

        fun factory(
            repository: TripRepository,
            preferencesStore: PreferencesStore,
            standbyPriceRepository: StandbyPriceRepository,
            favoriteRepository: FavoriteRepository,
            commercialPriceSource: CommercialPriceSource,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PlannerViewModel(
                    repository,
                    preferencesStore,
                    standbyPriceRepository,
                    favoriteRepository,
                    commercialPriceSource,
                ) as T
        }
    }
}
