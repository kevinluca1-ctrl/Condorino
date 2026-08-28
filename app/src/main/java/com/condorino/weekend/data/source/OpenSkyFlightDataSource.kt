package com.condorino.weekend.data.source

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
 * ## Abgleich mit tatsächlich geflogenen Flügen — OpenSky Network
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
 * Verified against OpenSky's public API documentation:
 *  * `GET {base}/flights/departure?airport={ICAO}&begin={unix}&end={unix}`
 *  * `GET {base}/flights/arrival?airport={ICAO}&begin={unix}&end={unix}`
 *  * response: JSON array of objects with `icao24`, `firstSeen`, `estDepartureAirport`,
 *    `lastSeen`, `estArrivalAirport`, `callsign` (airport codes are **ICAO**, times are Unix
 *    seconds). HTTP 404 means "nothing in this window", not an error.
 *  * anonymous access works but is rate-limited; OAuth2 client-credentials raise the limits.
 *
 * The interval per request is limited by OpenSky, so the lookback is fetched in chunks
 * ([OpenSkyConfig.chunkDays], default 7) and capped.
 */
class OpenSkyFlightDataSource(
    private val client: OkHttpClient,
    private val configProvider: suspend () -> OpenSkyConfig,
    private val airportCatalog: AirportReferenceCatalog,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val now: () -> Instant = { Instant.now() },
) : FlightDataSource {

    override val id: String = "opensky"
    override val displayName: String = "OpenSky Network (beobachtete Flüge)"
    override val bestProvenance: DataProvenance = DataProvenance.SCHEDULE

    private var cachedToken: String? = null
    private var tokenExpiry: Instant = Instant.EPOCH

    override suspend fun status(): SourceStatus {
        val config = configProvider()
        return when {
            !config.enabled -> SourceStatus.NotConfigured(
                reason = "OpenSky-Abgleich ist deaktiviert.",
                howToFix = "In den Einstellungen aktivieren. Funktioniert ohne Konto; " +
                    "mit kostenlosem OpenSky-Zugang sind die Limits höher.",
            )
            config.homeIcao.length != 4 -> SourceStatus.NotConfigured(
                reason = "Für den Heimatflughafen ist kein ICAO-Code hinterlegt.",
                howToFix = "ICAO-Code eintragen (Frankfurt = EDDF).",
            )
            config.clientId.isNotBlank() && config.clientSecret.isBlank() -> SourceStatus.NotConfigured(
                reason = "Zur OpenSky-Client-ID fehlt das Secret.",
                howToFix = "Secret eintragen oder Client-ID leer lassen (anonymer Zugriff).",
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
                    "Heimatflughafen ${config.homeIcao} steht nicht im Flughafen-Referenzdatensatz.",
                )

            try {
                val token = if (config.clientId.isNotBlank()) obtainToken(config) else null

                val departures = fetchObservations(config, token, arrivals = false)
                val arrivals = fetchObservations(config, token, arrivals = true)
                if (departures.isEmpty() && arrivals.isEmpty()) {
                    return@withContext FlightSearchResult.Failure(
                        "OpenSky hat für die letzten ${config.lookbackWeeks} Wochen keine " +
                            "${config.callsignPrefix}-Flüge ab ${config.homeIcao} gemeldet.",
                    )
                }

                val outbound = buildTimetable(departures, home, outbound = true)
                val inbound = buildTimetable(arrivals, home, outbound = false)

                val flights = (outbound + inbound).flatMap { it.projectOnto(query.from, query.to) }
                    .filter { f ->
                        query.destinationIata == null ||
                            f.destination.iata == query.destinationIata ||
                            f.origin.iata == query.destinationIata
                    }

                if (flights.isEmpty()) {
                    return@withContext FlightSearchResult.Failure(
                        "Aus den OpenSky-Beobachtungen ließ sich kein Flugplan für diesen Zeitraum ableiten.",
                    )
                }

                FlightSearchResult.Success(
                    flights = flights,
                    provenance = DataProvenance.SCHEDULE,
                    retrievedAt = now(),
                    note = "OpenSky Network · aus ${departures.size + arrivals.size} tatsächlich " +
                        "beobachteten ${config.callsignPrefix}-Flügen der letzten " +
                        "${config.lookbackWeeks} Wochen abgeleitet",
                )
            } catch (e: IOException) {
                FlightSearchResult.Failure("OpenSky nicht erreichbar – bist du offline?", e.message)
            } catch (e: Exception) {
                FlightSearchResult.Failure("OpenSky-Antwort konnte nicht verarbeitet werden.", e.message)
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

    private suspend fun fetchObservations(
        config: OpenSkyConfig,
        token: String?,
        arrivals: Boolean,
    ): List<Observation> {
        val endpoint = if (arrivals) "arrival" else "departure"
        val nowSeconds = now().epochSecond
        val chunk = config.chunkDays.coerceIn(1, 7) * 24L * 3600L
        val chunks = ((config.lookbackWeeks * 7L * 24L * 3600L) / chunk)
            .toInt().coerceIn(1, MAX_CHUNKS)

        val out = mutableListOf<Observation>()
        for (i in 0 until chunks) {
            val end = nowSeconds - i * chunk
            val begin = end - chunk
            val url = "${config.baseUrl.trimEnd('/')}/flights/$endpoint" +
                "?airport=${config.homeIcao}&begin=$begin&end=$end"

            val builder = Request.Builder().url(url).get().addHeader("Accept", "application/json")
            if (token != null) builder.addHeader("Authorization", "Bearer $token")

            client.newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    // OpenSky answers 404 when it simply has nothing for the window.
                    404 -> return@use
                    // Back off rather than hammering a rate-limited free service.
                    429 -> return out
                    401, 403 -> throw IOException("OpenSky lehnte den Zugriff ab (HTTP ${response.code}).")
                }
                if (!response.isSuccessful) return@use
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use
                out += parseObservations(body, config.callsignPrefix)
            }
        }
        return out
    }

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
        fun projectOnto(from: LocalDate, to: LocalDate): List<Flight> {
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
                        availabilityNote = "beobachtet an $sampleCount ${if (sampleCount == 1) "Tag" else "Tagen"}",
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

    /** OAuth2 client-credentials. Optional: without it OpenSky serves anonymous, tighter limits. */
    private fun obtainToken(config: OpenSkyConfig): String? {
        cachedToken?.let { if (now().isBefore(tokenExpiry)) return it }

        val form = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", config.clientId)
            .add("client_secret", config.clientSecret)
            .build()

        client.newCall(Request.Builder().url(config.tokenUrl).post(form).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            val obj = json.parseToJsonElement(body) as? JsonObject ?: return null
            val token = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 1800L
            cachedToken = token
            // Refresh a minute early so a request never races the expiry.
            tokenExpiry = now().plusSeconds((expiresIn - 60).coerceAtLeast(60))
            return token
        }
    }

    companion object {
        /** Hard ceiling on requests per refresh, so a free service is never hammered. */
        private const val MAX_CHUNKS = 8
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
    val lookbackWeeks: Int = 6,
    val chunkDays: Int = 7,
)
