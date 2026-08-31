package com.condorino.weekend.data.source

import com.condorino.weekend.R
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.CommercialPriceQuote
import com.condorino.weekend.domain.model.Money
import kotlinx.coroutines.CancellationException
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
    /** The one RapidAPI key shared by every RapidAPI-hosted source — see [PreferencesStore.rapidApiKey]. */
    private val apiKeyProvider: suspend () -> String,
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
            apiKeyProvider().isBlank() -> SourceStatus.NotConfigured(
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
            .addHeader("X-RapidAPI-Key", apiKeyProvider().trim())
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
                    ?: return@withContext apiErrorMessage(body)
                        // The API answered 200 but its body is an error envelope, not an
                        // itinerary — its own words explain this far better than any guess the
                        // app could make about the field mapping.
                        ?.let { CommercialPriceResult.Failure(strings.get(R.string.src_google_flights_api_error, it)) }
                        ?: CommercialPriceResult.Failure(
                            strings.get(R.string.src_google_flights_unmapped),
                            diagnoseMappingFailure(body, config),
                        )
                CommercialPriceResult.Success(quote)
            }
        } catch (e: IOException) {
            CommercialPriceResult.Failure(strings.get(R.string.src_google_flights_offline), e.message)
        } catch (e: CancellationException) {
            // Cancellation is not a failure: it means the caller went away (a new search
            // superseded this one, or the screen was left). Reporting it as an error would
            // put a spurious message on screen and hide the cancellation from the caller.
            throw e
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
                result.userMessage.withDetail(result.technicalDetail),
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

    /**
     * Called only when [mapQuote] returns null, to say *where* it stopped and what was actually
     * there instead of leaving a bare "no usable price" — the field mapping is an unverified guess
     * (see the class doc), so the fastest way to a correct one is showing exactly what the real
     * response looked like at the point resolution gave up. Never asserts what the right field name
     * *should* be, only reports what this specific response contained.
     */
    internal fun diagnoseMappingFailure(body: String, config: GoogleFlightsApiConfig): String {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return "response was not valid JSON"

        var current: JsonElement = root
        for (segment in config.itemsPath.split('.').filter { it.isNotBlank() }) {
            val obj = current as? JsonObject
            val next = obj?.get(segment)
            if (next == null) {
                return "items path \"${config.itemsPath}\" did not resolve past \"$segment\"; " +
                    "keys there: ${obj?.keys?.joinToString(", ") ?: "(not an object)"}"
            }
            current = next
        }

        val item = when (current) {
            is JsonArray -> current.firstOrNull() as? JsonObject ?: return if (current.isEmpty()) {
                "items path \"${config.itemsPath}\" resolved to an empty list"
            } else {
                "the first entry at \"${config.itemsPath}\" isn't an object"
            }
            is JsonObject -> current
            else -> return "\"${config.itemsPath}\" did not resolve to an object or a list of objects"
        }

        return "no numeric \"${config.fieldPrice}\" field in the resolved item; keys there: " +
            item.keys.joinToString(", ").ifBlank { "(none)" }
    }

    /**
     * The API's *own* explanation of a failure, when a 200 response body turns out to be an error
     * envelope rather than data — RapidAPI listings commonly answer this way ("You are not
     * subscribed to this API", "Invalid date format", a quota notice) instead of using an HTTP
     * status code, which is why an otherwise-successful request can still carry nothing usable.
     *
     * Only consulted once mapping has already failed, so it can never mistake a real itinerary for
     * an error: at that point the choice is between the API's own sentence and a guess about field
     * names, and the API's sentence is almost always the actual answer.
     *
     * Null when the body carries no such message — then the field mapping really is the thing to
     * look at, and [diagnoseMappingFailure] describes it.
     */
    internal fun apiErrorMessage(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val message = ERROR_MESSAGE_KEYS.firstNotNullOfOrNull { key -> flattenMessage(root[key]) }
            ?: return null
        // A body that says "ok" is not explaining a failure — it mapped badly for some other
        // reason, and the field-mapping diagnosis is the more useful answer there.
        return message.takeIf { it.lowercase() !in SUCCESS_WORDS }
    }

    /**
     * Pulls readable text out of whatever shape an error field takes. A plain string is the common
     * case, but validation errors arrive as a list, and some gateways nest the sentence one level
     * down ({"message": {"detail": "..."}}) — reading only a string meant those responses fell
     * through to a field-mapping diagnosis that described the wrong problem entirely.
     */
    private fun flattenMessage(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        is JsonArray -> element.mapNotNull { flattenMessage(it) }.joinToString("; ").takeIf { it.isNotBlank() }
        is JsonObject -> ERROR_MESSAGE_KEYS.firstNotNullOfOrNull { flattenMessage(element[it]) }
            // Nothing under a name we know: take the first readable value rather than give up,
            // since any sentence the API sent beats none at all.
            ?: element.values.firstNotNullOfOrNull { child -> (child as? JsonPrimitive)?.let { flattenMessage(it) } }
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

    /**
     * A plain number, but also tolerant of two shapes common across travel-price APIs that a bare
     * [JsonPrimitive] cast would miss: a formatted currency string ("€312", "$1,234.50") and a price
     * wrapped in its own object ({"amount": 312, "currency": "EUR"}) — tried under a short list of
     * likely sub-field names rather than assumed to be any one of them.
     */
    private fun JsonObject.number(key: String): Double? = when (val element = at(key)) {
        null -> null
        is JsonPrimitive -> element.doubleOrNull
            ?: element.contentOrNull?.let { PRICE_DIGITS.find(it.replace(",", ""))?.value?.toDoubleOrNull() }
        is JsonObject -> PRICE_SUBFIELD_KEYS.firstNotNullOfOrNull { element.number(it) }
        is JsonArray -> element.firstNotNullOfOrNull { (it as? JsonPrimitive)?.doubleOrNull }
    }

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
        /** Where an API-level error envelope tends to put its explanation, most specific first. */
        val ERROR_MESSAGE_KEYS = listOf("message", "error", "detail", "errors", "errorMessage", "description")

        /** Words that mean "this worked", so they never get reported to the user as a failure. */
        val SUCCESS_WORDS = setOf("ok", "success", "successful", "true", "done")

        /** First run of digits (thousands separators stripped) — for a formatted price string. */
        val PRICE_DIGITS = Regex("""\d+(?:\.\d+)?""")

        /** Tried in order when a price field resolves to an object rather than a plain number. */
        val PRICE_SUBFIELD_KEYS = listOf("amount", "total", "value", "raw")

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
    val path: String = "api/v1/searchFlights",
    val departureIdParam: String = "departure_id",
    val arrivalIdParam: String = "arrival_id",
    val outboundDateParam: String = "outbound_date",
    val returnDateParam: String = "return_date",
    val adultsParam: String = "adults",
    val currencyParam: String = "currency",
    val travelClassParam: String = "travel_class",
    /**
     * Named, not numbered. These used to default to Google Flights' own numeric codes ("1", "3"),
     * which this listing rejects outright — it answered, in as many words, *"Travel class must be
     * one of: ECONOMY, PREMIUM_ECONOMY, BUSINESS, or FIRST"*. That reply is the contract, so it is
     * what the defaults follow now.
     */
    val travelClassEconomyValue: String = "ECONOMY",
    val travelClassBusinessValue: String = "BUSINESS",
    /** Dotted path to the cheapest itinerary (object) or a list of them, e.g. `data.itineraries.topFlights`. */
    val itemsPath: String = "data.itineraries.topFlights",
    val fieldPrice: String = "price",
    val fieldAirline: String = "airlines",
    /** Left blank on purpose — see the class doc. */
    val fieldCarryOnIncluded: String = "",
    val fieldCarryOnNote: String = "",
)
