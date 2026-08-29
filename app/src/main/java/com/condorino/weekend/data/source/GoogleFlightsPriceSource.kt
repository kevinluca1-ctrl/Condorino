package com.condorino.weekend.data.source

import com.condorino.weekend.R
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.CommercialPriceQuote
import com.condorino.weekend.domain.model.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/**
 * ## Status of this data source — read before using
 *
 * This talks to the "Google Flights" API published on RapidAPI by DataCrawler (host
 * `google-flights2.p.rapidapi.com`, a `searchFlights` endpoint returning a cheapest-itinerary
 * price). That listing is where the endpoint path, parameter names and response field names below
 * come from — but its full request/response contract sits behind a RapidAPI subscription and its
 * playground page could not be reached from where this was written (blocked by network egress),
 * so **every field name and value below is a best-effort reconstruction from public search-engine
 * snippets, not a verified contract** — the same situation the Condor Developer API source was in,
 * and handled the same way: nothing is hard-coded as fact. [GoogleFlightsApiConfig]'s defaults are
 * a working starting point; if your actual response doesn't match, correct the field names in
 * Settings → Google Flights from what you see in RapidAPI's own "Test Endpoint" panel — nothing
 * else in the app needs to change (see [mapQuote], the single place this is interpreted).
 *
 * The baggage/carry-on fields in particular had no confirmed example in the researched snippets at
 * all, so their defaults are blank on purpose: this source reports "not reported by source" for
 * carry-on until you fill those two field names in yourself.
 *
 * Unlike [FlightDataSource], this does not search a date range for a timetable — it prices one
 * already-decided trip's exact dates on demand (see [CommercialPriceSource]).
 */
class GoogleFlightsPriceSource(
    private val client: OkHttpClient,
    private val configProvider: suspend () -> GoogleFlightsApiConfig,
    override val strings: SourceStrings,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val now: () -> Instant = { Instant.now() },
    /** Always "https" in production; overridable only so tests can point this at a plain-HTTP
     *  [okhttp3.mockwebserver.MockWebServer] without standing up TLS just to exercise HTTP codes. */
    private val scheme: String = "https",
) : CommercialPriceSource {

    override val id: String = "google-flights"
    override val displayName: String get() = strings.get(R.string.src_google_flights_name)

    override suspend fun status(): SourceStatus {
        val config = configProvider()
        return when {
            !config.enabled -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_google_flights_disabled),
                howToFix = strings.get(R.string.src_google_flights_disabled_fix),
            )
            config.apiKey.isBlank() -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_google_flights_no_key),
                howToFix = strings.get(R.string.src_google_flights_no_key_fix),
            )
            config.apiHost.isBlank() || config.path.isBlank() -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_google_flights_no_host),
                howToFix = strings.get(R.string.src_google_flights_no_host_fix),
            )
            else -> SourceStatus.Ready
        }
    }

    override suspend fun quote(
        origin: Airport,
        destination: Airport,
        outboundDate: LocalDate,
        returnDate: LocalDate,
        cabin: Cabin,
    ): CommercialPriceResult = withContext(Dispatchers.IO) {
        val config = configProvider()
        when (val s = status()) {
            is SourceStatus.NotConfigured -> return@withContext CommercialPriceResult.NotConfigured(s.reason, s.howToFix)
            is SourceStatus.Unavailable -> return@withContext CommercialPriceResult.Failure(s.reason)
            SourceStatus.Ready -> Unit
        }

        val url = buildUrl(config, origin.iata, destination.iata, outboundDate, returnDate, cabin)
        val request = Request.Builder().url(url).get()
            .addHeader("Accept", "application/json")
            .addHeader("X-RapidAPI-Key", config.apiKey.trim())
            .addHeader("X-RapidAPI-Host", config.apiHost.trim())
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> return@withContext CommercialPriceResult.Failure(
                        strings.get(R.string.src_google_flights_denied),
                        "HTTP ${response.code}",
                    )
                    response.code == 429 -> return@withContext CommercialPriceResult.Failure(
                        strings.get(R.string.src_google_flights_rate_limited),
                        "HTTP 429",
                    )
                    !response.isSuccessful -> return@withContext CommercialPriceResult.Failure(
                        strings.get(R.string.src_google_flights_http, response.code),
                        response.message,
                    )
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext CommercialPriceResult.Failure(strings.get(R.string.src_google_flights_empty))
                }
                val quote = mapQuote(body, config, destination.iata, cabin)
                    ?: return@withContext CommercialPriceResult.Failure(strings.get(R.string.src_google_flights_unmapped))
                CommercialPriceResult.Success(quote)
            }
        } catch (e: IOException) {
            CommercialPriceResult.Failure(strings.get(R.string.src_google_flights_offline), e.message)
        } catch (e: Exception) {
            CommercialPriceResult.Failure(strings.get(R.string.src_google_flights_parse_failed), e.message)
        }
    }

    override suspend fun selfTest(): SourceTestResult = withContext(Dispatchers.IO) {
        // A real, cheap search that doesn't depend on any particular trip being on screen: Frankfurt
        // to Munich, two weeks out, is a route that reliably has commercial fares, so a successful
        // parse there is a good stand-in for "credentials and field mapping work".
        val start = LocalDate.now().plusDays(14)
        when (val result = quote(Airport.FRANKFURT, MUNICH_FOR_SELF_TEST, start, start.plusDays(2), Cabin.ECONOMY)) {
            is CommercialPriceResult.Success -> SourceTestResult.Ok(
                strings.get(
                    R.string.src_google_flights_test_ok,
                    result.quote.roundTripPrice.format(),
                    result.quote.airline ?: strings.get(R.string.src_unknown),
                ),
            )
            is CommercialPriceResult.NotConfigured -> SourceTestResult.Problem("${result.reason} ${result.howToFix}")
            is CommercialPriceResult.Failure -> SourceTestResult.Problem(
                result.userMessage + (result.technicalDetail?.let { " ($it)" } ?: ""),
            )
        }
    }

    private fun buildUrl(
        config: GoogleFlightsApiConfig,
        originIata: String,
        destinationIata: String,
        outboundDate: LocalDate,
        returnDate: LocalDate,
        cabin: Cabin,
    ): String = buildString {
        append(scheme).append("://").append(config.apiHost.trim())
        append('/').append(config.path.trim().trimStart('/'))
        append('?').append(config.departureIdParam).append('=').append(originIata)
        append('&').append(config.arrivalIdParam).append('=').append(destinationIata)
        append('&').append(config.outboundDateParam).append('=').append(outboundDate)
        append('&').append(config.returnDateParam).append('=').append(returnDate)
        append('&').append(config.adultsParam).append("=1")
        if (config.currencyParam.isNotBlank()) {
            append('&').append(config.currencyParam).append('=').append(Money.CURRENCY)
        }
        if (config.travelClassParam.isNotBlank()) {
            val value = if (cabin == Cabin.BUSINESS) config.travelClassBusinessValue else config.travelClassEconomyValue
            if (value.isNotBlank()) append('&').append(config.travelClassParam).append('=').append(value)
        }
    }

    /**
     * Generic mapping from an unknown JSON envelope onto [CommercialPriceQuote] — same reasoning
     * as [CondorDeveloperApiDataSource.mapFlights]: field names come from [GoogleFlightsApiConfig],
     * which the user corrects from the real response if this source's guessed defaults are wrong.
     * Never fabricates a value a field name failed to resolve; a missing price fails the whole
     * quote rather than showing a wrong or zero one.
     */
    internal fun mapQuote(
        body: String,
        config: GoogleFlightsApiConfig,
        destinationIata: String,
        cabin: Cabin,
    ): CommercialPriceQuote? {
        val root = json.parseToJsonElement(body)
        val item: JsonObject = when {
            config.itemsPath.isBlank() -> root as? JsonObject ?: return null
            else -> when (val resolved = resolvePath(root, config.itemsPath)) {
                is JsonArray -> resolved.firstOrNull() as? JsonObject ?: return null
                is JsonObject -> resolved
                else -> return null
            }
        }

        val priceCents = item.number(config.fieldPrice)?.let { Math.round(it * 100) } ?: return null

        return CommercialPriceQuote(
            destinationIata = destinationIata,
            cabin = cabin,
            roundTripPrice = Money(priceCents),
            carryOnIncluded = item.bool(config.fieldCarryOnIncluded),
            carryOnNote = item.str(config.fieldCarryOnNote),
            airline = item.str(config.fieldAirline),
            retrievedAt = now(),
        )
    }

    private fun resolvePath(root: JsonElement, path: String): JsonElement? {
        var current: JsonElement = root
        for (segment in path.split('.').filter { it.isNotBlank() }) {
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }

    private fun JsonObject.at(key: String): JsonElement? {
        if (key.isBlank()) return null
        return if (key.contains('.')) resolvePath(this, key) else this[key]
    }

    private fun JsonObject.str(key: String): String? {
        val element = at(key) ?: return null
        // A source might report airlines as an array ("British Airways", "Iberia") rather than one
        // string; joining is more useful here than silently taking just the first.
        return when (element) {
            is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }
                .filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
            else -> element.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        }
    }

    private fun JsonObject.number(key: String): Double? = (at(key) as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.bool(key: String): Boolean? {
        val element = at(key) as? JsonPrimitive ?: return null
        element.booleanOrNull?.let { return it }
        // Some APIs report "1"/"0" or "yes"/"no" rather than a real JSON boolean.
        return when (element.contentOrNull?.lowercase()) {
            "true", "yes", "1", "included" -> true
            "false", "no", "0", "not included", "not_included" -> false
            else -> null
        }
    }

    private companion object {
        /** A stand-in destination for [selfTest]; the public reference resolves MUC reliably. */
        val MUNICH_FOR_SELF_TEST = Airport(
            iata = "MUC",
            name = "Munich Airport",
            city = "Munich",
            country = "Germany",
            countryCode = "DE",
            timeZoneId = "Europe/Berlin",
        )
    }
}

/**
 * User-editable description of the Google Flights (RapidAPI) contract. Defaults are a best-effort
 * reconstruction from public documentation snippets, **not a verified contract** — see the class
 * doc on [GoogleFlightsPriceSource]. The carry-on field names default to blank because no baggage
 * field was found in the researched snippets at all; fill them in from the real response.
 */
data class GoogleFlightsApiConfig(
    val enabled: Boolean = false,
    val apiHost: String = "google-flights2.p.rapidapi.com",
    val apiKey: String = "",
    val path: String = "api/v1/searchFlights",
    val departureIdParam: String = "departure_id",
    val arrivalIdParam: String = "arrival_id",
    val outboundDateParam: String = "outbound_date",
    val returnDateParam: String = "return_date",
    val adultsParam: String = "adults",
    val currencyParam: String = "currency",
    val travelClassParam: String = "travel_class",
    val travelClassEconomyValue: String = "1",
    val travelClassBusinessValue: String = "3",
    /** Dotted path to the cheapest itinerary (object) or a list of them, e.g. `data.itineraries.topFlights`. */
    val itemsPath: String = "data.itineraries.topFlights",
    val fieldPrice: String = "price",
    val fieldAirline: String = "airlines",
    /** Left blank on purpose — see the class doc. */
    val fieldCarryOnIncluded: String = "",
    val fieldCarryOnNote: String = "",
)
