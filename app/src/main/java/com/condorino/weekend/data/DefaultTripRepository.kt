package com.condorino.weekend.data

import com.condorino.weekend.data.local.AirportDao
import com.condorino.weekend.data.local.FlightDao
import com.condorino.weekend.data.local.RefreshStateDao
import com.condorino.weekend.data.local.RefreshStateEntity
import com.condorino.weekend.data.mapper.toDomain
import com.condorino.weekend.data.mapper.toEntity
import com.condorino.weekend.data.prefs.PreferencesStore
import com.condorino.weekend.R
import com.condorino.weekend.data.reference.AirportReferenceCatalog
import com.condorino.weekend.data.source.FlightDataSource
import com.condorino.weekend.data.source.FlightSearchQuery
import com.condorino.weekend.data.source.FlightSearchResult
import com.condorino.weekend.data.source.SourceStrings
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.Flight
import com.condorino.weekend.domain.repository.DataStatus
import com.condorino.weekend.domain.repository.FavoriteRepository
import com.condorino.weekend.domain.repository.StandbyPriceRepository
import com.condorino.weekend.domain.repository.TripRepository
import com.condorino.weekend.domain.repository.WeekendSearchResult
import com.condorino.weekend.scoring.TripBuilder
import com.condorino.weekend.scoring.WeekendCalendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Orchestrates: local cache → configured data sources → scoring.
 *
 * Refresh strategy (spec §19): the cache is served immediately, a network refresh is attempted in
 * the background, and the resulting [DataStatus] tells the UI exactly how trustworthy what it is
 * showing is. Nothing here ever upgrades a provenance — a cached row read back from disk becomes
 * [DataProvenance.CACHED], and demo rows stay demo forever.
 */
class DefaultTripRepository(
    /** Ordered by trust: the first source that returns data wins. */
    private val sources: List<FlightDataSource>,
    private val demoSource: FlightDataSource,
    private val flightDao: FlightDao,
    private val airportDao: AirportDao,
    private val refreshStateDao: RefreshStateDao,
    private val preferencesStore: PreferencesStore,
    private val destinationCatalog: DestinationCatalog,
    private val standbyPriceRepository: StandbyPriceRepository,
    private val favoriteRepository: FavoriteRepository,
    private val airportReferenceCatalog: AirportReferenceCatalog,
    private val strings: SourceStrings,
) : TripRepository {

    private val refreshing = MutableStateFlow(false)
    private val refreshMutex = Mutex()

    /** Live data older than this is shown as recently updated rather than "LIVE". */
    private val liveWindow: Duration = Duration.ofMinutes(30)

    override val dataStatus: Flow<DataStatus> =
        combine(refreshStateDao.observe(), refreshing) { state, isRefreshing ->
            val provenance = state?.provenance?.let {
                runCatching { DataProvenance.valueOf(it) }.getOrNull()
            }
            val lastSuccess = state?.lastSuccessEpochMillis?.let(Instant::ofEpochMilli)
            DataStatus(
                provenance = provenance?.let { agedProvenance(it, lastSuccess) },
                sourceLabel = state?.sourceLabel,
                lastSuccess = lastSuccess,
                lastAttempt = state?.lastAttemptEpochMillis?.let(Instant::ofEpochMilli),
                isRefreshing = isRefreshing,
                errorMessage = state?.lastErrorMessage,
            )
        }

    /** LIVE decays into RECENTLY_UPDATED once it leaves the freshness window. */
    private fun agedProvenance(provenance: DataProvenance, lastSuccess: Instant?): DataProvenance {
        if (provenance != DataProvenance.LIVE) return provenance
        val age = lastSuccess?.let { Duration.between(it, Instant.now()) } ?: return provenance
        return if (age > liveWindow) DataProvenance.RECENTLY_UPDATED else DataProvenance.LIVE
    }

    // ------------------------------------------------------------------ search

    override suspend fun searchWeekend(friday: LocalDate): WeekendSearchResult {
        val window = WeekendCalendar.searchWindow(friday)
        val flights = cachedFlights(window.start, window.endInclusive)
        return score(friday, flights, dataStatusSnapshot())
    }

    override suspend fun refresh(friday: LocalDate): WeekendSearchResult {
        val window = WeekendCalendar.searchWindow(friday)
        val status = fetchIntoCache(window.start, window.endInclusive)
        val flights = cachedFlights(window.start, window.endInclusive)
        return score(friday, flights, status)
    }

    override suspend fun searchRange(from: LocalDate, to: LocalDate): List<WeekendSearchResult> =
        scoreRange(from, to, dataStatusSnapshot())

    override suspend fun refreshRange(from: LocalDate, to: LocalDate): List<WeekendSearchResult> {
        // A single query for the whole range rather than one per weekend: every source takes a
        // date range, so a three-month overview costs one request, not thirteen.
        val status = fetchIntoCache(from.minusDays(1), to.plusDays(3))
        return scoreRange(from, to, status)
    }

    private suspend fun scoreRange(
        from: LocalDate,
        to: LocalDate,
        status: DataStatus,
    ): List<WeekendSearchResult> {
        val fridays = WeekendCalendar.fridaysBetween(from, to)
        if (fridays.isEmpty()) return emptyList()
        // One cache read for the whole range, then score each weekend from it.
        val flights = cachedFlights(from.minusDays(1), to.plusDays(3))
        return fridays.map { friday ->
            val window = WeekendCalendar.searchWindow(friday)
            val slice = flights.filter {
                val d = it.departureDateLocal
                !d.isBefore(window.start) && !d.isAfter(window.endInclusive)
            }
            score(friday, slice, status)
        }
    }

    override suspend fun destinations(): List<Destination> {
        val airports = airportCatalog()
        val profiles = destinationCatalog.profiles()
        val favorites = favoriteRepository.favorites.first()
        return airports.values
            .filter { it.iata != Airport.HOME_IATA }
            .map { airport ->
                Destination(
                    airport = airport,
                    profile = profiles[airport.iata],
                    isFavorite = airport.iata in favorites,
                )
            }
            .sortedBy { it.airport.city }
    }

    override suspend fun clearCache() {
        flightDao.clearAll()
    }

    // ------------------------------------------------------------------ internals

    private suspend fun score(
        friday: LocalDate,
        flights: List<Flight>,
        status: DataStatus,
    ): WeekendSearchResult {
        val prefs = preferencesStore.currentPreferences()
        val profiles = destinationCatalog.profiles()
        val favorites = favoriteRepository.favorites.first()
        val prices = standbyPriceRepository.current()

        // Destinations are *derived from the flight data*, never from a hard-coded list.
        val destinations = flights
            .filter { it.origin.iata == Airport.HOME_IATA }
            .groupBy { it.destination.iata }
            .mapValues { (iata, legs) ->
                Destination(
                    airport = legs.first().destination,
                    profile = profiles[iata],
                    servedDays = legs.map { it.departureDateLocal.dayOfWeek }.toSet(),
                    isFavorite = iata in favorites,
                )
            }

        val result = TripBuilder(prefs).build(
            flights = flights,
            weekendFriday = friday,
            destinations = destinations,
            prices = prices,
        )

        return WeekendSearchResult(
            friday = friday,
            trips = result.trips,
            rejections = result.rejections,
            status = status,
        )
    }

    private suspend fun cachedFlights(from: LocalDate, to: LocalDate): List<Flight> {
        val airports = airportCatalog()
        return flightDao.inRange(from.toString(), to.toString())
            .mapNotNull { it.toDomain(airports, downgradeToCached = !isFresh(it.retrievedAtEpochMillis)) }
    }

    private fun isFresh(retrievedAtEpochMillis: Long): Boolean =
        Duration.between(Instant.ofEpochMilli(retrievedAtEpochMillis), Instant.now()) <= liveWindow

    /**
     * Airports known to the app: the bundled public reference, overlaid with whatever a source
     * declared for itself (a feed's own spelling of a name wins), plus FRA as a hard guarantee.
     */
    private suspend fun airportCatalog(): Map<String, Airport> {
        val stored = airportDao.all().associate { it.iata to it.toDomain() }
        return airportReferenceCatalog.airports() + stored +
            (Airport.HOME_IATA to Airport.FRANKFURT)
    }

    /**
     * Tries each configured source in trust order. The bundled demo source is only used when no
     * real source produced data *and* the user has not disabled demo data.
     */
    private suspend fun fetchIntoCache(from: LocalDate, to: LocalDate): DataStatus =
        refreshMutex.withLock {
            refreshing.value = true
            val attemptAt = Instant.now()
            val problems = mutableListOf<String>()
            var hint: String? = null

            try {
                val query = FlightSearchQuery(originIata = Airport.HOME_IATA, from = from, to = to)

                for (source in sources) {
                    when (val result = source.search(query)) {
                        is FlightSearchResult.Success -> {
                            if (result.flights.isEmpty()) {
                                problems += strings.get(R.string.repo_no_flights, source.displayName)
                                continue
                            }
                            persist(source.id, result)
                            val state = RefreshStateEntity(
                                lastSuccessEpochMillis = result.retrievedAt.toEpochMilli(),
                                lastAttemptEpochMillis = attemptAt.toEpochMilli(),
                                sourceId = source.id,
                                sourceLabel = result.note ?: source.displayName,
                                provenance = result.provenance.name,
                                lastErrorMessage = null,
                            )
                            refreshStateDao.upsert(state)
                            return@withLock DataStatus(
                                provenance = result.provenance,
                                sourceLabel = result.note ?: source.displayName,
                                lastSuccess = result.retrievedAt,
                                lastAttempt = attemptAt,
                            )
                        }
                        is FlightSearchResult.NotConfigured -> {
                            if (hint == null) hint = "${result.reason} ${result.howToFix}"
                        }
                        is FlightSearchResult.Failure -> problems += "${source.displayName}: ${result.userMessage}"
                    }
                }

                // No real source delivered — fall back to bundled demo data if allowed.
                val demoAllowed = preferencesStore.allowDemoData.first()
                if (demoAllowed) {
                    val demo = demoSource.search(query)
                    if (demo is FlightSearchResult.Success && demo.flights.isNotEmpty()) {
                        persist(demoSource.id, demo)
                        refreshStateDao.upsert(
                            RefreshStateEntity(
                                lastSuccessEpochMillis = demo.retrievedAt.toEpochMilli(),
                                lastAttemptEpochMillis = attemptAt.toEpochMilli(),
                                sourceId = demoSource.id,
                                sourceLabel = demoSource.displayName,
                                provenance = DataProvenance.DEMO.name,
                                lastErrorMessage = problems.firstOrNull(),
                            ),
                        )
                        return@withLock DataStatus(
                            provenance = DataProvenance.DEMO,
                            sourceLabel = demoSource.displayName,
                            lastSuccess = demo.retrievedAt,
                            lastAttempt = attemptAt,
                            errorMessage = problems.firstOrNull(),
                            notConfiguredHint = hint,
                        )
                    }
                }

                val previous = refreshStateDao.get()
                val message = problems.firstOrNull()
                    ?: hint
                    ?: strings.get(R.string.repo_no_source)
                refreshStateDao.upsert(
                    RefreshStateEntity(
                        lastSuccessEpochMillis = previous?.lastSuccessEpochMillis,
                        lastAttemptEpochMillis = attemptAt.toEpochMilli(),
                        sourceId = previous?.sourceId,
                        sourceLabel = previous?.sourceLabel,
                        provenance = previous?.provenance,
                        lastErrorMessage = message,
                    ),
                )
                DataStatus(
                    provenance = previous?.provenance?.let {
                        runCatching { DataProvenance.valueOf(it) }.getOrNull()
                    },
                    sourceLabel = previous?.sourceLabel,
                    lastSuccess = previous?.lastSuccessEpochMillis?.let(Instant::ofEpochMilli),
                    lastAttempt = attemptAt,
                    errorMessage = message,
                    notConfiguredHint = hint,
                    isOffline = problems.any { it.contains("offline", ignoreCase = true) },
                )
            } finally {
                refreshing.value = false
            }
        }

    private suspend fun persist(sourceId: String, result: FlightSearchResult.Success) {
        val airports = result.flights
            .flatMap { listOf(it.origin, it.destination) }
            .distinctBy { it.iata }
            .map { it.toEntity() }
        if (airports.isNotEmpty()) airportDao.upsertAll(airports)
        flightDao.upsertAll(result.flights.map { it.toEntity(sourceId) })
        // Housekeeping: drop anything that departed more than a week ago.
        flightDao.purgeOlderThan(Instant.now().minus(Duration.ofDays(7)).toEpochMilli())
    }

    private suspend fun dataStatusSnapshot(): DataStatus = dataStatus.first()
}
