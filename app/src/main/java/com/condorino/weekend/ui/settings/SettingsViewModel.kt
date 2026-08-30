package com.condorino.weekend.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.condorino.weekend.data.export.PriceExport
import com.condorino.weekend.data.prefs.PreferencesStore
import com.condorino.weekend.data.source.AeroDataBoxConfig
import com.condorino.weekend.data.source.CommercialPriceSource
import com.condorino.weekend.data.source.CondorApiConfig
import com.condorino.weekend.data.reference.AirportReferenceCatalog
import com.condorino.weekend.data.source.FeedConfig
import com.condorino.weekend.data.source.GoogleFlightsApiConfig
import com.condorino.weekend.data.source.OpenSkyConfig
import com.condorino.weekend.data.source.FlightDataSource
import com.condorino.weekend.data.source.SourceStatus
import com.condorino.weekend.data.source.SourceTestResult
import com.condorino.weekend.data.source.TravelRecommendationSource
import com.condorino.weekend.data.source.TripAdvisorApiConfig
import com.condorino.weekend.data.update.UpdateRepository
import com.condorino.weekend.data.update.UpdateUiState
import com.condorino.weekend.domain.model.Airlines
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.ThemeMode
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.repository.StandbyPriceRepository
import com.condorino.weekend.domain.repository.TripRepository
import com.condorino.weekend.domain.model.Destination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class SourceState(
    val id: String,
    val name: String,
    val status: SourceStatus,
)

/**
 * Outcome of the last export/import action, so the standby-price screen can say what happened —
 * writing/reading the actual file is Android-specific (a content:// Uri from the system file
 * picker) and stays in the UI layer; this is just the result of it.
 */
sealed interface PriceIoStatus {
    data object Idle : PriceIoStatus
    data object ExportSucceeded : PriceIoStatus
    data object ExportFailed : PriceIoStatus
    data class ImportSucceeded(val count: Int) : PriceIoStatus
    data object ImportFailed : PriceIoStatus
}

data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences.DEFAULT,
    val feedConfig: FeedConfig = FeedConfig(),
    val condorApiConfig: CondorApiConfig = CondorApiConfig(),
    val allowDemoData: Boolean = true,
    val sources: List<SourceState> = emptyList(),
    val prices: Map<String, StandbyPrice> = emptyMap(),
    val destinations: List<Destination> = emptyList(),
    val openSkyConfig: OpenSkyConfig = OpenSkyConfig(),
    val aeroDataBoxConfig: AeroDataBoxConfig = AeroDataBoxConfig(),
    /** ICAO codes of the Lufthansa Group carriers opted into OpenSky/AeroDataBox searches, beyond
     *  Condor (always searched, not part of this set) — see [Airlines]. */
    val selectedLufthansaGroupCodes: Set<String> = emptySet(),
    /** One RapidAPI key shared by every RapidAPI-hosted source (AeroDataBox, Google Flights,
     *  TripAdvisor, …). */
    val rapidApiKey: String = "",
    val googleFlightsApiConfig: GoogleFlightsApiConfig = GoogleFlightsApiConfig(),
    /** Status of [SettingsViewModel]'s commercial-price source — separate from [sources] because
     *  it isn't a [FlightDataSource] (it prices one trip on demand, not a timetable). */
    val googleFlightsStatus: SourceStatus? = null,
    val tripAdvisorApiConfig: TripAdvisorApiConfig = TripAdvisorApiConfig(),
    /** Status of [SettingsViewModel]'s travel-recommendation source — same reasoning as
     *  [googleFlightsStatus]: it isn't a [FlightDataSource] either. */
    val tripAdvisorStatus: SourceStatus? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** How many airports the bundled public reference covers. */
    val referenceAirportCount: Int = 0,
    /** The whole public reference, so the price screen can search beyond reachable destinations. */
    val allAirports: List<Airport> = emptyList(),
    /** Latest self-test outcome per source id. */
    val sourceTests: Map<String, SourceTestResult> = emptyMap(),
    val testingSourceId: String? = null,
    /** Destination whose price card the prices screen should open expanded, if any. */
    val focusPriceIata: String? = null,
    /** Which airline's fields to open that destination's card on — the operating airline of the
     *  trip the user came from, so "Add standby price" fills in the price that trip will use. */
    val focusPriceAirlineIcao: String? = null,
    val updateState: UpdateUiState = UpdateUiState(),
    val priceIoStatus: PriceIoStatus = PriceIoStatus.Idle,
)

class SettingsViewModel(
    private val preferencesStore: PreferencesStore,
    private val standbyPriceRepository: StandbyPriceRepository,
    private val tripRepository: TripRepository,
    private val sources: List<FlightDataSource>,
    private val commercialPriceSource: CommercialPriceSource,
    private val travelRecommendationSource: TravelRecommendationSource,
    private val airportReferenceCatalog: AirportReferenceCatalog,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Every field lands via MutableStateFlow.update { }, not `_state.value = _state.value.copy(...)`:
        // these collectors all watch the same underlying DataStore Preferences object (one write to
        // any key re-emits all of them), so a plain read-then-write here can lose a concurrent
        // collector's update. update{} retries against whatever _state.value actually is, so none of
        // them can stomp another's change no matter how they interleave.
        viewModelScope.launch {
            preferencesStore.preferences.collectLatest { prefs ->
                _state.update { it.copy(preferences = prefs) }
            }
        }
        viewModelScope.launch {
            preferencesStore.feedConfig.collectLatest { config ->
                _state.update { it.copy(feedConfig = config) }
                refreshSourceStates()
            }
        }
        viewModelScope.launch {
            preferencesStore.condorApiConfig.collectLatest { config ->
                _state.update { it.copy(condorApiConfig = config) }
                refreshSourceStates()
            }
        }
        viewModelScope.launch {
            preferencesStore.allowDemoData.collectLatest { allow ->
                _state.update { it.copy(allowDemoData = allow) }
            }
        }
        viewModelScope.launch {
            standbyPriceRepository.prices.collectLatest { prices ->
                _state.update { it.copy(prices = prices) }
            }
        }
        viewModelScope.launch {
            preferencesStore.openSkyConfig.collectLatest { config ->
                _state.update { it.copy(openSkyConfig = config) }
                refreshSourceStates()
            }
        }
        viewModelScope.launch {
            preferencesStore.aeroDataBoxConfig.collectLatest { config ->
                _state.update { it.copy(aeroDataBoxConfig = config) }
                refreshSourceStates()
            }
        }
        viewModelScope.launch {
            preferencesStore.selectedLufthansaGroupCodes.collectLatest { codes ->
                _state.update { it.copy(selectedLufthansaGroupCodes = codes) }
                // Affects OpenSky's and AeroDataBox's own status/results, same reasoning as the
                // openSkyConfig/aeroDataBoxConfig collectors just above.
                refreshSourceStates()
            }
        }
        viewModelScope.launch {
            preferencesStore.rapidApiKey.collectLatest { key ->
                _state.update { it.copy(rapidApiKey = key) }
                _state.update {
                    it.copy(
                        googleFlightsStatus = commercialPriceSource.status(),
                        tripAdvisorStatus = travelRecommendationSource.status(),
                    )
                }
                // Unlike Google Flights/TripAdvisor, AeroDataBox is a real FlightDataSource and so
                // is already covered by `sources`/refreshSourceStates() — but its status() also
                // depends on this same shared key, so a key change needs to refresh it too.
                refreshSourceStates()
            }
        }
        viewModelScope.launch {
            preferencesStore.googleFlightsApiConfig.collectLatest { config ->
                _state.update { it.copy(googleFlightsApiConfig = config) }
                _state.update { it.copy(googleFlightsStatus = commercialPriceSource.status()) }
            }
        }
        viewModelScope.launch {
            preferencesStore.tripAdvisorApiConfig.collectLatest { config ->
                _state.update { it.copy(tripAdvisorApiConfig = config) }
                _state.update { it.copy(tripAdvisorStatus = travelRecommendationSource.status()) }
            }
        }
        viewModelScope.launch {
            preferencesStore.themeMode.collectLatest { mode ->
                _state.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            val reference = airportReferenceCatalog.airports()
            val destinations = tripRepository.destinations()
            _state.update {
                it.copy(
                    destinations = destinations,
                    referenceAirportCount = reference.size,
                    allAirports = reference.values.sortedBy { airport -> airport.city },
                )
            }
        }
        viewModelScope.launch {
            updateRepository.state.collectLatest { update ->
                _state.update { it.copy(updateState = update) }
            }
        }
        refreshSourceStates()
    }

    private fun refreshSourceStates() {
        viewModelScope.launch {
            val states = sources.map { SourceState(it.id, it.displayName, it.status()) }
            _state.update { it.copy(sources = states) }
        }
    }

    fun updatePreferences(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { preferencesStore.update(transform) }
    }

    fun updateFeedConfig(config: FeedConfig) {
        viewModelScope.launch { preferencesStore.updateFeedConfig(config) }
    }

    fun updateCondorApiConfig(config: CondorApiConfig) {
        viewModelScope.launch { preferencesStore.updateCondorApiConfig(config) }
    }

    fun updateOpenSkyConfig(config: OpenSkyConfig) {
        viewModelScope.launch { preferencesStore.updateOpenSkyConfig(config) }
    }

    fun updateAeroDataBoxConfig(config: AeroDataBoxConfig) {
        viewModelScope.launch { preferencesStore.updateAeroDataBoxConfig(config) }
    }

    fun updateSelectedLufthansaGroupCodes(codes: Set<String>) {
        viewModelScope.launch { preferencesStore.updateSelectedLufthansaGroupCodes(codes) }
    }

    fun updateRapidApiKey(key: String) {
        viewModelScope.launch { preferencesStore.updateRapidApiKey(key) }
    }

    fun updateGoogleFlightsApiConfig(config: GoogleFlightsApiConfig) {
        viewModelScope.launch { preferencesStore.updateGoogleFlightsApiConfig(config) }
    }

    fun updateTripAdvisorApiConfig(config: TripAdvisorApiConfig) {
        viewModelScope.launch { preferencesStore.updateTripAdvisorApiConfig(config) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesStore.setThemeMode(mode) }
    }

    fun setAllowDemoData(allow: Boolean) {
        viewModelScope.launch {
            preferencesStore.setAllowDemoData(allow)
            // Actually clear it out rather than just stopping new demo fetches — otherwise flights
            // cached before the toggle was flipped would keep showing up (demo provenance never
            // silently downgrades to CACHED, by design, so it would never age out on its own).
            if (!allow) tripRepository.purgeDemoData()
        }
    }

    fun focusPrice(iata: String?, airlineCode: String? = null) {
        // Resolved the same way a trip is matched to a price: a flight whose airline the app can't
        // identify is Condor's for pricing purposes, so that is the card it should open on.
        val airlineIcao = airlineCode
            ?.let { Airlines.canonicalIcaoOrNull(it) ?: Airlines.CONDOR.icaoCode }
        _state.update { it.copy(focusPriceIata = iata, focusPriceAirlineIcao = airlineIcao) }
    }

    /**
     * Runs one source's self-test and keeps the result for display. Also handles
     * [commercialPriceSource] and [travelRecommendationSource], neither of which is in [sources]
     * (they aren't [FlightDataSource]s — one prices a trip on demand, the other looks up nearby
     * highlights on demand) but both are tested the same way and share the same result map by id.
     */
    fun testSource(id: String) {
        val flightSource = sources.firstOrNull { it.id == id }
        val runner: suspend () -> SourceTestResult = when {
            flightSource != null -> flightSource::selfTest
            id == commercialPriceSource.id -> commercialPriceSource::selfTest
            id == travelRecommendationSource.id -> travelRecommendationSource::selfTest
            else -> return
        }
        viewModelScope.launch {
            _state.update { it.copy(testingSourceId = id) }
            val result = runCatching { runner() }.getOrElse {
                SourceTestResult.Problem(it.message ?: it::class.simpleName.orEmpty())
            }
            _state.update {
                it.copy(
                    sourceTests = it.sourceTests + (id to result),
                    testingSourceId = null,
                )
            }
            when {
                flightSource != null -> refreshSourceStates()
                id == commercialPriceSource.id ->
                    _state.update { it.copy(googleFlightsStatus = commercialPriceSource.status()) }
                id == travelRecommendationSource.id ->
                    _state.update { it.copy(tripAdvisorStatus = travelRecommendationSource.status()) }
            }
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch { updateRepository.checkNow() }
    }

    fun downloadUpdate() {
        viewModelScope.launch { updateRepository.startDownload() }
    }

    /** Returns false if the special "install unknown apps" access has to be granted first. */
    fun installUpdate(): Boolean = updateRepository.install()

    fun canInstallPackages(): Boolean = updateRepository.canInstallPackages()

    fun openUnknownSourcesSettingsIntent() = updateRepository.openUnknownSourcesSettings()

    fun setUpdateAutoCheckEnabled(enabled: Boolean) {
        viewModelScope.launch { updateRepository.setAutoCheckEnabled(enabled) }
    }

    fun setUpdateWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch { updateRepository.setWifiOnly(wifiOnly) }
    }

    fun clearCache() {
        viewModelScope.launch { tripRepository.clearCache() }
    }

    fun savePrice(price: StandbyPrice) {
        viewModelScope.launch { standbyPriceRepository.save(price) }
    }

    fun deletePrice(iata: String, airlineIcao: String) {
        viewModelScope.launch { standbyPriceRepository.delete(iata, airlineIcao) }
    }

    /** The text to write wherever the user chose to save it. */
    suspend fun buildPricesExportJson(): String =
        PriceExport.write(standbyPriceRepository.current().values, Instant.now().toString())

    fun reportPricesExportResult(success: Boolean) {
        _state.update { it.copy(priceIoStatus = if (success) PriceIoStatus.ExportSucceeded else PriceIoStatus.ExportFailed) }
    }

    /** @param text the file contents read from wherever the user picked it, or null if reading it failed. */
    fun importPrices(text: String?) {
        viewModelScope.launch {
            val imported = text?.let { runCatching { PriceExport.read(it) }.getOrNull() }
            if (imported.isNullOrEmpty()) {
                _state.update { it.copy(priceIoStatus = PriceIoStatus.ImportFailed) }
                return@launch
            }
            imported.forEach { standbyPriceRepository.save(it) }
            _state.update { it.copy(priceIoStatus = PriceIoStatus.ImportSucceeded(imported.size)) }
        }
    }

    fun clearPriceIoStatus() {
        _state.update { it.copy(priceIoStatus = PriceIoStatus.Idle) }
    }

    companion object {
        fun factory(
            preferencesStore: PreferencesStore,
            standbyPriceRepository: StandbyPriceRepository,
            tripRepository: TripRepository,
            sources: List<FlightDataSource>,
            commercialPriceSource: CommercialPriceSource,
            travelRecommendationSource: TravelRecommendationSource,
            airportReferenceCatalog: AirportReferenceCatalog,
            updateRepository: UpdateRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                preferencesStore,
                standbyPriceRepository,
                tripRepository,
                sources,
                commercialPriceSource,
                travelRecommendationSource,
                airportReferenceCatalog,
                updateRepository,
            ) as T
        }
    }
}
