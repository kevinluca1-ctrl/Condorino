package com.condorino.weekend.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.condorino.weekend.data.prefs.PreferencesStore
import com.condorino.weekend.data.source.CondorApiConfig
import com.condorino.weekend.data.reference.AirportReferenceCatalog
import com.condorino.weekend.data.source.FeedConfig
import com.condorino.weekend.data.source.OpenSkyConfig
import com.condorino.weekend.data.source.FlightDataSource
import com.condorino.weekend.data.source.SourceStatus
import com.condorino.weekend.data.source.SourceTestResult
import com.condorino.weekend.data.update.UpdateRepository
import com.condorino.weekend.data.update.UpdateUiState
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
import kotlinx.coroutines.launch

data class SourceState(
    val id: String,
    val name: String,
    val status: SourceStatus,
)

data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences.DEFAULT,
    val feedConfig: FeedConfig = FeedConfig(),
    val condorApiConfig: CondorApiConfig = CondorApiConfig(),
    val allowDemoData: Boolean = true,
    val sources: List<SourceState> = emptyList(),
    val prices: Map<String, StandbyPrice> = emptyMap(),
    val destinations: List<Destination> = emptyList(),
    val openSkyConfig: OpenSkyConfig = OpenSkyConfig(),
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
    val updateState: UpdateUiState = UpdateUiState(),
)

class SettingsViewModel(
    private val preferencesStore: PreferencesStore,
    private val standbyPriceRepository: StandbyPriceRepository,
    private val tripRepository: TripRepository,
    private val sources: List<FlightDataSource>,
    private val airportReferenceCatalog: AirportReferenceCatalog,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesStore.preferences.collectLatest { _state.value = _state.value.copy(preferences = it) }
        }
        viewModelScope.launch {
            preferencesStore.feedConfig.collectLatest {
                _state.value = _state.value.copy(feedConfig = it)
                refreshSourceStates()
            }
        }
        viewModelScope.launch {
            preferencesStore.condorApiConfig.collectLatest {
                _state.value = _state.value.copy(condorApiConfig = it)
                refreshSourceStates()
            }
        }
        viewModelScope.launch {
            preferencesStore.allowDemoData.collectLatest { _state.value = _state.value.copy(allowDemoData = it) }
        }
        viewModelScope.launch {
            standbyPriceRepository.prices.collectLatest { _state.value = _state.value.copy(prices = it) }
        }
        viewModelScope.launch {
            preferencesStore.openSkyConfig.collectLatest {
                _state.value = _state.value.copy(openSkyConfig = it)
                refreshSourceStates()
            }
        }
        viewModelScope.launch {
            preferencesStore.themeMode.collectLatest { _state.value = _state.value.copy(themeMode = it) }
        }
        viewModelScope.launch {
            val reference = airportReferenceCatalog.airports()
            _state.value = _state.value.copy(
                destinations = tripRepository.destinations(),
                referenceAirportCount = reference.size,
                allAirports = reference.values.sortedBy { it.city },
            )
        }
        viewModelScope.launch {
            updateRepository.state.collectLatest { _state.value = _state.value.copy(updateState = it) }
        }
        refreshSourceStates()
    }

    private fun refreshSourceStates() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                sources = sources.map { SourceState(it.id, it.displayName, it.status()) },
            )
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

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesStore.setThemeMode(mode) }
    }

    fun setAllowDemoData(allow: Boolean) {
        viewModelScope.launch { preferencesStore.setAllowDemoData(allow) }
    }

    fun focusPrice(iata: String?) {
        _state.value = _state.value.copy(focusPriceIata = iata)
    }

    /** Runs one source's self-test and keeps the result for display. */
    fun testSource(id: String) {
        val source = sources.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(testingSourceId = id)
            val result = runCatching { source.selfTest() }.getOrElse {
                SourceTestResult.Problem(it.message ?: it::class.simpleName.orEmpty())
            }
            _state.value = _state.value.copy(
                sourceTests = _state.value.sourceTests + (id to result),
                testingSourceId = null,
            )
            refreshSourceStates()
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

    fun savePrice(price: StandbyPrice) {
        viewModelScope.launch { standbyPriceRepository.save(price) }
    }

    fun deletePrice(iata: String) {
        viewModelScope.launch { standbyPriceRepository.delete(iata) }
    }

    companion object {
        fun factory(
            preferencesStore: PreferencesStore,
            standbyPriceRepository: StandbyPriceRepository,
            tripRepository: TripRepository,
            sources: List<FlightDataSource>,
            airportReferenceCatalog: AirportReferenceCatalog,
            updateRepository: UpdateRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
                preferencesStore,
                standbyPriceRepository,
                tripRepository,
                sources,
                airportReferenceCatalog,
                updateRepository,
            ) as T
        }
    }
}
