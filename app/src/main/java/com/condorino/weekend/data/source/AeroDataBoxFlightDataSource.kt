package com.condorino.weekend.data.source

import com.condorino.weekend.core.Formatting
import com.condorino.weekend.R
import com.condorino.weekend.domain.model.Airlines
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Flight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * The airport's FIDS response covers every airline flying through it, not just the ones this app
 * knows about — [selectedAirlinesProvider] (Condor, always, plus whichever Lufthansa Group carriers
 * are opted into in Settings → Airlines, see [Airlines]) is what narrows that down; a row whose
 * operating carrier isn't in that set is dropped in [toFlight], the same way a row missing a usable
 * airport or time already is.
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
    /** ICAO codes of the airlines to keep — Condor plus whichever Lufthansa Group carriers are
     *  opted in, see [PreferencesStore.selectedLufthansaGroupCodes] and [Airlines]. */
    private val selectedAirlinesProvider: suspend () -> Set<String>,
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
            val selected = selectedAirlinesProvider()
            val windows = chunkWindows(query, config)
            val flights = mutableListOf<Flight>()

            windows.forEachIndexed { index, (windowFrom, windowTo) ->
                // A modest pace between chunks, not on the first request. A search can fire up to
                // MAX_CHUNKS of these back to back with no pacing at all otherwise, which is a
                // reliable way to trip a RapidAPI Basic plan's own *per-second* gateway throttle —
                // a short-term limit entirely separate from, and far stricter than, the monthly
                // quota shown in the RapidAPI dashboard (see the 429 handling below).
                if (index > 0) delay(CHUNK_PACING_MILLIS)

                val url = windowUrl(config, windowFrom, windowTo)
                val request = Request.Builder().url(url).get()
                    .addHeader("Accept", "application/json")
                    .addHeader("X-RapidAPI-Key", apiKey.trim())
                    .addHeader("X-RapidAPI-Host", config.apiHost.trim())
                    .build()

                // A 429 from this gateway is usually the per-second gate rather than a spent
                // quota, so it clears within a second or two. Waiting once and asking again turns
                // most of them into a successful search instead of an error the user has to act
                // on; a second 429 is reported, since by then it is unlikely to be transient.
                var attempt = 0
                while (true) {
                    val retryAfterMillis: Long? = try {
                        client.newCall(request).execute().use { response ->
                            when {
                                response.code == 401 || response.code == 403 ->
                                    return@withContext FlightSearchResult.Failure(
                                        strings.get(R.string.src_aerodatabox_denied),
                                        "HTTP ${response.code}",
                                    )

                                response.code == 429 && attempt < RATE_LIMIT_RETRIES ->
                                    (response.header("Retry-After")?.toLongOrNull()?.times(1_000)
                                        ?: (CHUNK_PACING_MILLIS * 2))
                                        .coerceAtMost(MAX_RETRY_WAIT_MILLIS)

                                response.code == 429 -> return@withContext FlightSearchResult.Failure(
                                    // Very often the RapidAPI *gateway's* own per-second throttle
                                    // rather than the monthly quota shown in the dashboard, which
                                    // can (and does) sit in single digits when this fires. The
                                    // standard Retry-After is read when sent, so the message says
                                    // something concrete instead of implying a spent account.
                                    response.header("Retry-After")?.toLongOrNull()?.let {
                                        strings.get(R.string.src_aerodatabox_rate_limited_retry, Formatting.retryDelay(it))
                                    } ?: strings.get(R.string.src_aerodatabox_rate_limited),
                                    "HTTP 429",
                                )

                                !response.isSuccessful -> return@withContext FlightSearchResult.Failure(
                                    strings.get(R.string.src_aerodatabox_http, response.code),
                                    response.message,
                                )

                                else -> {
                                    val body = response.body?.string().orEmpty()
                                    if (body.isNotBlank()) {
                                        flights += mapFlights(body, config, home, airports, selected)
                                    }
                                    null
                                }
                            }
                        }
                    } catch (e: IOException) {
                        return@withContext FlightSearchResult.Failure(strings.get(R.string.src_aerodatabox_offline), e.message)
                    } catch (e: CancellationException) {
                        // Cancellation is not a failure: it means the caller went away (a new
                        // search superseded this one, or the screen was left). Reporting it as an
                        // error would put a spurious message on screen and hide the cancellation.
                        throw e
                    } catch (e: Exception) {
                        return@withContext FlightSearchResult.Failure(strings.get(R.string.src_aerodatabox_parse_failed), e.message)
                    }

                    if (retryAfterMillis == null) break
                    attempt++
                    delay(retryAfterMillis)
                }
            }

            val filtered = flights.filter { f ->
                query.destinationIata == null ||
                    f.destination.iata == query.destinationIata ||
                    f.origin.iata == query.destinationIata
            }

            val selectedNames = Airlines.describe(selected)
            if (filtered.isEmpty()) {
                FlightSearchResult.Failure(
                    strings.get(R.string.src_aerodatabox_no_flights, selectedNames, config.homeIata),
                )
            } else {
                FlightSearchResult.Success(
                    flights = filtered,
                    provenance = DataProvenance.LIVE,
                    retrievedAt = now(),
                    note = strings.get(R.string.src_aerodatabox_note, selectedNames),
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
     * the mirror image. Rows whose operating carrier isn't in [selectedAirlines] (see the class doc),
     * or missing a usable airport or time, are dropped rather than defaulted or guessed.
     */
    internal fun mapFlights(
        body: String,
        config: AeroDataBoxConfig,
        home: Airport,
        airports: Map<String, Airport>,
        selectedAirlines: Set<String>,
    ): List<Flight> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val departures = resolvePath(root, config.departuresItemsPath) as? JsonArray ?: JsonArray(emptyList())
        val arrivals = resolvePath(root, config.arrivalsItemsPath) as? JsonArray ?: JsonArray(emptyList())

        val out = mutableListOf<Flight>()
        departures.forEach { toFlight(it, config, home, airports, selectedAirlines, outbound = true)?.let(out::add) }
        arrivals.forEach { toFlight(it, config, home, airports, selectedAirlines, outbound = false)?.let(out::add) }
        return out
    }

    private fun toFlight(
        element: JsonElement,
        config: AeroDataBoxConfig,
        home: Airport,
        airports: Map<String, Airport>,
        selectedAirlines: Set<String>,
        outbound: Boolean,
    ): Flight? {
        val obj = element as? JsonObject ?: return null

        val reportedAirline = obj.str(config.fieldAirlineIcao) ?: return null
        // Resolved, not string-compared: the selection holds ICAO codes, but the field this reads
        // is user-configurable and a response may well carry the IATA one ("DE" for Condor)
        // instead. Comparing raw would silently drop every flight and report an empty airport.
        val airline = Airlines.resolve(reportedAirline)
        val airlineIcao = airline?.icaoCode ?: reportedAirline.trim().uppercase()
        if (selectedAirlines.none { Airlines.canonicalIcao(it) == airlineIcao }) return null

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
            airline = obj.str(config.fieldAirlineName) ?: airline?.displayName ?: airlineIcao,
            airlineCode = airlineIcao,
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

        /** Between chunk requests within one search — see the pacing comment above. */
        const val CHUNK_PACING_MILLIS = 400L

        /** One retry after a 429 — see the retry loop for why once, and why the wait is bounded. */
        const val RATE_LIMIT_RETRIES = 1

        /** A longer Retry-After than this is not the per-second gate, and waiting it out in the
         *  foreground would just hang the screen. */
        const val MAX_RETRY_WAIT_MILLIS = 3_000L
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
