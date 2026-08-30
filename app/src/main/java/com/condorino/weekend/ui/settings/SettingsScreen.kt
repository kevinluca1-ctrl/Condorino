package com.condorino.weekend.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.condorino.weekend.R
import com.condorino.weekend.data.source.SourceStatus
import com.condorino.weekend.data.source.SourceTestResult
import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.DestinationType
import com.condorino.weekend.domain.model.ScoreComponent
import com.condorino.weekend.domain.model.ThemeMode
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.WeekendPattern
import com.condorino.weekend.ui.text.label
import com.condorino.weekend.ui.theme.CondorinoColors
import java.time.LocalTime

/** Matches [com.condorino.weekend.data.source.GoogleFlightsPriceSource.id] — it isn't in
 *  [SettingsUiState.sources], so its self-test result is looked up by this literal instead. */
private const val GOOGLE_FLIGHTS_SOURCE_ID = "google-flights"

/** Matches [com.condorino.weekend.data.source.TripAdvisorRecommendationSource.id] — same reasoning
 *  as [GOOGLE_FLIGHTS_SOURCE_ID]. */
private const val TRIPADVISOR_SOURCE_ID = "tripadvisor"

/** Everything from spec §7 (work times, buffers, limits), §8 (weights) and §3 (data sources). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenPrices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = state.preferences

    Column(
        modifier
            .fillMaxSize()
            .background(CondorinoColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 96.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            color = CondorinoColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1).sp,
        )

        // ---------------------------------------------------------------- updates
        UpdateSection(state, viewModel)

        // ---------------------------------------------------------------- appearance
        SettingsSection(stringResource(R.string.settings_appearance)) {
            Text(
                stringResource(R.string.settings_theme),
                color = CondorinoColors.TextSecondary,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(mode.label(), fontSize = 12.sp) },
                        colors = chipColors(),
                    )
                }
            }
        }

        SettingsSection(
            stringResource(R.string.settings_language),
            stringResource(R.string.settings_language_body),
        ) {
            // Android 13+ exposes a per-app language picker; sending the user straight there is
            // more reliable than duplicating locale switching inside the app.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val context = LocalContext.current
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null)),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.settings_open_language),
                        color = CondorinoColors.Amber,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- data sources
        SettingsSection(
            stringResource(R.string.settings_sources),
            stringResource(R.string.settings_sources_body),
        ) {
            state.sources.forEach { source ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, color = CondorinoColors.TextPrimary, fontSize = 13.sp)
                        Text(
                            when (val s = source.status) {
                                is SourceStatus.Ready -> stringResource(R.string.settings_source_ready)
                                is SourceStatus.NotConfigured -> "${s.reason} ${s.howToFix}"
                                is SourceStatus.Unavailable -> s.reason
                            },
                            color = when (source.status) {
                                is SourceStatus.Ready -> CondorinoColors.Mint
                                else -> CondorinoColors.TextTertiary
                            },
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                        // The self-test is the honest answer to "are my credentials working?":
                        // it calls the real endpoint and repeats what came back, verbatim.
                        state.sourceTests[source.id]?.let { result ->
                            Text(
                                when (result) {
                                    is SourceTestResult.Ok -> result.message
                                    is SourceTestResult.Problem -> result.message
                                },
                                color = when (result) {
                                    is SourceTestResult.Ok -> CondorinoColors.Mint
                                    is SourceTestResult.Problem -> CondorinoColors.Warning
                                },
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { viewModel.testSource(source.id) },
                        enabled = state.testingSourceId == null,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            stringResource(
                                if (state.testingSourceId == source.id) R.string.settings_source_testing
                                else R.string.settings_source_test,
                            ),
                            color = CondorinoColors.Amber,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            SwitchRow(
                label = stringResource(R.string.settings_allow_demo),
                description = stringResource(R.string.settings_allow_demo_body),
                checked = state.allowDemoData,
                onCheckedChange = viewModel::setAllowDemoData,
            )
        }

        SettingsSection(
            stringResource(R.string.settings_feed),
            stringResource(R.string.settings_feed_body),
        ) {
            SwitchRow(
                label = stringResource(R.string.settings_feed_active),
                checked = state.feedConfig.enabled,
                onCheckedChange = { viewModel.updateFeedConfig(state.feedConfig.copy(enabled = it)) },
            )
            TextField(
                label = stringResource(R.string.settings_feed_url),
                value = state.feedConfig.url,
                placeholder = "https://…/condor-feed.json",
                onValueChange = { viewModel.updateFeedConfig(state.feedConfig.copy(url = it.trim())) },
            )
            TextField(
                label = stringResource(R.string.settings_auth_header),
                value = state.feedConfig.headerName,
                placeholder = "X-API-Key",
                onValueChange = { viewModel.updateFeedConfig(state.feedConfig.copy(headerName = it.trim())) },
            )
            PasswordField(
                label = stringResource(R.string.settings_auth_value),
                value = state.feedConfig.headerValue,
                onValueChange = { viewModel.updateFeedConfig(state.feedConfig.copy(headerValue = it.trim())) },
            )
        }

        SettingsSection(stringResource(R.string.settings_reference)) {
            Text(
                stringResource(R.string.settings_reference_body, state.referenceAirportCount),
                color = CondorinoColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }

        SettingsSection(
            stringResource(R.string.settings_opensky),
            stringResource(R.string.settings_opensky_body),
        ) {
            SwitchRow(
                label = stringResource(R.string.settings_opensky_active),
                checked = state.openSkyConfig.enabled,
                onCheckedChange = { viewModel.updateOpenSkyConfig(state.openSkyConfig.copy(enabled = it)) },
            )
            TextField(stringResource(R.string.settings_opensky_home), state.openSkyConfig.homeIcao) {
                viewModel.updateOpenSkyConfig(state.openSkyConfig.copy(homeIcao = it.trim().uppercase()))
            }
            TextField(stringResource(R.string.settings_opensky_callsign), state.openSkyConfig.callsignPrefix) {
                viewModel.updateOpenSkyConfig(state.openSkyConfig.copy(callsignPrefix = it.trim().uppercase()))
            }
            NumberField(
                stringResource(R.string.settings_opensky_lookback),
                state.openSkyConfig.lookbackWeeks.toString(),
                { v ->
                    v.toIntOrNull()?.coerceIn(1, 12)?.let { weeks ->
                        viewModel.updateOpenSkyConfig(state.openSkyConfig.copy(lookbackWeeks = weeks))
                    }
                },
            )
            TextField(stringResource(R.string.settings_opensky_client_id), state.openSkyConfig.clientId) {
                viewModel.updateOpenSkyConfig(state.openSkyConfig.copy(clientId = it.trim()))
            }
            PasswordField(stringResource(R.string.settings_opensky_client_secret), state.openSkyConfig.clientSecret) {
                viewModel.updateOpenSkyConfig(state.openSkyConfig.copy(clientSecret = it.trim()))
            }
            Text(
                stringResource(R.string.settings_opensky_client_hint),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        SettingsSection(
            stringResource(R.string.settings_condor_api),
            stringResource(R.string.settings_condor_api_body),
        ) {
            SwitchRow(
                label = stringResource(R.string.settings_api_active),
                checked = state.condorApiConfig.enabled,
                onCheckedChange = { viewModel.updateCondorApiConfig(state.condorApiConfig.copy(enabled = it)) },
            )
            TextField(stringResource(R.string.settings_api_base_url), state.condorApiConfig.baseUrl, placeholder = "https://api.condor.com") {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(baseUrl = it.trim()))
            }
            TextField(stringResource(R.string.settings_api_path), state.condorApiConfig.path, placeholder = "e.g. /v1/flights") {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(path = it.trim()))
            }
            TextField(stringResource(R.string.settings_api_key_header), state.condorApiConfig.apiKeyHeader, placeholder = "Ocp-Apim-Subscription-Key") {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(apiKeyHeader = it.trim()))
            }
            PasswordField(stringResource(R.string.settings_api_key), state.condorApiConfig.apiKey) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(apiKey = it.trim()))
            }
            TextField(stringResource(R.string.settings_api_items_path), state.condorApiConfig.itemsPath, placeholder = "data.flights") {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(itemsPath = it.trim()))
            }
            Text(
                stringResource(R.string.settings_api_fields),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            TextField(stringResource(R.string.settings_api_field_origin), state.condorApiConfig.fieldOrigin) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(fieldOrigin = it.trim()))
            }
            TextField(stringResource(R.string.settings_api_field_destination), state.condorApiConfig.fieldDestination) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(fieldDestination = it.trim()))
            }
            TextField(stringResource(R.string.settings_api_field_departure), state.condorApiConfig.fieldDeparture) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(fieldDeparture = it.trim()))
            }
            TextField(stringResource(R.string.settings_api_field_arrival), state.condorApiConfig.fieldArrival) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(fieldArrival = it.trim()))
            }
            TextField(stringResource(R.string.settings_api_field_flight_number), state.condorApiConfig.fieldFlightNumber) {
                viewModel.updateCondorApiConfig(state.condorApiConfig.copy(fieldFlightNumber = it.trim()))
            }
        }

        SettingsSection(
            stringResource(R.string.settings_rapidapi),
            stringResource(R.string.settings_rapidapi_body),
        ) {
            PasswordField(stringResource(R.string.settings_rapidapi_key), state.rapidApiKey) {
                viewModel.updateRapidApiKey(it.trim())
            }
        }

        SettingsSection(
            stringResource(R.string.settings_google_flights),
            stringResource(R.string.settings_google_flights_body),
        ) {
            SwitchRow(
                label = stringResource(R.string.settings_api_active),
                checked = state.googleFlightsApiConfig.enabled,
                onCheckedChange = { viewModel.updateGoogleFlightsApiConfig(state.googleFlightsApiConfig.copy(enabled = it)) },
            )
            state.googleFlightsStatus?.let { status ->
                Text(
                    when (status) {
                        is SourceStatus.Ready -> stringResource(R.string.settings_source_ready)
                        is SourceStatus.NotConfigured -> "${status.reason} ${status.howToFix}"
                        is SourceStatus.Unavailable -> status.reason
                    },
                    color = if (status is SourceStatus.Ready) CondorinoColors.Mint else CondorinoColors.TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            state.sourceTests[GOOGLE_FLIGHTS_SOURCE_ID]?.let { result ->
                Text(
                    when (result) {
                        is SourceTestResult.Ok -> result.message
                        is SourceTestResult.Problem -> result.message
                    },
                    color = when (result) {
                        is SourceTestResult.Ok -> CondorinoColors.Mint
                        is SourceTestResult.Problem -> CondorinoColors.Warning
                    },
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            OutlinedButton(
                onClick = { viewModel.testSource(GOOGLE_FLIGHTS_SOURCE_ID) },
                enabled = state.testingSourceId == null,
            ) {
                Text(
                    stringResource(
                        if (state.testingSourceId == GOOGLE_FLIGHTS_SOURCE_ID) R.string.settings_source_testing
                        else R.string.settings_source_test,
                    ),
                    color = CondorinoColors.Amber,
                    fontSize = 12.sp,
                )
            }
            TextField(stringResource(R.string.settings_gf_api_host), state.googleFlightsApiConfig.apiHost) {
                viewModel.updateGoogleFlightsApiConfig(state.googleFlightsApiConfig.copy(apiHost = it.trim()))
            }
            TextField(stringResource(R.string.settings_gf_path), state.googleFlightsApiConfig.path) {
                viewModel.updateGoogleFlightsApiConfig(state.googleFlightsApiConfig.copy(path = it.trim()))
            }
            TextField(stringResource(R.string.settings_gf_items_path), state.googleFlightsApiConfig.itemsPath, placeholder = "data.itineraries.topFlights") {
                viewModel.updateGoogleFlightsApiConfig(state.googleFlightsApiConfig.copy(itemsPath = it.trim()))
            }
            Text(
                stringResource(R.string.settings_api_fields),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            TextField(stringResource(R.string.settings_gf_field_price), state.googleFlightsApiConfig.fieldPrice) {
                viewModel.updateGoogleFlightsApiConfig(state.googleFlightsApiConfig.copy(fieldPrice = it.trim()))
            }
            TextField(stringResource(R.string.settings_gf_field_airline), state.googleFlightsApiConfig.fieldAirline) {
                viewModel.updateGoogleFlightsApiConfig(state.googleFlightsApiConfig.copy(fieldAirline = it.trim()))
            }
            TextField(stringResource(R.string.settings_gf_field_carry_on_included), state.googleFlightsApiConfig.fieldCarryOnIncluded) {
                viewModel.updateGoogleFlightsApiConfig(state.googleFlightsApiConfig.copy(fieldCarryOnIncluded = it.trim()))
            }
            TextField(stringResource(R.string.settings_gf_field_carry_on_note), state.googleFlightsApiConfig.fieldCarryOnNote) {
                viewModel.updateGoogleFlightsApiConfig(state.googleFlightsApiConfig.copy(fieldCarryOnNote = it.trim()))
            }
            Text(
                stringResource(R.string.settings_gf_unverified_hint),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SettingsSection(
            stringResource(R.string.settings_tripadvisor),
            stringResource(R.string.settings_tripadvisor_body),
        ) {
            SwitchRow(
                label = stringResource(R.string.settings_api_active),
                checked = state.tripAdvisorApiConfig.enabled,
                onCheckedChange = { viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(enabled = it)) },
            )
            state.tripAdvisorStatus?.let { status ->
                Text(
                    when (status) {
                        is SourceStatus.Ready -> stringResource(R.string.settings_source_ready)
                        is SourceStatus.NotConfigured -> "${status.reason} ${status.howToFix}"
                        is SourceStatus.Unavailable -> status.reason
                    },
                    color = if (status is SourceStatus.Ready) CondorinoColors.Mint else CondorinoColors.TextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            state.sourceTests[TRIPADVISOR_SOURCE_ID]?.let { result ->
                Text(
                    when (result) {
                        is SourceTestResult.Ok -> result.message
                        is SourceTestResult.Problem -> result.message
                    },
                    color = when (result) {
                        is SourceTestResult.Ok -> CondorinoColors.Mint
                        is SourceTestResult.Problem -> CondorinoColors.Warning
                    },
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            OutlinedButton(
                onClick = { viewModel.testSource(TRIPADVISOR_SOURCE_ID) },
                enabled = state.testingSourceId == null,
            ) {
                Text(
                    stringResource(
                        if (state.testingSourceId == TRIPADVISOR_SOURCE_ID) R.string.settings_source_testing
                        else R.string.settings_source_test,
                    ),
                    color = CondorinoColors.Amber,
                    fontSize = 12.sp,
                )
            }
            TextField(stringResource(R.string.settings_ta_api_host), state.tripAdvisorApiConfig.apiHost) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(apiHost = it.trim()))
            }
            Text(
                stringResource(R.string.settings_ta_location_step),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            TextField(stringResource(R.string.settings_ta_location_search_path), state.tripAdvisorApiConfig.locationSearchPath) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(locationSearchPath = it.trim()))
            }
            TextField(stringResource(R.string.settings_ta_location_query_param), state.tripAdvisorApiConfig.locationQueryParam) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(locationQueryParam = it.trim()))
            }
            TextField(stringResource(R.string.settings_ta_location_items_path), state.tripAdvisorApiConfig.locationItemsPath) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(locationItemsPath = it.trim()))
            }
            TextField(stringResource(R.string.settings_ta_location_id_field), state.tripAdvisorApiConfig.locationIdField) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(locationIdField = it.trim()))
            }
            Text(
                stringResource(R.string.settings_ta_highlights_step),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            TextField(stringResource(R.string.settings_ta_highlights_path), state.tripAdvisorApiConfig.highlightsPath) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(highlightsPath = it.trim()))
            }
            TextField(stringResource(R.string.settings_ta_highlights_location_id_param), state.tripAdvisorApiConfig.highlightsLocationIdParam) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(highlightsLocationIdParam = it.trim()))
            }
            TextField(stringResource(R.string.settings_ta_items_path), state.tripAdvisorApiConfig.itemsPath) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(itemsPath = it.trim()))
            }
            Text(
                stringResource(R.string.settings_api_fields),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            TextField(stringResource(R.string.settings_ta_field_name), state.tripAdvisorApiConfig.fieldName) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(fieldName = it.trim()))
            }
            TextField(stringResource(R.string.settings_ta_field_rating), state.tripAdvisorApiConfig.fieldRating) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(fieldRating = it.trim()))
            }
            TextField(stringResource(R.string.settings_ta_field_review_count), state.tripAdvisorApiConfig.fieldReviewCount) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(fieldReviewCount = it.trim()))
            }
            TextField(stringResource(R.string.settings_ta_field_url), state.tripAdvisorApiConfig.fieldUrl) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(fieldUrl = it.trim()))
            }
            TextField(stringResource(R.string.settings_ta_field_address), state.tripAdvisorApiConfig.fieldAddress) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(fieldAddress = it.trim()))
            }
            TextField(stringResource(R.string.settings_ta_field_category), state.tripAdvisorApiConfig.fieldCategory) {
                viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(fieldCategory = it.trim()))
            }
            NumberField(
                stringResource(R.string.settings_ta_max_results),
                state.tripAdvisorApiConfig.maxResults.toString(),
                { v -> v.toIntOrNull()?.coerceIn(1, 20)?.let { n ->
                    viewModel.updateTripAdvisorApiConfig(state.tripAdvisorApiConfig.copy(maxResults = n))
                } },
            )
            Text(
                stringResource(R.string.settings_ta_unverified_hint),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ---------------------------------------------------------------- work / travel times
        SettingsSection(
            stringResource(R.string.settings_work),
            stringResource(R.string.settings_work_body),
        ) {
            NumberField(
                stringResource(R.string.settings_work_end_hour),
                prefs.workEndTime.hour.toString(),
                { v -> v.toIntOrNull()?.coerceIn(0, 23)?.let { h ->
                    viewModel.updatePreferences { it.copy(workEndTime = LocalTime.of(h, prefs.workEndTime.minute)) }
                } },
            )
            NumberField(
                stringResource(R.string.settings_work_end_minute),
                prefs.workEndTime.minute.toString(),
                { v -> v.toIntOrNull()?.coerceIn(0, 59)?.let { m ->
                    viewModel.updatePreferences { it.copy(workEndTime = LocalTime.of(prefs.workEndTime.hour, m)) }
                } },
            )
            TextField(stringResource(R.string.settings_home_city), prefs.homeCity) { v ->
                viewModel.updatePreferences { it.copy(homeCity = v) }
            }
            NumberField(stringResource(R.string.settings_drive_to_airport), prefs.homeToAirportMinutes.toString(), { v ->
                v.toIntOrNull()?.coerceIn(0, 300)?.let { m -> viewModel.updatePreferences { it.copy(homeToAirportMinutes = m) } }
            }, suffix = "min")
            NumberField(stringResource(R.string.settings_airport_buffer), prefs.airportBufferMinutes.toString(), { v ->
                v.toIntOrNull()?.coerceIn(0, 300)?.let { m -> viewModel.updatePreferences { it.copy(airportBufferMinutes = m) } }
            }, suffix = "min")
            NumberField(stringResource(R.string.settings_return_buffer), prefs.returnAirportBufferMinutes.toString(), { v ->
                v.toIntOrNull()?.coerceIn(0, 300)?.let { m -> viewModel.updatePreferences { it.copy(returnAirportBufferMinutes = m) } }
            }, suffix = "min")
            NumberField(stringResource(R.string.settings_drive_home), prefs.airportToHomeMinutes.toString(), { v ->
                v.toIntOrNull()?.coerceIn(0, 300)?.let { m -> viewModel.updatePreferences { it.copy(airportToHomeMinutes = m) } }
            }, suffix = "min")

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CondorinoColors.SurfaceElevated)
                    .padding(12.dp),
            ) {
                Column {
                    Text(
                        stringResource(R.string.settings_earliest_departure),
                        color = CondorinoColors.TextTertiary,
                        fontSize = 11.sp,
                    )
                    Text(
                        "%02d:%02d".format(
                            prefs.earliestReachableDeparture.hour,
                            prefs.earliestReachableDeparture.minute,
                        ),
                        color = CondorinoColors.Amber,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        stringResource(
                            R.string.settings_earliest_formula,
                            prefs.homeToAirportMinutes,
                            prefs.airportBufferMinutes,
                        ),
                        color = CondorinoColors.TextTertiary,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- trip constraints
        SettingsSection(stringResource(R.string.settings_trip_rules)) {
            NumberField(stringResource(R.string.settings_max_flight), prefs.maxFlightMinutes.toString(), { v ->
                // Below ~60 min the scoring math's own fixed comfort anchors (45/60 min) start
                // overlapping this value's derived breakpoints, so 60 is a hard floor, not just a
                // sanity one. 780 min (13 h) comfortably covers Condor's longest routes.
                v.toIntOrNull()?.coerceIn(60, 780)?.let { m -> viewModel.updatePreferences { it.copy(maxFlightMinutes = m) } }
            }, suffix = "min")
            NumberField(stringResource(R.string.settings_min_nights), prefs.minNights.toString(), { v ->
                v.toIntOrNull()?.coerceIn(0, 30)?.let { n -> viewModel.updatePreferences { it.copy(minNights = n) } }
            })
            NumberField(stringResource(R.string.settings_max_nights), prefs.maxNights.toString(), { v ->
                v.toIntOrNull()?.coerceIn(0, 30)?.let { n -> viewModel.updatePreferences { it.copy(maxNights = n) } }
            })
            NumberField(stringResource(R.string.settings_max_budget), (prefs.maxBudgetCents / 100).toString(), { v ->
                v.toLongOrNull()?.coerceAtLeast(0)?.let { e -> viewModel.updatePreferences { it.copy(maxBudgetCents = e * 100) } }
            }, suffix = "€")

            Text(stringResource(R.string.settings_preferred_cabin), color = CondorinoColors.TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Cabin.entries.forEach { cabin ->
                    FilterChip(
                        selected = prefs.preferredCabin == cabin,
                        onClick = { viewModel.updatePreferences { it.copy(preferredCabin = cabin) } },
                        label = { Text(cabin.label(), fontSize = 12.sp) },
                        colors = chipColors(),
                    )
                }
            }

            Text(stringResource(R.string.settings_preferred_patterns), color = CondorinoColors.TextSecondary, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WeekendPattern.byPriority.forEach { pattern ->
                    val selected = pattern in prefs.enabledPatterns
                    FilterChip(
                        selected = selected,
                        onClick = {
                            viewModel.updatePreferences {
                                val next = if (selected) it.enabledPatterns - pattern
                                else it.enabledPatterns + pattern
                                it.copy(enabledPatterns = next.ifEmpty { setOf(pattern) })
                            }
                        },
                        label = { Text(pattern.label(), fontSize = 12.sp) },
                        colors = chipColors(),
                    )
                }
            }

            Text(stringResource(R.string.settings_preferred_types), color = CondorinoColors.TextSecondary, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DestinationType.entries.forEach { type ->
                    val selected = type in prefs.enabledDestinationTypes
                    FilterChip(
                        selected = selected,
                        onClick = {
                            viewModel.updatePreferences {
                                val next = if (selected) it.enabledDestinationTypes - type
                                else it.enabledDestinationTypes + type
                                it.copy(enabledDestinationTypes = next.ifEmpty { setOf(type) })
                            }
                        },
                        label = { Text(type.label(), fontSize = 12.sp) },
                        colors = chipColors(),
                    )
                }
            }
        }

        // ---------------------------------------------------------------- scoring weights
        SettingsSection(
            stringResource(R.string.settings_weights),
            stringResource(R.string.settings_weights_body),
        ) {
            ScoreComponent.entries.forEach { component ->
                WeightSlider(
                    label = component.label(),
                    value = prefs.weights.forComponent(component),
                ) { value ->
                    viewModel.updatePreferences {
                        it.copy(weights = it.weights.withComponent(component, value))
                    }
                }
            }
            Text(
                stringResource(R.string.settings_weights_sum, (prefs.weights.total * 100).toInt()),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
            )
            OutlinedButton(onClick = {
                viewModel.updatePreferences {
                    it.copy(weights = com.condorino.weekend.domain.model.ScoreWeights.DEFAULT)
                }
            }) {
                Text(stringResource(R.string.settings_weights_reset), color = CondorinoColors.Amber, fontSize = 12.sp)
            }
        }

        // ---------------------------------------------------------------- prices
        SettingsSection(
            stringResource(R.string.settings_prices),
            stringResource(R.string.settings_prices_body),
        ) {
            Text(
                stringResource(R.string.settings_prices_count, state.prices.count { it.value.hasAnyPrice }),
                color = CondorinoColors.TextSecondary,
                fontSize = 13.sp,
            )
            OutlinedButton(onClick = onOpenPrices, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_manage_prices), color = CondorinoColors.Amber, fontSize = 13.sp)
            }
        }

        SettingsSection(stringResource(R.string.settings_privacy)) {
            Text(
                stringResource(R.string.settings_privacy_body),
                color = CondorinoColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }

        // ---------------------------------------------------------------- general
        SettingsSection(stringResource(R.string.settings_general)) {
            Text(
                stringResource(R.string.settings_reset_body),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            OutlinedButton(
                onClick = { viewModel.updatePreferences { UserPreferences.DEFAULT } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_reset), color = CondorinoColors.Amber, fontSize = 13.sp)
            }
            Text(
                stringResource(R.string.settings_clear_cache_body),
                color = CondorinoColors.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            OutlinedButton(onClick = viewModel::clearCache, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_clear_cache), color = CondorinoColors.Amber, fontSize = 13.sp)
            }
        }

        // ---------------------------------------------------------------- about
        AboutSection()

        Spacer(Modifier.height(40.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = CondorinoColors.SurfaceElevated,
    labelColor = CondorinoColors.TextSecondary,
    selectedContainerColor = CondorinoColors.Amber,
    selectedLabelColor = CondorinoColors.Background,
)
