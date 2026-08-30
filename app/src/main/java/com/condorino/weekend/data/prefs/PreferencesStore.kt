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
import com.condorino.weekend.data.source.AeroDataBoxConfig
import com.condorino.weekend.data.source.CondorApiConfig
import com.condorino.weekend.data.source.FeedConfig
import com.condorino.weekend.data.source.GoogleFlightsApiConfig
import com.condorino.weekend.data.source.OpenSkyConfig
import com.condorino.weekend.data.source.TripAdvisorApiConfig
import com.condorino.weekend.domain.model.Airlines
import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.DestinationType
import com.condorino.weekend.domain.model.ScoreWeights
import com.condorino.weekend.domain.model.ThemeMode
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.WeekendPattern
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
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

        /** ICAO codes of the Lufthansa Group carriers additionally opted in — see
         *  [selectedLufthansaGroupCodes]. Condor itself is always searched and is not stored here. */
        val selectedLufthansaGroupCodes = stringSetPreferencesKey("selected_lufthansa_group_codes")

        val openSkyEnabled = booleanPreferencesKey("opensky_enabled")
        val openSkyClientId = stringPreferencesKey("opensky_client_id")
        val openSkyClientSecret = stringPreferencesKey("opensky_client_secret")
        val openSkyHomeIcao = stringPreferencesKey("opensky_home_icao")
        // opensky_callsign_prefix removed: OpenSky's per-source callsign filter was replaced by
        // the shared selectedLufthansaGroupCodes selection (plus Condor, always on) — see
        // OpenSkyFlightDataSource.
        val openSkyLookbackWeeks = intPreferencesKey("opensky_lookback_weeks")

        val adbEnabled = booleanPreferencesKey("aerodatabox_enabled")
        val adbApiHost = stringPreferencesKey("aerodatabox_api_host")
        val adbHomeIata = stringPreferencesKey("aerodatabox_home_iata")
        val adbWindowHours = intPreferencesKey("aerodatabox_window_hours")
        val adbWithLeg = booleanPreferencesKey("aerodatabox_with_leg")
        val adbWithCancelled = booleanPreferencesKey("aerodatabox_with_cancelled")
        val adbWithCodeshared = booleanPreferencesKey("aerodatabox_with_codeshared")
        val adbWithPrivate = booleanPreferencesKey("aerodatabox_with_private")
        // aerodatabox_airline_icao_filter removed: same reasoning as opensky_callsign_prefix
        // above — replaced by the shared selectedLufthansaGroupCodes selection.
        val adbDeparturesItemsPath = stringPreferencesKey("aerodatabox_departures_items_path")
        val adbArrivalsItemsPath = stringPreferencesKey("aerodatabox_arrivals_items_path")
        val adbFieldDepartureAirportCode = stringPreferencesKey("aerodatabox_field_departure_airport_code")
        val adbFieldArrivalAirportCode = stringPreferencesKey("aerodatabox_field_arrival_airport_code")
        val adbFieldDepartureTimeUtc = stringPreferencesKey("aerodatabox_field_departure_time_utc")
        val adbFieldArrivalTimeUtc = stringPreferencesKey("aerodatabox_field_arrival_time_utc")
        val adbFieldFlightNumber = stringPreferencesKey("aerodatabox_field_flight_number")
        val adbFieldAirlineName = stringPreferencesKey("aerodatabox_field_airline_name")
        val adbFieldAirlineIcao = stringPreferencesKey("aerodatabox_field_airline_icao")

        // Legacy, no longer written: kept only so an already-entered key survives as a fallback
        // for the shared rapidApiKey below, rather than silently discarding it.
        val gfApiKey = stringPreferencesKey("google_flights_api_key")

        val rapidApiKey = stringPreferencesKey("rapid_api_key")

        val gfEnabled = booleanPreferencesKey("google_flights_enabled")
        val gfApiHost = stringPreferencesKey("google_flights_api_host")
        val gfPath = stringPreferencesKey("google_flights_path")
        val gfDepartureIdParam = stringPreferencesKey("google_flights_departure_id_param")
        val gfArrivalIdParam = stringPreferencesKey("google_flights_arrival_id_param")
        val gfOutboundDateParam = stringPreferencesKey("google_flights_outbound_date_param")
        val gfReturnDateParam = stringPreferencesKey("google_flights_return_date_param")
        val gfAdultsParam = stringPreferencesKey("google_flights_adults_param")
        val gfCurrencyParam = stringPreferencesKey("google_flights_currency_param")
        val gfTravelClassParam = stringPreferencesKey("google_flights_travel_class_param")
        val gfTravelClassEconomyValue = stringPreferencesKey("google_flights_travel_class_economy_value")
        val gfTravelClassBusinessValue = stringPreferencesKey("google_flights_travel_class_business_value")
        val gfItemsPath = stringPreferencesKey("google_flights_items_path")
        val gfFieldPrice = stringPreferencesKey("google_flights_field_price")
        val gfFieldAirline = stringPreferencesKey("google_flights_field_airline")
        val gfFieldCarryOnIncluded = stringPreferencesKey("google_flights_field_carry_on_included")
        val gfFieldCarryOnNote = stringPreferencesKey("google_flights_field_carry_on_note")

        val taEnabled = booleanPreferencesKey("tripadvisor_enabled")
        val taApiHost = stringPreferencesKey("tripadvisor_api_host")
        val taLocationSearchPath = stringPreferencesKey("tripadvisor_location_search_path")
        val taLocationQueryParam = stringPreferencesKey("tripadvisor_location_query_param")
        val taLocationItemsPath = stringPreferencesKey("tripadvisor_location_items_path")
        val taLocationIdField = stringPreferencesKey("tripadvisor_location_id_field")
        val taHighlightsPath = stringPreferencesKey("tripadvisor_highlights_path")
        val taHighlightsLocationIdParam = stringPreferencesKey("tripadvisor_highlights_location_id_param")
        val taItemsPath = stringPreferencesKey("tripadvisor_items_path")
        val taFieldName = stringPreferencesKey("tripadvisor_field_name")
        val taFieldRating = stringPreferencesKey("tripadvisor_field_rating")
        val taFieldReviewCount = stringPreferencesKey("tripadvisor_field_review_count")
        val taFieldUrl = stringPreferencesKey("tripadvisor_field_url")
        val taFieldAddress = stringPreferencesKey("tripadvisor_field_address")
        val taFieldCategory = stringPreferencesKey("tripadvisor_field_category")
        val taMaxResults = intPreferencesKey("tripadvisor_max_results")

        val themeMode = stringPreferencesKey("theme_mode")

        val updateAutoCheckEnabled = booleanPreferencesKey("update_auto_check_enabled")
        val updateWifiOnly = booleanPreferencesKey("update_wifi_only")
        val updateLastCheckedAt = longPreferencesKey("update_last_checked_at")
        val updateLastNotifiedTag = stringPreferencesKey("update_last_notified_tag")
        val updatePendingDownloadId = longPreferencesKey("update_pending_download_id")
        val updatePendingDownloadTag = stringPreferencesKey("update_pending_download_tag")
        val updatePendingDownloadApkAssetName = stringPreferencesKey("update_pending_download_apk_asset_name")
        val updateReadyTag = stringPreferencesKey("update_ready_tag")
        val updateReadyApkAssetName = stringPreferencesKey("update_ready_apk_asset_name")
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
            lookbackWeeks = p[Keys.openSkyLookbackWeeks] ?: d.lookbackWeeks,
        )
    }

    val aeroDataBoxConfig: Flow<AeroDataBoxConfig> = context.dataStore.data.map { p ->
        val d = AeroDataBoxConfig()
        AeroDataBoxConfig(
            enabled = p[Keys.adbEnabled] ?: false,
            apiHost = p[Keys.adbApiHost] ?: d.apiHost,
            homeIata = p[Keys.adbHomeIata]?.takeIf { it.isNotBlank() } ?: d.homeIata,
            windowHours = p[Keys.adbWindowHours] ?: d.windowHours,
            withLeg = p[Keys.adbWithLeg] ?: d.withLeg,
            withCancelled = p[Keys.adbWithCancelled] ?: d.withCancelled,
            withCodeshared = p[Keys.adbWithCodeshared] ?: d.withCodeshared,
            withPrivate = p[Keys.adbWithPrivate] ?: d.withPrivate,
            departuresItemsPath = p[Keys.adbDeparturesItemsPath] ?: d.departuresItemsPath,
            arrivalsItemsPath = p[Keys.adbArrivalsItemsPath] ?: d.arrivalsItemsPath,
            fieldDepartureAirportCode = p[Keys.adbFieldDepartureAirportCode] ?: d.fieldDepartureAirportCode,
            fieldArrivalAirportCode = p[Keys.adbFieldArrivalAirportCode] ?: d.fieldArrivalAirportCode,
            fieldDepartureTimeUtc = p[Keys.adbFieldDepartureTimeUtc] ?: d.fieldDepartureTimeUtc,
            fieldArrivalTimeUtc = p[Keys.adbFieldArrivalTimeUtc] ?: d.fieldArrivalTimeUtc,
            fieldFlightNumber = p[Keys.adbFieldFlightNumber] ?: d.fieldFlightNumber,
            fieldAirlineName = p[Keys.adbFieldAirlineName] ?: d.fieldAirlineName,
            fieldAirlineIcao = p[Keys.adbFieldAirlineIcao] ?: d.fieldAirlineIcao,
        )
    }

    /**
     * One RapidAPI key shared by every RapidAPI-hosted source (AeroDataBox, Google Flights,
     * TripAdvisor, and any future one) — matching how RapidAPI itself works: a single
     * account-level key is valid across every API that account has subscribed to, distinguished
     * only by the `X-RapidAPI-Host` header each source already sends. Falls back to whatever was
     * entered in the old, source-specific Google Flights key field, so upgrading doesn't silently
     * drop it.
     */
    val rapidApiKey: Flow<String> = context.dataStore.data.map { p ->
        p[Keys.rapidApiKey]?.takeIf { it.isNotBlank() } ?: p[Keys.gfApiKey].orEmpty()
    }

    val googleFlightsApiConfig: Flow<GoogleFlightsApiConfig> = context.dataStore.data.map { p ->
        val d = GoogleFlightsApiConfig()
        GoogleFlightsApiConfig(
            enabled = p[Keys.gfEnabled] ?: false,
            apiHost = p[Keys.gfApiHost] ?: d.apiHost,
            path = p[Keys.gfPath] ?: d.path,
            departureIdParam = p[Keys.gfDepartureIdParam] ?: d.departureIdParam,
            arrivalIdParam = p[Keys.gfArrivalIdParam] ?: d.arrivalIdParam,
            outboundDateParam = p[Keys.gfOutboundDateParam] ?: d.outboundDateParam,
            returnDateParam = p[Keys.gfReturnDateParam] ?: d.returnDateParam,
            adultsParam = p[Keys.gfAdultsParam] ?: d.adultsParam,
            currencyParam = p[Keys.gfCurrencyParam] ?: d.currencyParam,
            travelClassParam = p[Keys.gfTravelClassParam] ?: d.travelClassParam,
            travelClassEconomyValue = p[Keys.gfTravelClassEconomyValue] ?: d.travelClassEconomyValue,
            travelClassBusinessValue = p[Keys.gfTravelClassBusinessValue] ?: d.travelClassBusinessValue,
            itemsPath = p[Keys.gfItemsPath] ?: d.itemsPath,
            fieldPrice = p[Keys.gfFieldPrice] ?: d.fieldPrice,
            fieldAirline = p[Keys.gfFieldAirline] ?: d.fieldAirline,
            fieldCarryOnIncluded = p[Keys.gfFieldCarryOnIncluded] ?: d.fieldCarryOnIncluded,
            fieldCarryOnNote = p[Keys.gfFieldCarryOnNote] ?: d.fieldCarryOnNote,
        )
    }

    val tripAdvisorApiConfig: Flow<TripAdvisorApiConfig> = context.dataStore.data.map { p ->
        val d = TripAdvisorApiConfig()
        TripAdvisorApiConfig(
            enabled = p[Keys.taEnabled] ?: false,
            // Every field in this config gets persisted verbatim the moment any one of them is
            // touched (e.g. just flipping the "API active" switch), so an alpha-05 install that
            // was ever opened here already has the *old* default host written to disk — a plain
            // `?: d.apiHost` fallback would never see alpha-06's new default for it, since the
            // stored value is never null. Treat that one specific stale string as "never actually
            // customized" so the new default reaches upgrading installs too; a host a user typed
            // in themselves (the new default included) is untouched either way.
            apiHost = p[Keys.taApiHost]?.takeIf { it.isNotBlank() && it != LEGACY_TRIPADVISOR_HOST } ?: d.apiHost,
            locationSearchPath = p[Keys.taLocationSearchPath] ?: d.locationSearchPath,
            locationQueryParam = p[Keys.taLocationQueryParam] ?: d.locationQueryParam,
            locationItemsPath = p[Keys.taLocationItemsPath] ?: d.locationItemsPath,
            locationIdField = p[Keys.taLocationIdField] ?: d.locationIdField,
            highlightsPath = p[Keys.taHighlightsPath] ?: d.highlightsPath,
            highlightsLocationIdParam = p[Keys.taHighlightsLocationIdParam] ?: d.highlightsLocationIdParam,
            itemsPath = p[Keys.taItemsPath] ?: d.itemsPath,
            fieldName = p[Keys.taFieldName] ?: d.fieldName,
            fieldRating = p[Keys.taFieldRating] ?: d.fieldRating,
            fieldReviewCount = p[Keys.taFieldReviewCount] ?: d.fieldReviewCount,
            fieldUrl = p[Keys.taFieldUrl] ?: d.fieldUrl,
            fieldAddress = p[Keys.taFieldAddress] ?: d.fieldAddress,
            fieldCategory = p[Keys.taFieldCategory] ?: d.fieldCategory,
            maxResults = p[Keys.taMaxResults] ?: d.maxResults,
        )
    }

    /** Light / dark / follow the system. */
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    /** Whether the bundled demo data may be used when no real source is configured. */
    val allowDemoData: Flow<Boolean> = context.dataStore.data.map { it[Keys.allowDemoData] ?: true }

    /**
     * ICAO codes of the Lufthansa Group carriers the user has additionally opted into searching,
     * beyond Condor (which is always searched and never appears in this set — see
     * [com.condorino.weekend.domain.model.Airlines]). Defaults to empty: an existing install's
     * results do not change until the user opts a carrier in from Settings → Airlines. Filtered
     * against [Airlines.LUFTHANSA_GROUP] on read so a stored code for a carrier this app no longer
     * recognises (e.g. a future rename) is silently dropped rather than left unmatchable forever.
     */
    val selectedLufthansaGroupCodes: Flow<Set<String>> = context.dataStore.data.map { p ->
        val known = Airlines.LUFTHANSA_GROUP.map { it.icaoCode }.toSet()
        p[Keys.selectedLufthansaGroupCodes]?.filter { it in known }?.toSet() ?: emptySet()
    }

    val updatePrefs: Flow<UpdatePrefs> = context.dataStore.data.map { p ->
        UpdatePrefs(
            autoCheckEnabled = p[Keys.updateAutoCheckEnabled] ?: true,
            wifiOnly = p[Keys.updateWifiOnly] ?: true,
            lastCheckedAt = p[Keys.updateLastCheckedAt]?.let { Instant.ofEpochMilli(it) },
            lastNotifiedTag = p[Keys.updateLastNotifiedTag],
            pendingDownloadId = p[Keys.updatePendingDownloadId],
            pendingDownloadTag = p[Keys.updatePendingDownloadTag],
            pendingDownloadApkAssetName = p[Keys.updatePendingDownloadApkAssetName],
            readyTag = p[Keys.updateReadyTag],
            readyApkAssetName = p[Keys.updateReadyApkAssetName],
        )
    }

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
            p[Keys.openSkyLookbackWeeks] = config.lookbackWeeks
        }
    }

    suspend fun updateAeroDataBoxConfig(config: AeroDataBoxConfig) {
        context.dataStore.edit { p ->
            p[Keys.adbEnabled] = config.enabled
            p[Keys.adbApiHost] = config.apiHost
            p[Keys.adbHomeIata] = config.homeIata
            p[Keys.adbWindowHours] = config.windowHours
            p[Keys.adbWithLeg] = config.withLeg
            p[Keys.adbWithCancelled] = config.withCancelled
            p[Keys.adbWithCodeshared] = config.withCodeshared
            p[Keys.adbWithPrivate] = config.withPrivate
            p[Keys.adbDeparturesItemsPath] = config.departuresItemsPath
            p[Keys.adbArrivalsItemsPath] = config.arrivalsItemsPath
            p[Keys.adbFieldDepartureAirportCode] = config.fieldDepartureAirportCode
            p[Keys.adbFieldArrivalAirportCode] = config.fieldArrivalAirportCode
            p[Keys.adbFieldDepartureTimeUtc] = config.fieldDepartureTimeUtc
            p[Keys.adbFieldArrivalTimeUtc] = config.fieldArrivalTimeUtc
            p[Keys.adbFieldFlightNumber] = config.fieldFlightNumber
            p[Keys.adbFieldAirlineName] = config.fieldAirlineName
            p[Keys.adbFieldAirlineIcao] = config.fieldAirlineIcao
        }
    }

    suspend fun updateRapidApiKey(key: String) {
        context.dataStore.edit { it[Keys.rapidApiKey] = key }
    }

    suspend fun updateGoogleFlightsApiConfig(config: GoogleFlightsApiConfig) {
        context.dataStore.edit { p ->
            p[Keys.gfEnabled] = config.enabled
            p[Keys.gfApiHost] = config.apiHost
            p[Keys.gfPath] = config.path
            p[Keys.gfDepartureIdParam] = config.departureIdParam
            p[Keys.gfArrivalIdParam] = config.arrivalIdParam
            p[Keys.gfOutboundDateParam] = config.outboundDateParam
            p[Keys.gfReturnDateParam] = config.returnDateParam
            p[Keys.gfAdultsParam] = config.adultsParam
            p[Keys.gfCurrencyParam] = config.currencyParam
            p[Keys.gfTravelClassParam] = config.travelClassParam
            p[Keys.gfTravelClassEconomyValue] = config.travelClassEconomyValue
            p[Keys.gfTravelClassBusinessValue] = config.travelClassBusinessValue
            p[Keys.gfItemsPath] = config.itemsPath
            p[Keys.gfFieldPrice] = config.fieldPrice
            p[Keys.gfFieldAirline] = config.fieldAirline
            p[Keys.gfFieldCarryOnIncluded] = config.fieldCarryOnIncluded
            p[Keys.gfFieldCarryOnNote] = config.fieldCarryOnNote
        }
    }

    suspend fun updateTripAdvisorApiConfig(config: TripAdvisorApiConfig) {
        context.dataStore.edit { p ->
            p[Keys.taEnabled] = config.enabled
            p[Keys.taApiHost] = config.apiHost
            p[Keys.taLocationSearchPath] = config.locationSearchPath
            p[Keys.taLocationQueryParam] = config.locationQueryParam
            p[Keys.taLocationItemsPath] = config.locationItemsPath
            p[Keys.taLocationIdField] = config.locationIdField
            p[Keys.taHighlightsPath] = config.highlightsPath
            p[Keys.taHighlightsLocationIdParam] = config.highlightsLocationIdParam
            p[Keys.taItemsPath] = config.itemsPath
            p[Keys.taFieldName] = config.fieldName
            p[Keys.taFieldRating] = config.fieldRating
            p[Keys.taFieldReviewCount] = config.fieldReviewCount
            p[Keys.taFieldUrl] = config.fieldUrl
            p[Keys.taFieldAddress] = config.fieldAddress
            p[Keys.taFieldCategory] = config.fieldCategory
            p[Keys.taMaxResults] = config.maxResults
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.themeMode] = mode.name }
    }

    suspend fun setAllowDemoData(allow: Boolean) {
        context.dataStore.edit { it[Keys.allowDemoData] = allow }
    }

    suspend fun updateSelectedLufthansaGroupCodes(codes: Set<String>) {
        context.dataStore.edit { it[Keys.selectedLufthansaGroupCodes] = codes }
    }

    suspend fun setUpdateAutoCheckEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.updateAutoCheckEnabled] = enabled }
    }

    suspend fun setUpdateWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { it[Keys.updateWifiOnly] = wifiOnly }
    }

    suspend fun recordUpdateCheckedAt(at: Instant) {
        context.dataStore.edit { it[Keys.updateLastCheckedAt] = at.toEpochMilli() }
    }

    suspend fun recordUpdateNotified(tag: String) {
        context.dataStore.edit { it[Keys.updateLastNotifiedTag] = tag }
    }

    /** Persisted so a still-running download survives the app process being killed and restarted. */
    suspend fun recordPendingDownload(id: Long, tag: String, apkAssetName: String) {
        context.dataStore.edit { p ->
            p[Keys.updatePendingDownloadId] = id
            p[Keys.updatePendingDownloadTag] = tag
            p[Keys.updatePendingDownloadApkAssetName] = apkAssetName
            p.remove(Keys.updateReadyTag)
            p.remove(Keys.updateReadyApkAssetName)
        }
    }

    suspend fun recordDownloadReady(tag: String, apkAssetName: String) {
        context.dataStore.edit { p ->
            p.remove(Keys.updatePendingDownloadId)
            p.remove(Keys.updatePendingDownloadTag)
            p.remove(Keys.updatePendingDownloadApkAssetName)
            p[Keys.updateReadyTag] = tag
            p[Keys.updateReadyApkAssetName] = apkAssetName
        }
    }

    suspend fun clearPendingDownload() {
        context.dataStore.edit { p ->
            p.remove(Keys.updatePendingDownloadId)
            p.remove(Keys.updatePendingDownloadTag)
            p.remove(Keys.updatePendingDownloadApkAssetName)
        }
    }

    suspend fun clearReadyDownload() {
        context.dataStore.edit { p ->
            p.remove(Keys.updateReadyTag)
            p.remove(Keys.updateReadyApkAssetName)
        }
    }

    suspend fun currentUpdatePrefs(): UpdatePrefs = updatePrefs.first()

    private companion object {
        /** [TripAdvisorApiConfig.apiHost]'s default before alpha-06 — see the migration note on
         *  [tripAdvisorApiConfig]. */
        const val LEGACY_TRIPADVISOR_HOST = "travel-advisor.p.rapidapi.com"
    }
}

/**
 * Everything the update flow needs to remember across app restarts: whether a download is still in
 * flight (so it can be picked back up rather than silently forgotten) and which tag, if any, is
 * already sitting on disk ready to install.
 */
data class UpdatePrefs(
    val autoCheckEnabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val lastCheckedAt: Instant? = null,
    val lastNotifiedTag: String? = null,
    val pendingDownloadId: Long? = null,
    val pendingDownloadTag: String? = null,
    val pendingDownloadApkAssetName: String? = null,
    val readyTag: String? = null,
    val readyApkAssetName: String? = null,
)
