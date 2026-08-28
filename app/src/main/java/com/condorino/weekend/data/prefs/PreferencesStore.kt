package com.condorino.weekend.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.condorino.weekend.data.source.CondorApiConfig
import com.condorino.weekend.data.source.FeedConfig
import com.condorino.weekend.data.source.OpenSkyConfig
import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.DestinationType
import com.condorino.weekend.domain.model.ScoreWeights
import com.condorino.weekend.domain.model.ThemeMode
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.WeekendPattern
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "condorino_settings")

/**
 * All persisted settings. Deliberately holds *no* credentials for MyID Travel or any booking
 * account (spec §26) — only the flight-feed API key the user chooses to enter for their own
 * data source, plus their scoring preferences.
 */
class PreferencesStore(private val context: Context) {

    private object Keys {
        val homeCity = stringPreferencesKey("home_city")
        val workEndMinutes = intPreferencesKey("work_end_minutes")
        val homeToAirport = intPreferencesKey("home_to_airport_minutes")
        val airportBuffer = intPreferencesKey("airport_buffer_minutes")
        val returnAirportBuffer = intPreferencesKey("return_airport_buffer_minutes")
        val airportToHome = intPreferencesKey("airport_to_home_minutes")
        val maxFlightMinutes = intPreferencesKey("max_flight_minutes")
        val minNights = intPreferencesKey("min_nights")
        val maxNights = intPreferencesKey("max_nights")
        val maxBudgetCents = longPreferencesKey("max_budget_cents")
        val cabin = stringPreferencesKey("preferred_cabin")
        val patterns = stringSetPreferencesKey("enabled_patterns")
        val destinationTypes = stringSetPreferencesKey("enabled_destination_types")
        val minScore = intPreferencesKey("min_score")
        val latestHomeArrivalMinutes = intPreferencesKey("latest_home_arrival_minutes")

        val wFlight = doublePreferencesKey("w_flight_time")
        val wStay = doublePreferencesKey("w_stay")
        val wWeekend = doublePreferencesKey("w_weekend")
        val wLogistics = doublePreferencesKey("w_logistics")
        val wCost = doublePreferencesKey("w_cost")
        val wDestination = doublePreferencesKey("w_destination")

        val feedEnabled = booleanPreferencesKey("feed_enabled")
        val feedUrl = stringPreferencesKey("feed_url")
        val feedHeaderName = stringPreferencesKey("feed_header_name")
        val feedHeaderValue = stringPreferencesKey("feed_header_value")

        val apiEnabled = booleanPreferencesKey("condor_api_enabled")
        val apiBaseUrl = stringPreferencesKey("condor_api_base_url")
        val apiPath = stringPreferencesKey("condor_api_path")
        val apiKeyHeader = stringPreferencesKey("condor_api_key_header")
        val apiKey = stringPreferencesKey("condor_api_key")
        val apiItemsPath = stringPreferencesKey("condor_api_items_path")
        val apiOriginParam = stringPreferencesKey("condor_api_origin_param")
        val apiFromParam = stringPreferencesKey("condor_api_from_param")
        val apiToParam = stringPreferencesKey("condor_api_to_param")
        val apiFieldOrigin = stringPreferencesKey("condor_api_field_origin")
        val apiFieldDestination = stringPreferencesKey("condor_api_field_destination")
        val apiFieldDeparture = stringPreferencesKey("condor_api_field_departure")
        val apiFieldArrival = stringPreferencesKey("condor_api_field_arrival")
        val apiFieldFlightNumber = stringPreferencesKey("condor_api_field_flight_number")

        val allowDemoData = booleanPreferencesKey("allow_demo_data")

        val openSkyEnabled = booleanPreferencesKey("opensky_enabled")
        val openSkyClientId = stringPreferencesKey("opensky_client_id")
        val openSkyClientSecret = stringPreferencesKey("opensky_client_secret")
        val openSkyHomeIcao = stringPreferencesKey("opensky_home_icao")
        val openSkyCallsign = stringPreferencesKey("opensky_callsign_prefix")
        val openSkyLookbackWeeks = intPreferencesKey("opensky_lookback_weeks")

        val themeMode = stringPreferencesKey("theme_mode")
    }

    val preferences: Flow<UserPreferences> = context.dataStore.data.map { p ->
        val defaults = UserPreferences.DEFAULT
        UserPreferences(
            homeCity = p[Keys.homeCity] ?: defaults.homeCity,
            workEndTime = p[Keys.workEndMinutes]?.let { LocalTime.ofSecondOfDay(it * 60L) }
                ?: defaults.workEndTime,
            homeToAirportMinutes = p[Keys.homeToAirport] ?: defaults.homeToAirportMinutes,
            airportBufferMinutes = p[Keys.airportBuffer] ?: defaults.airportBufferMinutes,
            returnAirportBufferMinutes = p[Keys.returnAirportBuffer] ?: defaults.returnAirportBufferMinutes,
            airportToHomeMinutes = p[Keys.airportToHome] ?: defaults.airportToHomeMinutes,
            maxFlightMinutes = p[Keys.maxFlightMinutes] ?: defaults.maxFlightMinutes,
            minNights = p[Keys.minNights] ?: defaults.minNights,
            maxNights = p[Keys.maxNights] ?: defaults.maxNights,
            maxBudgetCents = p[Keys.maxBudgetCents] ?: defaults.maxBudgetCents,
            preferredCabin = p[Keys.cabin]?.let { runCatching { Cabin.valueOf(it) }.getOrNull() }
                ?: defaults.preferredCabin,
            enabledPatterns = p[Keys.patterns]
                ?.mapNotNull { runCatching { WeekendPattern.valueOf(it) }.getOrNull() }
                ?.toSet()
                ?.ifEmpty { defaults.enabledPatterns }
                ?: defaults.enabledPatterns,
            enabledDestinationTypes = p[Keys.destinationTypes]
                ?.mapNotNull { runCatching { DestinationType.valueOf(it) }.getOrNull() }
                ?.toSet()
                ?.ifEmpty { defaults.enabledDestinationTypes }
                ?: defaults.enabledDestinationTypes,
            minScore = p[Keys.minScore] ?: defaults.minScore,
            latestHomeArrival = p[Keys.latestHomeArrivalMinutes]
                ?.let { LocalTime.ofSecondOfDay(it * 60L) } ?: defaults.latestHomeArrival,
            weights = ScoreWeights(
                flightTimeComfort = p[Keys.wFlight] ?: ScoreWeights.DEFAULT.flightTimeComfort,
                stayQuality = p[Keys.wStay] ?: ScoreWeights.DEFAULT.stayQuality,
                weekendCompatibility = p[Keys.wWeekend] ?: ScoreWeights.DEFAULT.weekendCompatibility,
                logistics = p[Keys.wLogistics] ?: ScoreWeights.DEFAULT.logistics,
                cost = p[Keys.wCost] ?: ScoreWeights.DEFAULT.cost,
                destinationQuality = p[Keys.wDestination] ?: ScoreWeights.DEFAULT.destinationQuality,
            ),
        )
    }

    val feedConfig: Flow<FeedConfig> = context.dataStore.data.map { p ->
        FeedConfig(
            enabled = p[Keys.feedEnabled] ?: false,
            url = p[Keys.feedUrl].orEmpty(),
            headerName = p[Keys.feedHeaderName].orEmpty(),
            headerValue = p[Keys.feedHeaderValue].orEmpty(),
        )
    }

    val condorApiConfig: Flow<CondorApiConfig> = context.dataStore.data.map { p ->
        val d = CondorApiConfig()
        CondorApiConfig(
            enabled = p[Keys.apiEnabled] ?: false,
            baseUrl = p[Keys.apiBaseUrl].orEmpty(),
            path = p[Keys.apiPath].orEmpty(),
            apiKeyHeader = p[Keys.apiKeyHeader].orEmpty(),
            apiKey = p[Keys.apiKey].orEmpty(),
            originParam = p[Keys.apiOriginParam] ?: d.originParam,
            fromParam = p[Keys.apiFromParam] ?: d.fromParam,
            toParam = p[Keys.apiToParam] ?: d.toParam,
            itemsPath = p[Keys.apiItemsPath].orEmpty(),
            fieldOrigin = p[Keys.apiFieldOrigin] ?: d.fieldOrigin,
            fieldDestination = p[Keys.apiFieldDestination] ?: d.fieldDestination,
            fieldDeparture = p[Keys.apiFieldDeparture] ?: d.fieldDeparture,
            fieldArrival = p[Keys.apiFieldArrival] ?: d.fieldArrival,
            fieldFlightNumber = p[Keys.apiFieldFlightNumber] ?: d.fieldFlightNumber,
        )
    }

    val openSkyConfig: Flow<OpenSkyConfig> = context.dataStore.data.map { p ->
        val d = OpenSkyConfig()
        OpenSkyConfig(
            enabled = p[Keys.openSkyEnabled] ?: false,
            clientId = p[Keys.openSkyClientId].orEmpty(),
            clientSecret = p[Keys.openSkyClientSecret].orEmpty(),
            homeIcao = p[Keys.openSkyHomeIcao]?.takeIf { it.isNotBlank() } ?: d.homeIcao,
            callsignPrefix = p[Keys.openSkyCallsign]?.takeIf { it.isNotBlank() } ?: d.callsignPrefix,
            lookbackWeeks = p[Keys.openSkyLookbackWeeks] ?: d.lookbackWeeks,
        )
    }

    /** Light / dark / follow the system. */
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    /** Whether the bundled demo data may be used when no real source is configured. */
    val allowDemoData: Flow<Boolean> = context.dataStore.data.map { it[Keys.allowDemoData] ?: true }

    suspend fun currentPreferences(): UserPreferences = preferences.first()

    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        val current = currentPreferences()
        val next = transform(current)
        context.dataStore.edit { p ->
            p[Keys.homeCity] = next.homeCity
            p[Keys.workEndMinutes] = next.workEndTime.toSecondOfDay() / 60
            p[Keys.homeToAirport] = next.homeToAirportMinutes
            p[Keys.airportBuffer] = next.airportBufferMinutes
            p[Keys.returnAirportBuffer] = next.returnAirportBufferMinutes
            p[Keys.airportToHome] = next.airportToHomeMinutes
            p[Keys.maxFlightMinutes] = next.maxFlightMinutes
            p[Keys.minNights] = next.minNights
            p[Keys.maxNights] = next.maxNights
            p[Keys.maxBudgetCents] = next.maxBudgetCents
            p[Keys.cabin] = next.preferredCabin.name
            p[Keys.patterns] = next.enabledPatterns.map { it.name }.toSet()
            p[Keys.destinationTypes] = next.enabledDestinationTypes.map { it.name }.toSet()
            p[Keys.minScore] = next.minScore
            p[Keys.latestHomeArrivalMinutes] = next.latestHomeArrival.toSecondOfDay() / 60
            p[Keys.wFlight] = next.weights.flightTimeComfort
            p[Keys.wStay] = next.weights.stayQuality
            p[Keys.wWeekend] = next.weights.weekendCompatibility
            p[Keys.wLogistics] = next.weights.logistics
            p[Keys.wCost] = next.weights.cost
            p[Keys.wDestination] = next.weights.destinationQuality
        }
    }

    suspend fun updateFeedConfig(config: FeedConfig) {
        context.dataStore.edit { p ->
            p[Keys.feedEnabled] = config.enabled
            p[Keys.feedUrl] = config.url
            p[Keys.feedHeaderName] = config.headerName
            p[Keys.feedHeaderValue] = config.headerValue
        }
    }

    suspend fun updateCondorApiConfig(config: CondorApiConfig) {
        context.dataStore.edit { p ->
            p[Keys.apiEnabled] = config.enabled
            p[Keys.apiBaseUrl] = config.baseUrl
            p[Keys.apiPath] = config.path
            p[Keys.apiKeyHeader] = config.apiKeyHeader
            p[Keys.apiKey] = config.apiKey
            p[Keys.apiOriginParam] = config.originParam
            p[Keys.apiFromParam] = config.fromParam
            p[Keys.apiToParam] = config.toParam
            p[Keys.apiItemsPath] = config.itemsPath
            p[Keys.apiFieldOrigin] = config.fieldOrigin
            p[Keys.apiFieldDestination] = config.fieldDestination
            p[Keys.apiFieldDeparture] = config.fieldDeparture
            p[Keys.apiFieldArrival] = config.fieldArrival
            p[Keys.apiFieldFlightNumber] = config.fieldFlightNumber
        }
    }

    suspend fun updateOpenSkyConfig(config: OpenSkyConfig) {
        context.dataStore.edit { p ->
            p[Keys.openSkyEnabled] = config.enabled
            p[Keys.openSkyClientId] = config.clientId
            p[Keys.openSkyClientSecret] = config.clientSecret
            p[Keys.openSkyHomeIcao] = config.homeIcao
            p[Keys.openSkyCallsign] = config.callsignPrefix
            p[Keys.openSkyLookbackWeeks] = config.lookbackWeeks
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.themeMode] = mode.name }
    }

    suspend fun setAllowDemoData(allow: Boolean) {
        context.dataStore.edit { it[Keys.allowDemoData] = allow }
    }
}
