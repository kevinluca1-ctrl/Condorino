package com.condorino.weekend.data.source

import com.condorino.weekend.R
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Flight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * ## Status of this data source — read before using
 *
 * This talks to AeroDataBox's "Airport Flights (FIDS)" endpoint, published on RapidAPI at host
 * `aerodatabox.p.rapidapi.com`: `GET /flights/airports/iata/{code}/{fromLocal}/{toLocal}`, which
 * returns the *actual scheduled/live* departures and arrivals for one airport over a local time
 * window, in one response — unlike [OpenSkyFlightDataSource] this is a direct schedule query for
 * the exact dates asked, not a reconstruction from historical ADS-B observations, which is why it
 * sits ahead of OpenSky in the trust order once configured.
 *
 * AeroDataBox's own documentation site (`doc.aerodatabox.com`) could not be reached from where this
 * was written (blocked by network egress, the same as every other RapidAPI listing referenced in
 * this app) — but unlike [TripAdvisorRecommendationSource]'s listing, this one is widely documented
 * and cross-referenced across multiple independent public sources, so the endpoint path and the
 * broad response shape below (a `departures`/`arrivals` object, each entry carrying nested
 * `departure`/`arrival` sub-objects with `airport`, `scheduledTimeUtc`/`scheduledTimeLocal`) are a
 * considerably more confident reconstruction than that one — but still **not a fetched, verified
 * contract**, so exactly like every other RapidAPI source in this app, every field name below is
 * user-editable in Settings → AeroDataBox rather than hard-coded as fact (see [mapFlights], the
 * single place this is interpreted). AeroDataBox's own tiering also lists this endpoint as needing
 * more than the free "Basic" plan on some accounts — if every request comes back denied, check your
 * RapidAPI subscription's plan for this API before assuming the field mapping is wrong.
 *
 * Airports are matched by **IATA** code, not ICAO — deliberately, since every other part of this
 * app (the [Airport] model, [FlightSearchQuery], the bundled reference data) is IATA-keyed already,
 * so this avoids needing a second, ICAO-keyed lookup just for this one source. If your real response
 * reports airport codes under a different field, or as ICAO only, correct
 * [AeroDataBoxConfig.fieldDepartureAirportCode]/[AeroDataBoxConfig.fieldArrivalAirportCode] in
 * Settings — but note that field still needs to resolve an IATA code for a flight to be usable here.
 *
 * A single request's local time window is capped by [AeroDataBoxConfig.windowHours] — RapidAPI's
 * lower subscription tiers are commonly reported to reject a request spanning much more than half a
 * day — so a query covering a whole weekend is split into several chunked requests, the same
 * pattern [OpenSkyFlightDataSource] uses and for the same reason: better to make a few small,
 * reliable requests than one big one of uncertain validity.
 */
class AeroDataBoxFlightDataSource(
    private val client: OkHttpClient,
    private val configProvider: suspend () -> AeroDataBoxConfig,
    /** IATA-keyed, same shape and reasoning as [CondorDeveloperApiDataSource]'s constructor
     *  parameter of the same name: resolved once per [search] call, then used synchronously. */
    private val airportCatalog: suspend () -> Map<String, Airport>,
    /** The one RapidAPI key shared by every RapidAPI-hosted source — see [PreferencesStore.rapidApiKey]. */
    private val apiKeyProvider: suspend () -> String,
    override val strings: SourceStrings,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val now: () -> Instant = { Instant.now() },
    /** Always "https" in production; overridable only so tests can point this at a plain-HTTP
     *  [okhttp3.mockwebserver.MockWebServer] without standing up TLS just to exercise HTTP codes. */
    private val scheme: String = "https",
) : FlightDataSource {

    override val id: String = "aerodatabox"
    override val displayName: String get() = strings.get(R.string.src_aerodatabox_name)
    override val bestProvenance: DataProvenance = DataProvenance.LIVE

    override suspend fun status(): SourceStatus {
        val config = configProvider()
        return when {
            !config.enabled -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_aerodatabox_disabled),
                howToFix = strings.get(R.string.src_aerodatabox_disabled_fix),
            )
            apiKeyProvider().isBlank() -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_aerodatabox_no_key),
                howToFix = strings.get(R.string.src_aerodatabox_no_key_fix),
            )
            config.apiHost.isBlank() || config.homeIata.length != 3 -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_aerodatabox_no_home),
                howToFix = strings.get(R.string.src_aerodatabox_no_home_fix),
            )
            else -> SourceStatus.Ready
        }
    }

    override suspend fun search(query: FlightSearchQuery): FlightSearchResult =
        withContext(Dispatchers.IO) {
            val config = configProvider()
            when (val s = status()) {
                is SourceStatus.NotConfigured -> return@withContext FlightSearchResult.NotConfigured(s.reason, s.howToFix)
                is SourceStatus.Unavailable -> return@withContext FlightSearchResult.Failure(s.reason)
                SourceStatus.Ready -> Unit
            }

            val airports = airportCatalog()
            val home = airports[config.homeIata.uppercase()]
                ?: return@withContext FlightSearchResult.Failure(
                    strings.get(R.string.src_aerodatabox_home_unknown, config.homeIata),
                )

            val apiKey = apiKeyProvider()
            val windows = chunkWindows(query, config)
            val flights = mutableListOf<Flight>()

            for ((windowFrom, windowTo) in windows) {
                val url = windowUrl(config, windowFrom, windowTo)
                val request = Request.Builder().url(url).get()
                    .addHeader("Accept", "application/json")
                    .addHeader("X-RapidAPI-Key", apiKey.trim())
                    .addHeader("X-RapidAPI-Host", config.apiHost.trim())
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        when {
                            response.code == 401 || response.code == 403 -> return@withContext FlightSearchResult.Failure(
                                strings.get(R.string.src_aerodatabox_denied),
                                "HTTP ${response.code}",
                            )
                            response.code == 429 -> return@withContext FlightSearchResult.Failure(
                                strings.get(R.string.src_aerodatabox_rate_limited),
                                "HTTP 429",
                            )
                            !response.isSuccessful -> return@withContext FlightSearchResult.Failure(
                                strings.get(R.string.src_aerodatabox_http, response.code),
                                response.message,
                            )
                        }
                        val body = response.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            flights += mapFlights(body, config, home, airports)
                        }
                    }
                } catch (e: IOException) {
                    return@withContext FlightSearchResult.Failure(strings.get(R.string.src_aerodatabox_offline), e.message)
                } catch (e: Exception) {
                    return@withContext FlightSearchResult.Failure(strings.get(R.string.src_aerodatabox_parse_failed), e.message)
                }
            }

            val filtered = flights.filter { f ->
                query.destinationIata == null ||
                    f.destination.iata == query.destinationIata ||
                    f.origin.iata == query.destinationIata
            }

            if (filtered.isEmpty()) {
                FlightSearchResult.Failure(
                    strings.get(R.string.src_aerodatabox_no_flights, config.airlineIcaoFilter, config.homeIata),
                )
            } else {
                FlightSearchResult.Success(
                    flights = filtered,
                    provenance = DataProvenance.LIVE,
                    retrievedAt = now(),
                    note = strings.get(R.string.src_aerodatabox_note, config.airlineIcaoFilter),
                )
            }
        }

    /**
     * Splits [query]'s date range into local time windows no longer than
     * [AeroDataBoxConfig.windowHours], capped at [MAX_CHUNKS] requests — see the class doc.
     */
    internal fun chunkWindows(
        query: FlightSearchQuery,
        config: AeroDataBoxConfig,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val rangeStart = query.from.atStartOfDay()
        val rangeEnd = query.to.plusDays(1).atStartOfDay()
        val windowHours = config.windowHours.coerceAtLeast(1).toLong()

        val windows = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        var chunkStart = rangeStart
        while (chunkStart.isBefore(rangeEnd) && windows.size < MAX_CHUNKS) {
            val chunkEnd = minOf(chunkStart.plusHours(windowHours), rangeEnd)
            windows += chunkStart to chunkEnd
            chunkStart = chunkEnd
        }
        return windows
    }

    internal fun windowUrl(config: AeroDataBoxConfig, from: LocalDateTime, to: LocalDateTime): String = buildString {
        append(scheme).append("://").append(config.apiHost.trim())
        append("/flights/airports/iata/").append(config.homeIata.trim())
        append('/').append(from.format(PATH_TIME_FORMAT))
        append('/').append(to.format(PATH_TIME_FORMAT))
        append("?withLeg=").append(config.withLeg)
        append("&withCancelled=").append(config.withCancelled)
        append("&withCodeshared=").append(config.withCodeshared)
        append("&withPrivate=").append(config.withPrivate)
    }

    /**
     * Generic mapping from one window's response onto [Flight] — same reasoning as
     * [CondorDeveloperApiDataSource.mapFlights]: field names come from [AeroDataBoxConfig], user
     * corrected if the guessed defaults are wrong. An entry in [AeroDataBoxConfig.departuresItemsPath]
     * has [home] as its origin and whatever [AeroDataBoxConfig.fieldArrivalAirportCode] resolves to
     * (against [airports]) as its destination; an entry in [AeroDataBoxConfig.arrivalsItemsPath] is
     * the mirror image. Rows outside [config]'s airline filter, or missing a usable airport or time,
     * are dropped rather than defaulted or guessed.
     */
    internal fun mapFlights(
        body: String,
        config: AeroDataBoxConfig,
        home: Airport,
        airports: Map<String, Airport>,
    ): List<Flight> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val departures = resolvePath(root, config.departuresItemsPath) as? JsonArray ?: JsonArray(emptyList())
        val arrivals = resolvePath(root, config.arrivalsItemsPath) as? JsonArray ?: JsonArray(emptyList())

        val out = mutableListOf<Flight>()
        departures.forEach { toFlight(it, config, home, airports, outbound = true)?.let(out::add) }
        arrivals.forEach { toFlight(it, config, home, airports, outbound = false)?.let(out::add) }
        return out
    }

    private fun toFlight(
        element: JsonElement,
        config: AeroDataBoxConfig,
        home: Airport,
        airports: Map<String, Airport>,
        outbound: Boolean,
    ): Flight? {
        val obj = element as? JsonObject ?: return null

        if (config.airlineIcaoFilter.isNotBlank()) {
            val airlineIcao = obj.str(config.fieldAirlineIcao)
            if (!airlineIcao.equals(config.airlineIcaoFilter, ignoreCase = true)) return null
        }

        val otherCode = obj.str(if (outbound) config.fieldArrivalAirportCode else config.fieldDepartureAirportCode)
            ?: return null
        val other = airports[otherCode.uppercase()] ?: return null
        if (other.iata == home.iata) return null

        val departureTime = obj.str(config.fieldDepartureTimeUtc)?.let(::parseAeroDataBoxTime) ?: return null
        val arrivalTime = obj.str(config.fieldArrivalTimeUtc)?.let(::parseAeroDataBoxTime) ?: return null
        if (!arrivalTime.isAfter(departureTime)) return null

        val origin = if (outbound) home else other
        val destination = if (outbound) other else home

        return Flight(
            flightNumber = obj.str(config.fieldFlightNumber),
            // A brand name, like CondorDeveloperApiDataSource's own "Condor" literal — not
            // localized text, so it is not routed through SourceStrings.
            airline = obj.str(config.fieldAirlineName) ?: "Condor",
            airlineCode = obj.str(config.fieldAirlineIcao) ?: config.airlineIcaoFilter,
            origin = origin,
            destination = destination,
            departure = departureTime,
            arrival = arrivalTime,
            isDirect = true,
            provenance = DataProvenance.LIVE,
            retrievedAt = now(),
        )
    }

    private fun JsonObject.at(key: String): JsonElement? {
        if (key.isBlank()) return null
        return if (key.contains('.')) resolvePath(this, key) else this[key]
    }

    private fun JsonObject.str(key: String): String? =
        (at(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun resolvePath(root: JsonElement, path: String): JsonElement? {
        if (path.isBlank()) return root
        var current: JsonElement = root
        for (segment in path.split('.').filter { it.isNotBlank() }) {
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }

    /**
     * AeroDataBox's UTC timestamps are commonly reported as `"yyyy-MM-dd HH:mm[:ss]Z"` — a space
     * rather than a `T` separator, which [Instant.parse] rejects outright. This normalises that one
     * known quirk before falling back through the same parsers [CondorDeveloperApiDataSource] uses,
     * and gives up (returns null, dropping the row) rather than guessing a timezone for anything
     * else unrecognised.
     */
    internal fun parseAeroDataBoxTime(value: String): Instant? {
        val normalized = if (value.length > 10 && value[10] == ' ') {
            value.substring(0, 10) + "T" + value.substring(11)
        } else {
            value
        }
        return runCatching { Instant.parse(normalized) }.getOrElse {
            runCatching { OffsetDateTime.parse(normalized).toInstant() }.getOrNull()
        }
    }

    private companion object {
        /** yyyy-MM-dd'T'HH:mm, the local-time path segment format AeroDataBox's own examples use. */
        val PATH_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        /**
         * Hard ceiling on chunked requests per search. At the default 12-hour window a full weekend
         * (Friday morning through Monday night, ~4 days) needs about 8 chunks; this leaves headroom
         * without letting a much longer custom date range turn into an unbounded request burst —
         * the same reasoning [OpenSkyFlightDataSource.MAX_CHUNKS] documents in more detail.
         */
        const val MAX_CHUNKS = 16
    }
}

/**
 * User-editable description of the AeroDataBox (RapidAPI) contract. Defaults are a considerably
 * more confident reconstruction than most other RapidAPI sources in this app — see the class doc on
 * [AeroDataBoxFlightDataSource] — but still not a verified contract; correct any field here from
 * your own RapidAPI account's "Test Endpoint" panel if a real response doesn't match.
 */
data class AeroDataBoxConfig(
    val enabled: Boolean = false,
    val apiHost: String = "aerodatabox.p.rapidapi.com",
    /** IATA code of the home airport. Frankfurt is FRA — see the class doc on why this source is
     *  IATA-keyed throughout rather than ICAO like [OpenSkyFlightDataSource]. */
    val homeIata: String = Airport.HOME_IATA,
    /** Max local-time hours per request — see the class doc on why this is chunked at all. */
    val windowHours: Int = 12,
    val withLeg: Boolean = true,
    val withCancelled: Boolean = false,
    val withCodeshared: Boolean = false,
    val withPrivate: Boolean = false,
    /** Matched case-insensitively against [fieldAirlineIcao]; blank disables the filter entirely
     *  (every airline at the airport, not just Condor). Condor's ICAO designator is CFG. */
    val airlineIcaoFilter: String = "CFG",
    /** Dotted path to the departures array in the response, e.g. `departures`. */
    val departuresItemsPath: String = "departures",
    /** Dotted path to the arrivals array in the response, e.g. `arrivals`. */
    val arrivalsItemsPath: String = "arrivals",
    val fieldDepartureAirportCode: String = "departure.airport.iata",
    val fieldArrivalAirportCode: String = "arrival.airport.iata",
    val fieldDepartureTimeUtc: String = "departure.scheduledTimeUtc",
    val fieldArrivalTimeUtc: String = "arrival.scheduledTimeUtc",
    val fieldFlightNumber: String = "number",
    val fieldAirlineName: String = "airline.name",
    val fieldAirlineIcao: String = "airline.icao",
)
