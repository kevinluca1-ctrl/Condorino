package com.condorino.weekend.data.source

import com.condorino.weekend.R
import com.condorino.weekend.data.reference.AirportReferenceCatalog
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Flight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * ## Cross-check against flights that were actually flown — OpenSky Network
 *
 * The OpenSky Network publishes a free REST API over crowd-sourced ADS-B receptions. Unlike an
 * airline's booking backend it answers a different question, and that difference is the point:
 *
 *  * a schedule says *"this route is planned"*,
 *  * OpenSky says **"this aircraft actually flew, at this time, on this day"**.
 *
 * That makes it a genuine cross-check. This source asks OpenSky which flights with a Condor
 * callsign (`CFG…`) actually departed Frankfurt over the past few weeks, groups them by weekday and
 * destination, and derives an **observed timetable** from them, which it then projects onto the
 * weekend the user is looking at.
 *
 * ### What this is not
 *
 * These are observations, not availability. `firstSeen` is when the transponder was first picked up
 * — close to, but not identical with, the scheduled departure. Nothing here says a seat is
 * bookable. Everything this source produces is therefore tagged [DataProvenance.SCHEDULE], never
 * `LIVE`, and the UI labels it accordingly.
 *
 * ### Contract
 *
 * Verified against OpenSky's public API documentation (re-checked 2026-08-29):
 *  * `GET {base}/flights/departure?airport={ICAO}&begin={unix}&end={unix}`
 *  * `GET {base}/flights/arrival?airport={ICAO}&begin={unix}&end={unix}`
 *  * response: JSON array of objects with `icao24`, `firstSeen`, `estDepartureAirport`,
 *    `lastSeen`, `estArrivalAirport`, `callsign` (airport codes are **ICAO**, times are Unix
 *    seconds). HTTP 404 means "nothing in this window", not an error.
 *  * auth is OAuth2 client-credentials only (basic auth was retired); a token lasts 30 minutes,
 *    and OpenSky's own guidance is that a 401 from a data endpoint means it just expired — refresh
 *    and retry, don't treat it as a rejected credential. [fetchObservations] and [selfTest] both do
 *    exactly one such refresh-and-retry before reporting a 401/403 as an actual denial.
 *  * the `flights` endpoints spend from a daily/hourly credit quota (400-14,400 depending on
 *    account tier) that is independent of the `states` endpoints. Critically, the cost of a single
 *    request is not flat: a request whose window stays under 24 hours costs 4 credits, but one that
 *    merely crosses into a second calendar day jumps to 30, and gets steeper again from there. Many
 *    short requests are therefore *much* cheaper than a few long ones for the same total lookback —
 *    which is why
 *    [CHUNK_WINDOW_SECONDS] is kept safely under 24 hours rather than the multi-day chunks this
 *    source used to request (those could burn an entire day's quota, sometimes more than one, in a
 *    single search — the most likely explanation for "OpenSky reports nothing even with correct
 *    credentials" reports where credits, not credentials, had actually run out).
 *  * `/flights/departure`'s own docs currently read "the given time interval must cover more than
 *    two days", the reverse of `/flights/arrival`'s "must not be larger than two days" — almost
 *    certainly a documentation error rather than an intentional asymmetry, since it would leave no
 *    way to request a small, cheap window from that one endpoint at all. This source does not
 *    trust either reading blindly: if a departure-endpoint chunk is rejected with 400, it retries
 *    that one chunk with a larger window before giving up on it (see [fetchChunk]'s caller).
 *  * anonymous access works but is rate-limited; OAuth2 client-credentials raise the limits.
 */
class OpenSkyFlightDataSource(
    private val client: OkHttpClient,
    private val configProvider: suspend () -> OpenSkyConfig,
    private val airportCatalog: AirportReferenceCatalog,
    override val strings: SourceStrings,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val now: () -> Instant = { Instant.now() },
) : FlightDataSource {

    override val id: String = "opensky"
    override val displayName: String get() = strings.get(R.string.src_opensky_name)
    override val bestProvenance: DataProvenance = DataProvenance.SCHEDULE

    private var cachedToken: String? = null
    private var tokenExpiry: Instant = Instant.EPOCH

    // In-process cache of the last observation fetch, so repeated triggers within one session
    // (weekend navigation, pull-to-refresh, the reactive collectors that call refresh()) reuse it
    // instead of repeating the request burst below — see [search]'s FETCH_COOLDOWN comment. Only the
    // fetch+aggregation outcome is cached, never the final per-query result — see [search].
    private var lastFetchAt: Instant = Instant.EPOCH
    private var cached: FetchState? = null

    override suspend fun status(): SourceStatus {
        val config = configProvider()
        return when {
            !config.enabled -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_opensky_disabled),
                howToFix = strings.get(R.string.src_opensky_disabled_fix),
            )
            config.homeIcao.length != 4 -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_opensky_no_icao),
                howToFix = strings.get(R.string.src_opensky_no_icao_fix),
            )
            config.clientId.isNotBlank() && config.clientSecret.isBlank() -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_opensky_no_secret),
                howToFix = strings.get(R.string.src_opensky_no_secret_fix),
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

            val home = airportCatalog.byIcao(config.homeIcao)
                ?: return@withContext FlightSearchResult.Failure(
                    strings.get(R.string.src_opensky_home_unknown, config.homeIcao),
                )

            // The observed timetable is a median over weeks of history — it does not meaningfully
            // change from one refresh to the next, so there is no reason to repeat the request burst
            // below every time the user changes weekends or pulls to refresh. What's cached here is
            // the *observation fetch*, not the final result: projectOnto() below still runs fresh
            // for every call, against whatever date range and destination this particular query
            // asks for, so a cached fetch still answers a different weekend correctly.
            val state = cached?.takeIf { Duration.between(lastFetchAt, now()) < FETCH_COOLDOWN }
                ?: fetchAndBuildTimetable(config, home).also {
                    cached = it
                    lastFetchAt = now()
                }

            when (state) {
                is FetchState.Failed -> state.result
                is FetchState.Ok -> {
                    val flights = (state.outbound + state.inbound)
                        .flatMap { service ->
                            service.projectOnto(query.from, query.to) { days ->
                                strings.plural(R.plurals.src_opensky_observed_days, days, days)
                            }
                        }
                        .filter { f ->
                            query.destinationIata == null ||
                                f.destination.iata == query.destinationIata ||
                                f.origin.iata == query.destinationIata
                        }

                    if (flights.isEmpty()) {
                        FlightSearchResult.Failure(strings.get(R.string.src_opensky_no_timetable))
                    } else {
                        FlightSearchResult.Success(
                            flights = flights,
                            provenance = DataProvenance.SCHEDULE,
                            retrievedAt = now(),
                            note = strings.get(
                                R.string.src_opensky_note,
                                state.sampleCount,
                                config.callsignPrefix,
                                config.lookbackWeeks,
                            ),
                        )
                    }
                }
            }
        }

    /** What one real observation fetch + aggregation produced — cached across calls, see [search]. */
    private sealed interface FetchState {
        data class Ok(
            val outbound: List<ObservedService>,
            val inbound: List<ObservedService>,
            val sampleCount: Int,
        ) : FetchState
        data class Failed(val result: FlightSearchResult.Failure) : FetchState
    }

    /** The network burst and aggregation step of [search], separated out so it can be cached. */
    private suspend fun fetchAndBuildTimetable(config: OpenSkyConfig, home: Airport): FetchState {
        try {
            // A configured client that fails to authenticate must say so. Quietly dropping to
            // anonymous access looks exactly like "my credentials don't work": the request
            // succeeds, returns almost nothing because anonymous limits are tight, and the
            // user is never told why.
            val token = if (config.clientId.isNotBlank()) {
                when (val auth = obtainToken(config)) {
                    is TokenResult.Success -> auth.token
                    is TokenResult.Failure -> return FetchState.Failed(
                        FlightSearchResult.Failure(
                            strings.get(R.string.src_opensky_auth_failed, auth.reason),
                            auth.detail,
                        ),
                    )
                }
            } else {
                null
            }

            val departureFetch = fetchObservations(config, token, arrivals = false)
            val arrivalFetch = fetchObservations(config, token, arrivals = true)
            val departures = departureFetch.observations
            val arrivals = arrivalFetch.observations
            if (departures.isEmpty() && arrivals.isEmpty()) {
                // Every chunk request either failed outright or returned an unexpected status —
                // this is not the same fact as "OpenSky checked and there really is nothing", and
                // must not be reported as if it were.
                val failedCode = departureFetch.lastErrorCode ?: arrivalFetch.lastErrorCode
                val retryAfter = departureFetch.retryAfterSeconds ?: arrivalFetch.retryAfterSeconds
                return FetchState.Failed(
                    if (failedCode != null) {
                        val message = when {
                            failedCode == 429 && retryAfter != null ->
                                strings.get(R.string.src_opensky_rate_limited_retry, retryAfter)
                            failedCode == 429 -> strings.get(R.string.src_opensky_rate_limited)
                            failedCode == 401 || failedCode == 403 -> strings.get(R.string.src_opensky_denied, failedCode)
                            else -> strings.get(R.string.src_opensky_http, failedCode)
                        }
                        FlightSearchResult.Failure(message)
                    } else {
                        FlightSearchResult.Failure(
                            strings.get(
                                R.string.src_opensky_no_flights,
                                config.lookbackWeeks,
                                config.callsignPrefix,
                                config.homeIcao,
                            ),
                        )
                    },
                )
            }

            return FetchState.Ok(
                outbound = buildTimetable(departures, home, outbound = true),
                inbound = buildTimetable(arrivals, home, outbound = false),
                sampleCount = departures.size + arrivals.size,
            )
        } catch (e: IOException) {
            return FetchState.Failed(FlightSearchResult.Failure(strings.get(R.string.src_opensky_offline), e.message))
        } catch (e: Exception) {
            return FetchState.Failed(FlightSearchResult.Failure(strings.get(R.string.src_opensky_parse_failed), e.message))
        }
    }

    // ------------------------------------------------------------------ fetching

    /** One observed flight, reduced to what this source needs. */
    internal data class Observation(
        val callsign: String,
        val departureIcao: String,
        val arrivalIcao: String,
        val firstSeen: Instant,
        val lastSeen: Instant,
    )

    /**
     * @param lastErrorCode the most recent HTTP status that was neither success nor a documented
     *   "nothing in this window" 404 — set when at least one chunk request failed outright, so the
     *   caller can tell "OpenSky genuinely reported zero flights" apart from "every request to
     *   OpenSky failed and this is not actually zero flights".
     * @param retryAfterSeconds set alongside a 429 [lastErrorCode] when OpenSky's own
     *   `X-Rate-Limit-Retry-After-Seconds` response header said how long to wait.
     */
    internal data class ObservationFetch(
        val observations: List<Observation>,
        val lastErrorCode: Int? = null,
        val retryAfterSeconds: Long? = null,
    )

    /** The outcome of one `/flights/{departure,arrival}` request for a single time window. */
    private sealed interface ChunkResult {
        data class Observations(val list: List<Observation>) : ChunkResult
        data object NotFound : ChunkResult
        data class RateLimited(val retryAfterSeconds: Long?) : ChunkResult
        data class Denied(val code: Int) : ChunkResult
        data class OtherError(val code: Int) : ChunkResult
    }

    private fun fetchChunk(url: String, token: String?, callsignPrefix: String): ChunkResult {
        val builder = Request.Builder().url(url).get().addHeader("Accept", "application/json")
        if (token != null) builder.addHeader("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { response ->
            return when (response.code) {
                // OpenSky answers 404 when it simply has nothing for the window — a real answer,
                // not a failure.
                404 -> ChunkResult.NotFound
                // The response header says exactly how long to back off; pass it on rather than
                // discarding it, so the failure message can tell the user *when* to try again
                // instead of just that it failed.
                429 -> ChunkResult.RateLimited(
                    response.header("X-Rate-Limit-Retry-After-Seconds")?.toLongOrNull(),
                )
                401, 403 -> ChunkResult.Denied(response.code)
                else -> if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    ChunkResult.Observations(if (body.isBlank()) emptyList() else parseObservations(body, callsignPrefix))
                } else {
                    ChunkResult.OtherError(response.code)
                }
            }
        }
    }

    internal suspend fun fetchObservations(
        config: OpenSkyConfig,
        initialToken: String?,
        arrivals: Boolean,
    ): ObservationFetch {
        val endpoint = if (arrivals) "arrival" else "departure"
        val nowSeconds = now().epochSecond
        val totalSeconds = config.lookbackWeeks.coerceAtLeast(1) * 7L * 24L * 3600L
        val chunks = ceilDiv(totalSeconds, CHUNK_WINDOW_SECONDS).coerceIn(1, MAX_CHUNKS)

        val out = mutableListOf<Observation>()
        var lastErrorCode: Int? = null
        var retryAfterSeconds: Long? = null
        var token = initialToken
        var triedTokenRefresh = false

        for (i in 0 until chunks) {
            val end = nowSeconds - i * CHUNK_WINDOW_SECONDS
            val begin = end - CHUNK_WINDOW_SECONDS
            val url = "${config.baseUrl.trimEnd('/')}/flights/$endpoint" +
                "?airport=${config.homeIcao}&begin=$begin&end=$end"

            var result = fetchChunk(url, token, config.callsignPrefix)

            // A 401 on a data request most plausibly means the (30-minute-lived) token expired
            // between being issued and being used, not that the credentials are actually wrong —
            // that is OpenSky's own documented reading of a 401 here. Refresh once and retry this
            // exact chunk before believing the denial; only one refresh per search, since a second
            // 401 right after a fresh token really is a rejection.
            if (result is ChunkResult.Denied && token != null && !triedTokenRefresh) {
                triedTokenRefresh = true
                when (val refreshed = obtainToken(config, forceRefresh = true)) {
                    is TokenResult.Success -> {
                        token = refreshed.token
                        result = fetchChunk(url, token, config.callsignPrefix)
                    }
                    is TokenResult.Failure -> Unit // keep the original denial
                }
            }

            // The departure endpoint's own docs disagree with its siblings about which interval
            // lengths it accepts (see the class doc). A 400 specifically on that endpoint is
            // treated as "maybe this window length was the problem" rather than a hard failure:
            // retry once with a several-day window, which every reading of the docs agrees is
            // valid, before giving up on this chunk.
            if (result is ChunkResult.OtherError && result.code == 400 && endpoint == "departure") {
                val wideBegin = end - WIDE_FALLBACK_WINDOW_SECONDS
                val wideUrl = "${config.baseUrl.trimEnd('/')}/flights/$endpoint" +
                    "?airport=${config.homeIcao}&begin=$wideBegin&end=$end"
                result = fetchChunk(wideUrl, token, config.callsignPrefix)
            }

            when (val r = result) {
                is ChunkResult.Observations -> out += r.list
                ChunkResult.NotFound -> Unit
                is ChunkResult.RateLimited -> {
                    // Back off rather than hammering a rate-limited free service, but say so:
                    // silently returning whatever was found so far would look exactly like "that's
                    // everything there is" instead of "the rest of the window was never checked".
                    return ObservationFetch(out, lastErrorCode = 429, retryAfterSeconds = r.retryAfterSeconds)
                }
                is ChunkResult.Denied -> {
                    // A rejected token/credential fails identically on every remaining chunk — stop
                    // immediately rather than repeat the same denial N times.
                    return ObservationFetch(out, lastErrorCode = r.code)
                }
                is ChunkResult.OtherError -> lastErrorCode = r.code
            }
        }
        return ObservationFetch(out, lastErrorCode, retryAfterSeconds)
    }

    /** Integer ceiling division for positive [numerator]/[denominator]. */
    private fun ceilDiv(numerator: Long, denominator: Long): Int =
        ((numerator + denominator - 1) / denominator).toInt()

    internal fun parseObservations(body: String, callsignPrefix: String): List<Observation> {
        val root = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return root.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val callsign = o["callsign"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (callsignPrefix.isNotBlank() && !callsign.startsWith(callsignPrefix, ignoreCase = true)) {
                return@mapNotNull null
            }
            val dep = o["estDepartureAirport"]?.jsonPrimitive?.contentOrNull?.trim()
            val arr = o["estArrivalAirport"]?.jsonPrimitive?.contentOrNull?.trim()
            val first = o["firstSeen"]?.jsonPrimitive?.longOrNull
            val last = o["lastSeen"]?.jsonPrimitive?.longOrNull
            if (dep.isNullOrBlank() || arr.isNullOrBlank() || first == null || last == null) {
                return@mapNotNull null
            }
            if (last <= first) return@mapNotNull null
            Observation(
                callsign = callsign,
                departureIcao = dep.uppercase(),
                arrivalIcao = arr.uppercase(),
                firstSeen = Instant.ofEpochSecond(first),
                lastSeen = Instant.ofEpochSecond(last),
            )
        }
    }

    // ------------------------------------------------------------------ aggregation

    /**
     * One entry of the derived timetable: "on this weekday this route was typically observed
     * leaving at this local time, taking about this long".
     */
    internal data class ObservedService(
        val origin: Airport,
        val destination: Airport,
        val weekday: DayOfWeek,
        val departureLocal: LocalTime,
        val blockMinutes: Long,
        val sampleCount: Int,
        val callsign: String,
    ) {
        /**
         * @param note renders the "observed on N days" caption. It is passed in rather than looked
         *   up here because this is a plain nested value class with no access to resources — and
         *   keeping it that way is what lets the aggregation stay free of Android.
         */
        fun projectOnto(from: LocalDate, to: LocalDate, note: (Int) -> String): List<Flight> {
            if (to.isBefore(from)) return emptyList()
            val out = mutableListOf<Flight>()
            var date = from
            while (!date.isAfter(to)) {
                if (date.dayOfWeek == weekday) {
                    val departure = ZonedDateTime.of(date, departureLocal, ZoneId.of(origin.timeZoneId))
                    out += Flight(
                        flightNumber = callsign,
                        airline = "Condor",
                        airlineCode = "DE",
                        origin = origin,
                        destination = destination,
                        departure = departure.toInstant(),
                        arrival = departure.toInstant().plus(blockMinutes, ChronoUnit.MINUTES),
                        isDirect = true,
                        provenance = DataProvenance.SCHEDULE,
                        availabilityNote = note(sampleCount),
                    )
                }
                date = date.plusDays(1)
            }
            return out
        }
    }

    /**
     * Groups observations by (weekday, route) and takes the **median** departure time and block
     * time of each group. The median rather than the mean because a single heavily delayed flight
     * would otherwise drag the whole entry off its real slot.
     */
    internal suspend fun buildTimetable(
        observations: List<Observation>,
        home: Airport,
        outbound: Boolean,
    ): List<ObservedService> {
        val services = mutableListOf<ObservedService>()

        // The API call is already scoped to the home airport, so the "other" end is whichever
        // side of the observation is not home.
        val homeZone = ZoneId.of(home.timeZoneId)
        val grouped = observations.groupBy { obs ->
            val otherIcao = if (outbound) obs.arrivalIcao else obs.departureIcao
            val reference = if (outbound) obs.firstSeen else obs.lastSeen
            otherIcao to reference.atZone(homeZone).dayOfWeek
        }

        for ((key, group) in grouped) {
            val (otherIcao, weekday) = key
            val other = airportCatalog.byIcao(otherIcao) ?: continue
            if (other.iata == home.iata) continue

            val origin = if (outbound) home else other
            val destination = if (outbound) other else home
            val zone = ZoneId.of(origin.timeZoneId)

            val departureSeconds = group.map { it.firstSeen.atZone(zone).toLocalTime().toSecondOfDay().toLong() }
            val blocks = group.map { Duration.between(it.firstSeen, it.lastSeen).toMinutes() }

            services += ObservedService(
                origin = origin,
                destination = destination,
                weekday = weekday,
                departureLocal = LocalTime.ofSecondOfDay(median(departureSeconds).coerceIn(0, 86_399)),
                blockMinutes = median(blocks).coerceAtLeast(20L),
                sampleCount = group.size,
                callsign = group.first().callsign,
            )
        }
        return services
    }

    internal fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    // ------------------------------------------------------------------ auth

    internal sealed interface TokenResult {
        data class Success(val token: String) : TokenResult
        data class Failure(val reason: String, val detail: String?) : TokenResult
    }

    /**
     * OAuth2 client-credentials. Optional — without credentials OpenSky serves anonymous requests
     * under much tighter limits — but if credentials *are* configured and rejected, that is
     * reported rather than swallowed.
     *
     * @param forceRefresh skips the cached token even if it looks unexpired — used after a data
     *   request comes back 401, since OpenSky's own guidance is to treat that as "the 30-minute
     *   token just expired", not "the credentials are wrong". The stale token is dropped either
     *   way, so a failed forced refresh can't leave an already-rejected token cached for next time.
     */
    private fun obtainToken(config: OpenSkyConfig, forceRefresh: Boolean = false): TokenResult {
        if (forceRefresh) {
            cachedToken = null
        } else {
            cachedToken?.let { if (now().isBefore(tokenExpiry)) return TokenResult.Success(it) }
        }

        val form = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", config.clientId)
            .add("client_secret", config.clientSecret)
            .build()

        val request = Request.Builder()
            .url(config.tokenUrl)
            .post(form)
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty().take(300)
                val reason = when (response.code) {
                    400, 401 -> strings.get(R.string.src_opensky_token_rejected, response.code)
                    404 -> strings.get(R.string.src_opensky_token_404)
                    else -> strings.get(R.string.src_opensky_http, response.code)
                }
                return TokenResult.Failure(reason, body.ifBlank { null })
            }

            val body = response.body?.string().orEmpty()
            val obj = json.parseToJsonElement(body) as? JsonObject
                ?: return TokenResult.Failure(strings.get(R.string.src_opensky_token_not_json), body.take(200))
            val token = obj["access_token"]?.jsonPrimitive?.contentOrNull
                ?: return TokenResult.Failure(strings.get(R.string.src_opensky_token_no_access), body.take(200))

            val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 1800L
            cachedToken = token
            // Refresh a minute early so a request never races the expiry.
            tokenExpiry = now().plusSeconds((expiresIn - 60).coerceAtLeast(60))
            return TokenResult.Success(token)
        }
    }

    /**
     * Checks credentials and reachability without pulling a whole timetable: one token request,
     * then a single short window of departures.
     */
    override suspend fun selfTest(): SourceTestResult = withContext(Dispatchers.IO) {
        val config = configProvider()
        when (val s = status()) {
            is SourceStatus.NotConfigured -> return@withContext SourceTestResult.Problem("${s.reason} ${s.howToFix}")
            is SourceStatus.Unavailable -> return@withContext SourceTestResult.Problem(s.reason)
            SourceStatus.Ready -> Unit
        }

        try {
            val authenticated = config.clientId.isNotBlank()
            val token = if (authenticated) {
                when (val auth = obtainToken(config)) {
                    is TokenResult.Success -> auth.token
                    // auth.detail is OpenSky's own error body — usually the one line that actually
                    // says what's wrong ("invalid_client", "unauthorized_client", …), so it is
                    // never dropped here the way it used to be.
                    is TokenResult.Failure -> return@withContext SourceTestResult.Problem(
                        strings.get(R.string.src_opensky_auth_failed, auth.reason) +
                            (auth.detail?.let { strings.get(R.string.src_opensky_test_detail, it) } ?: ""),
                    )
                }
            } else {
                null
            }

            // A day ending yesterday — recent enough to hold data, small enough to be cheap (and,
            // staying under 24 hours, in OpenSky's cheapest credit bracket for this endpoint).
            val end = now().epochSecond - 24L * 3600L
            val begin = end - 24L * 3600L
            val url = "${config.baseUrl.trimEnd('/')}/flights/departure" +
                "?airport=${config.homeIcao}&begin=$begin&end=$end"

            fun call(bearer: String?): Response {
                val builder = Request.Builder().url(url).get().addHeader("Accept", "application/json")
                if (bearer != null) builder.addHeader("Authorization", "Bearer $bearer")
                return client.newCall(builder.build()).execute()
            }

            var effectiveToken = token
            var response = call(effectiveToken)
            if (authenticated && (response.code == 401 || response.code == 403)) {
                // Same reasoning as fetchObservations: a 401 here most plausibly means the token
                // expired in the moment between being issued and being used, so refresh once and
                // retry before reporting a denial. Only close the original response once a fresh
                // token actually came back — closing it first and then failing to refresh would
                // leave the denial's own response body unreadable below.
                when (val refreshed = obtainToken(config, forceRefresh = true)) {
                    is TokenResult.Success -> {
                        response.close()
                        effectiveToken = refreshed.token
                        response = call(effectiveToken)
                    }
                    is TokenResult.Failure -> Unit // keep the original response/denial
                }
            }

            response.use {
                val authNote = strings.get(
                    if (authenticated) R.string.src_auth_signed_in else R.string.src_auth_anonymous,
                )
                return@withContext when (response.code) {
                    200 -> {
                        val body = response.body?.string().orEmpty()
                        val all = parseObservations(body, callsignPrefix = "")
                        val mine = parseObservations(body, config.callsignPrefix)
                        SourceTestResult.Ok(
                            strings.get(
                                R.string.src_opensky_test_ok,
                                authNote,
                                all.size,
                                config.homeIcao,
                                mine.size,
                                config.callsignPrefix,
                            ),
                        )
                    }
                    404 -> SourceTestResult.Ok(
                        strings.get(R.string.src_opensky_test_nodata, authNote),
                    )
                    401, 403 -> SourceTestResult.Problem(
                        strings.get(R.string.src_opensky_test_denied, response.code) +
                            responseDetailSuffix(response),
                    )
                    429 -> {
                        val retryAfter = response.header("X-Rate-Limit-Retry-After-Seconds")?.toLongOrNull()
                        SourceTestResult.Problem(
                            if (retryAfter != null) {
                                strings.get(R.string.src_opensky_rate_limited_retry, retryAfter)
                            } else {
                                strings.get(R.string.src_opensky_rate_limited)
                            },
                        )
                    }
                    else -> SourceTestResult.Problem(
                        strings.get(R.string.src_opensky_http, response.code) + responseDetailSuffix(response),
                    )
                }
            }
        } catch (e: IOException) {
            SourceTestResult.Problem(
                strings.get(R.string.src_test_unreachable, e.message ?: strings.get(R.string.src_network_error)),
            )
        } catch (e: Exception) {
            SourceTestResult.Problem(
                strings.get(R.string.src_test_failed, e.message ?: e::class.simpleName.orEmpty()),
            )
        }
    }

    /** " — <first 300 chars of the response body>", or "" if there was none worth showing. */
    private fun responseDetailSuffix(response: Response): String {
        val body = runCatching { response.body?.string() }.getOrNull()?.trim()?.take(300)
        return if (body.isNullOrBlank()) "" else strings.get(R.string.src_opensky_test_detail, body)
    }

    companion object {
        /**
         * Kept under 24 hours so every chunk request lands in OpenSky's cheapest `flights` credit
         * bracket (4 credits) rather than the much steeper one a request spanning a second
         * calendar day falls into (30+) — see the class doc. The 4-hour margin below 24h absorbs
         * clock drift and the time this loop itself takes to run.
         */
        private const val CHUNK_WINDOW_SECONDS = 20L * 3600L

        /** Used only as a departure-endpoint 400 fallback — see the class doc. */
        private const val WIDE_FALLBACK_WINDOW_SECONDS = 2L * 24L * 3600L

        /**
         * Hard ceiling on requests per refresh, per endpoint (departure and arrival are each
         * fetched separately, so a full [fetchAndBuildTimetable] costs at most twice this many
         * requests). Settings caps [OpenSkyConfig.lookbackWeeks] at 4 (see `SettingsScreen`'s
         * `NumberField` for it), which needs ~34 chunks per endpoint at [CHUNK_WINDOW_SECONDS]; 40
         * covers that with headroom. At 4 credits per chunk (see the class doc), a full fetch at
         * the Settings maximum costs at most 2 × 40 × 4 = 320 credits — under the anonymous tier's
         * 400-credit daily quota in a *single* fetch, with [FETCH_COOLDOWN] on top keeping repeat
         * fetches rare. This used to be 110 with no lookback cap below 12 weeks, which needed ~101
         * chunks per endpoint at that maximum and could burn the entire daily quota — and take long
         * enough as 100+ sequential blocking requests to look like an endless spinner — in one
         * single search. That combination is the bug this cap and the lowered Settings maximum now
         * exist to prevent.
         */
        private const val MAX_CHUNKS = 40

        /**
         * The observed timetable barely moves week to week, so there is no reason to repeat the
         * request burst in [fetchAndBuildTimetable] on every refresh trigger (cold start, weekend
         * navigation, pull-to-refresh) — a single session can easily call [search] a dozen times.
         * Six hours keeps the data fresh across a day of use while cutting the *effective* request
         * volume by roughly that same factor.
         */
        private val FETCH_COOLDOWN: Duration = Duration.ofHours(6)
    }
}

/**
 * OpenSky settings. Defaults are the documented public endpoints; credentials are optional and,
 * like every other secret in this app, stay on the device.
 */
data class OpenSkyConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://opensky-network.org/api",
    val tokenUrl: String =
        "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token",
    val clientId: String = "",
    val clientSecret: String = "",
    /** ICAO code of the home airport — OpenSky speaks ICAO, not IATA. Frankfurt is EDDF. */
    val homeIcao: String = "EDDF",
    /** Condor's ICAO airline designator; its callsigns start with this. */
    val callsignPrefix: String = "CFG",
    // Lower than the OpenSky endpoints could technically support (up to 12 weeks) on purpose: this
    // is now a de-prioritized fallback rather than the app's default live source (see AeroDataBox
    // and the trust order in AppContainer), so its default should ask for as little quota as still
    // gives a usable weekday/route sample — 2 weeks is enough to catch most recurring routes.
    val lookbackWeeks: Int = 2,
)
