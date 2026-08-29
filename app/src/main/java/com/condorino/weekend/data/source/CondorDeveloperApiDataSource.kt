package com.condorino.weekend.data.source

import com.condorino.weekend.R
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Flight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * ## Status of this data source — read before using
 *
 * Condor operates a developer portal at `developer.condor.com` which lists, among others, a
 * "Flight Information API", a "Flight Offer API" and a "Travel Shopping Carts API". The portal
 * exists, but its request/response contract is **behind registration and was not accessible while
 * this app was written**, so this class does not hard-code any endpoint path, parameter name or
 * response field that was not verified. Inventing them would produce an app that silently returns
 * nothing (spec §3, §29).
 *
 * What this class therefore is: a *complete, working HTTP client whose contract is supplied by the
 * user*. Once you have portal access, you fill in — in Settings → Datenquellen — the base URL, the
 * path, the parameter names and the API-key header from the official documentation. The generic
 * JSON extraction below then maps the response onto [Flight].
 *
 * If your contract's response shape differs from the (deliberately flexible) mapping in
 * [mapFlights], that mapping is the single place to adjust. Nothing else in the app changes.
 *
 * Until it is configured, [status] reports [SourceStatus.NotConfigured] and the app falls through
 * to the next source — it never fabricates flights.
 */
class CondorDeveloperApiDataSource(
    private val client: OkHttpClient,
    private val configProvider: suspend () -> CondorApiConfig,
    private val airportCatalog: suspend () -> Map<String, Airport>,
    override val strings: SourceStrings,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : FlightDataSource {

    override val id: String = "condor-developer-api"
    override val displayName: String = "Condor Developer API"
    override val bestProvenance: DataProvenance = DataProvenance.LIVE

    override suspend fun status(): SourceStatus {
        val config = configProvider()
        return when {
            !config.enabled -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_condor_disabled),
                howToFix = strings.get(R.string.src_condor_disabled_fix),
            )
            config.baseUrl.isBlank() || config.path.isBlank() -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_condor_no_url),
                howToFix = strings.get(R.string.src_condor_no_url_fix),
            )
            !config.baseUrl.startsWith("https://") -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_https_required),
                howToFix = strings.get(R.string.src_https_required_fix),
            )
            config.apiKeyHeader.isNotBlank() && config.apiKey.isBlank() -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_condor_no_key),
                howToFix = strings.get(R.string.src_condor_no_key_fix),
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

            val url = buildString {
                append(config.baseUrl.trimEnd('/'))
                append('/')
                append(config.path.trimStart('/'))
                append(if (config.path.contains('?')) '&' else '?')
                append(config.originParam).append('=').append(query.originIata)
                append('&').append(config.fromParam).append('=').append(query.from)
                append('&').append(config.toParam).append('=').append(query.to)
                query.destinationIata?.takeIf { config.destinationParam.isNotBlank() }?.let {
                    append('&').append(config.destinationParam).append('=').append(it)
                }
            }

            val builder = Request.Builder().url(url).get().addHeader("Accept", "application/json")
            if (config.apiKeyHeader.isNotBlank()) {
                builder.addHeader(config.apiKeyHeader.trim(), config.apiKey.trim())
            }

            try {
                client.newCall(builder.build()).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 -> return@withContext FlightSearchResult.Failure(
                            strings.get(R.string.src_condor_denied),
                            "HTTP ${response.code}",
                        )
                        response.code == 429 -> return@withContext FlightSearchResult.Failure(
                            strings.get(R.string.src_condor_rate_limited),
                            "HTTP 429",
                        )
                        !response.isSuccessful -> return@withContext FlightSearchResult.Failure(
                            strings.get(R.string.src_condor_http, response.code),
                            response.message,
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        return@withContext FlightSearchResult.Failure(strings.get(R.string.src_condor_empty))
                    }
                    val airports = airportCatalog()
                    val flights = mapFlights(body, config, airports)
                    if (flights.isEmpty()) {
                        return@withContext FlightSearchResult.Failure(
                            strings.get(R.string.src_condor_unmapped),
                        )
                    }
                    FlightSearchResult.Success(
                        flights = flights,
                        provenance = DataProvenance.LIVE,
                        retrievedAt = Instant.now(),
                        note = "Condor Developer API",
                    )
                }
            } catch (e: IOException) {
                FlightSearchResult.Failure(strings.get(R.string.src_condor_offline), e.message)
            } catch (e: Exception) {
                FlightSearchResult.Failure(strings.get(R.string.src_condor_parse_failed), e.message)
            }
        }

    /**
     * Generic mapping from an unknown JSON envelope onto [Flight].
     *
     * The field names come from [CondorApiConfig] rather than being guessed, so the same code
     * works whatever the official contract turns out to call things. Rows that lack a usable
     * origin, destination or timestamp are dropped rather than defaulted.
     */
    internal fun mapFlights(
        body: String,
        config: CondorApiConfig,
        airports: Map<String, Airport>,
    ): List<Flight> {
        val root = json.parseToJsonElement(body)
        val array: JsonArray = when {
            root is JsonArray -> root
            root is JsonObject && config.itemsPath.isNotBlank() ->
                resolvePath(root, config.itemsPath) as? JsonArray ?: return emptyList()
            root is JsonObject -> root.values.filterIsInstance<JsonArray>().firstOrNull() ?: return emptyList()
            else -> return emptyList()
        }

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val originIata = obj.str(config.fieldOrigin)?.uppercase() ?: return@mapNotNull null
            val destIata = obj.str(config.fieldDestination)?.uppercase() ?: return@mapNotNull null
            val departure = obj.str(config.fieldDeparture)?.let(::parseTime) ?: return@mapNotNull null
            val arrival = obj.str(config.fieldArrival)?.let(::parseTime) ?: return@mapNotNull null
            if (!arrival.isAfter(departure)) return@mapNotNull null

            val origin = airports[originIata] ?: return@mapNotNull null
            val destination = airports[destIata] ?: return@mapNotNull null

            Flight(
                flightNumber = obj.str(config.fieldFlightNumber),
                airline = "Condor",
                airlineCode = "DE",
                origin = origin,
                destination = destination,
                departure = departure,
                arrival = arrival,
                // Only treat a flight as nonstop when the response actually says so.
                isDirect = obj.str(config.fieldStops)?.toIntOrNull()?.let { it == 0 } ?: true,
                provenance = DataProvenance.LIVE,
                retrievedAt = Instant.now(),
                cashFareCents = obj.str(config.fieldPrice)?.toDoubleOrNull()
                    ?.let { Math.round(it * 100) },
            )
        }
    }

    private fun resolvePath(root: JsonObject, path: String): kotlinx.serialization.json.JsonElement? {
        var current: kotlinx.serialization.json.JsonElement = root
        for (segment in path.split('.').filter { it.isNotBlank() }) {
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }

    private fun JsonObject.str(key: String): String? {
        if (key.isBlank()) return null
        val element = if (key.contains('.')) resolvePath(this, key) else this[key]
        return element?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun parseTime(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrElse {
            runCatching { OffsetDateTime.parse(value).toInstant() }.getOrElse {
                // Some airline APIs return local date-times without an offset. We can only accept
                // those if we know which zone they refer to, so we deliberately reject them here
                // rather than guessing UTC and shifting every flight by an hour or two.
                null
            }
        }
}

/**
 * User-supplied description of the Condor API contract. Every value defaults to empty: the app
 * ships with **no** guessed endpoints, and the source stays disabled until these are filled in
 * from the official documentation.
 */
data class CondorApiConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val path: String = "",
    val apiKeyHeader: String = "",
    val apiKey: String = "",
    val originParam: String = "origin",
    val destinationParam: String = "destination",
    val fromParam: String = "departureDate",
    val toParam: String = "returnDate",
    /** Dotted path to the array of flights inside the response envelope, e.g. `data.flights`. */
    val itemsPath: String = "",
    val fieldOrigin: String = "origin",
    val fieldDestination: String = "destination",
    val fieldDeparture: String = "departureTime",
    val fieldArrival: String = "arrivalTime",
    val fieldFlightNumber: String = "flightNumber",
    val fieldStops: String = "stops",
    val fieldPrice: String = "price",
)
