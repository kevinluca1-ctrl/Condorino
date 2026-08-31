package com.condorino.weekend.data.source

import com.condorino.weekend.R
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.DestinationHighlights
import com.condorino.weekend.domain.model.HighlightCategory
import com.condorino.weekend.domain.model.TravelHighlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant

/**
 * ## Status of this data source — read before using
 *
 * This talks to the **Tripadvisor Scraper** listing on RapidAPI (host
 * `tripadvisor-scraper.p.rapidapi.com`), which is where [TripAdvisorApiConfig.apiHost] points. Its
 * published API reference is the source of the endpoint path and parameter names below: the
 * listing exposes `search`, `list` and `detail` endpoints for hotels, restaurants and attractions,
 * and — this is the part that shapes this class — states that those endpoints "accept a location
 * name, a full TripAdvisor URL, or an entity ID" in their `query` parameter.
 *
 * That single sentence removes a whole request. Earlier versions of this source resolved a city
 * name to TripAdvisor's internal location id first and only then asked for attractions; since the
 * listing endpoint takes the name directly, [TripAdvisorApiConfig.locationSearchPath] now ships
 * **blank** and the lookup step is skipped entirely — halving what one destination costs, which
 * matters on a free plan of 200 requests a month. The two-step path is still implemented and still
 * tested; filling that field in switches it back on, for a host that genuinely needs it.
 *
 * What is *not* settled is the response envelope: the reference documents the parameters exactly
 * but shows a sample body only for a `detail` call, which has no wrapper at all. So
 * [TripAdvisorApiConfig.itemsPath] is the one value still inferred rather than read off the
 * contract, and it defaults to blank — "the response is the array itself". If that turns out to be
 * wrong, the failure is not silent: [diagnoseHighlights] names the keys that actually came back,
 * and Settings → TripAdvisor is where you correct it. The same applies to every field name here
 * ([mapLocationId] and [mapHighlights] are the only two places any of it is interpreted).
 *
 * The category field had no documented example at all, so its default is blank on purpose: every
 * highlight reports [HighlightCategory.OTHER] until you fill that field name in yourself.
 *
 * Queried on demand, one destination at a time — never automatically for every trip or destination
 * on screen, since this is a metered RapidAPI call per lookup and firing it unprompted would burn
 * through a subscription's quota for a card most of it would never be opened.
 */
class TripAdvisorRecommendationSource(
    private val client: OkHttpClient,
    private val configProvider: suspend () -> TripAdvisorApiConfig,
    /** The one RapidAPI key shared by every RapidAPI-hosted source — see [PreferencesStore.rapidApiKey]. */
    private val apiKeyProvider: suspend () -> String,
    override val strings: SourceStrings,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val now: () -> Instant = { Instant.now() },
    /** Always "https" in production; overridable only so tests can point this at a plain-HTTP
     *  [okhttp3.mockwebserver.MockWebServer] without standing up TLS just to exercise HTTP codes. */
    private val scheme: String = "https",
) : TravelRecommendationSource {

    override val id: String = "tripadvisor"
    override val displayName: String get() = strings.get(R.string.src_tripadvisor_name)

    override suspend fun status(): SourceStatus {
        val config = configProvider()
        return when {
            !config.enabled -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_tripadvisor_disabled),
                howToFix = strings.get(R.string.src_tripadvisor_disabled_fix),
            )
            apiKeyProvider().isBlank() -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_tripadvisor_no_key),
                howToFix = strings.get(R.string.src_tripadvisor_no_key_fix),
            )
            config.apiHost.isBlank() || config.highlightsPath.isBlank() ->
                SourceStatus.NotConfigured(
                    reason = strings.get(R.string.src_tripadvisor_no_host),
                    howToFix = strings.get(R.string.src_tripadvisor_no_host_fix),
                )
            else -> SourceStatus.Ready
        }
    }

    override suspend fun highlights(destination: Airport): TravelRecommendationResult =
        withContext(Dispatchers.IO) {
            val config = configProvider()
            when (val s = status()) {
                is SourceStatus.NotConfigured -> return@withContext TravelRecommendationResult.NotConfigured(s.reason, s.howToFix)
                is SourceStatus.Unavailable -> return@withContext TravelRecommendationResult.Failure(s.reason)
                SourceStatus.Ready -> Unit
            }
            val apiKey = apiKeyProvider()

            val host = config.apiHost.trim()
            // The listing accepts a place *name* wherever it accepts an entity id, so the lookup
            // step is optional — and skipping it halves the requests a destination costs, which
            // matters on a free plan of 200 a month. It stays available for a host that needs it:
            // leaving the search path blank means "ask the listing directly".
            val locationId = if (config.locationSearchPath.isBlank()) {
                destination.city.ifBlank { destination.name }
            } else {
                when (val step1 = request(locationSearchUrl(config, destination.city), apiKey, host)) {
                    is StepResult.Body -> mapLocationId(step1.body, config)
                        ?: return@withContext TravelRecommendationResult.Failure(strings.get(R.string.src_tripadvisor_no_location))
                    is StepResult.Failure -> return@withContext step1.result
                }
            }

            val highlightsBody = when (val step2 = request(highlightsUrl(config, locationId), apiKey, host)) {
                is StepResult.Body -> step2.body
                is StepResult.Failure -> return@withContext step2.result
            }
            val highlights = mapHighlights(highlightsBody, config)
            if (highlights.isEmpty()) {
                return@withContext TravelRecommendationResult.Failure(
                    strings.get(R.string.src_tripadvisor_unmapped),
                    // Which of the mapping settings is wrong, rather than leaving the user to
                    // guess — the same treatment the other two RapidAPI sources got.
                    diagnoseHighlights(highlightsBody, config),
                )
            }

            TravelRecommendationResult.Success(
                DestinationHighlights(
                    destinationIata = destination.iata,
                    highlights = highlights.take(config.maxResults.coerceAtLeast(1)),
                    retrievedAt = now(),
                ),
            )
        }

    override suspend fun selfTest(): SourceTestResult = withContext(Dispatchers.IO) {
        // Frankfurt has plenty of TripAdvisor content of its own, so this doesn't need a second
        // stand-in airport the way GoogleFlightsPriceSource's self-test does.
        when (val result = highlights(Airport.FRANKFURT)) {
            is TravelRecommendationResult.Success -> SourceTestResult.Ok(
                strings.get(R.string.src_tripadvisor_test_ok, result.highlights.highlights.size),
            )
            is TravelRecommendationResult.NotConfigured -> SourceTestResult.Problem("${result.reason} ${result.howToFix}")
            is TravelRecommendationResult.Failure -> SourceTestResult.Problem(
                result.userMessage.withDetail(result.technicalDetail),
            )
        }
    }

    /** One already-executed HTTP step: either a readable body, or a [TravelRecommendationResult]
     *  that the caller should return immediately without attempting the next step. */
    private sealed interface StepResult {
        data class Body(val body: String) : StepResult
        data class Failure(val result: TravelRecommendationResult) : StepResult
    }

    private fun request(url: String, apiKey: String, host: String): StepResult {
        val request = Request.Builder().url(url).get()
            .addHeader("Accept", "application/json")
            .addHeader("X-RapidAPI-Key", apiKey.trim())
            .addHeader("X-RapidAPI-Host", host)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> StepResult.Failure(
                        TravelRecommendationResult.Failure(strings.get(R.string.src_tripadvisor_denied), "HTTP ${response.code}"),
                    )
                    response.code == 429 -> StepResult.Failure(
                        TravelRecommendationResult.Failure(strings.get(R.string.src_tripadvisor_rate_limited), "HTTP 429"),
                    )
                    // A 404 here is not "the place wasn't found" — it means this host has no such
                    // endpoint, i.e. the configured path is wrong for the RapidAPI listing in use.
                    // The paths are an unverified reconstruction (see the class doc), so saying
                    // which path was asked for is the one thing that makes this fixable.
                    response.code == 404 -> StepResult.Failure(
                        TravelRecommendationResult.Failure(
                            strings.get(R.string.src_tripadvisor_not_found),
                            request.url.encodedPath.trimStart('/'),
                        ),
                    )
                    !response.isSuccessful -> StepResult.Failure(
                        TravelRecommendationResult.Failure(strings.get(R.string.src_tripadvisor_http, response.code), response.message),
                    )
                    else -> {
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank()) {
                            StepResult.Failure(TravelRecommendationResult.Failure(strings.get(R.string.src_tripadvisor_empty)))
                        } else {
                            StepResult.Body(body)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            StepResult.Failure(TravelRecommendationResult.Failure(strings.get(R.string.src_tripadvisor_offline), e.message))
        } catch (e: CancellationException) {
            // Cancellation is not a failure: it means the caller went away (a new search
            // superseded this one, or the screen was left). Reporting it as an error would
            // put a spurious message on screen and hide the cancellation from the caller.
            throw e
        } catch (e: Exception) {
            StepResult.Failure(TravelRecommendationResult.Failure(strings.get(R.string.src_tripadvisor_parse_failed), e.message))
        }
    }

    private fun locationSearchUrl(config: TripAdvisorApiConfig, cityName: String): String = buildString {
        append(scheme).append("://").append(config.apiHost.trim())
        append('/').append(config.locationSearchPath.trim().trimStart('/'))
        append('?').append(config.locationQueryParam).append('=')
        append(java.net.URLEncoder.encode(cityName, "UTF-8"))
    }

    private fun highlightsUrl(config: TripAdvisorApiConfig, locationId: String): String = buildString {
        append(scheme).append("://").append(config.apiHost.trim())
        append('/').append(config.highlightsPath.trim().trimStart('/'))
        append('?').append(config.highlightsLocationIdParam).append('=').append(locationId)
    }

    /**
     * Generic mapping from the location-search response onto the id the second call needs. Field
     * name comes from [TripAdvisorApiConfig.locationIdField], user-corrected if the guessed default
     * is wrong. Returns null rather than guessing when nothing resolves.
     */
    internal fun mapLocationId(body: String, config: TripAdvisorApiConfig): String? {
        val root = json.parseToJsonElement(body)
        val item: JsonObject = when {
            config.locationItemsPath.isBlank() -> root as? JsonObject ?: return null
            else -> when (val resolved = resolvePath(root, config.locationItemsPath)) {
                is JsonArray -> resolved.firstOrNull() as? JsonObject ?: return null
                is JsonObject -> resolved
                else -> return null
            }
        }
        return item.str(config.locationIdField)
    }

    /**
     * Generic mapping from the highlights-list response onto [TravelHighlight] — same reasoning as
     * [GoogleFlightsPriceSource.mapQuote]: field names come from [TripAdvisorApiConfig], corrected
     * by the user if the guessed defaults are wrong. A row missing a name is dropped rather than
     * shown blank; every other field is optional and simply reported as "not available" when it
     * doesn't resolve.
     */
    /**
     * Why a listing response produced nothing usable, in one sentence.
     *
     * Two settings can be wrong here and they fail identically: the path to the array, and the
     * field holding each entry's name. Naming what the response actually contained separates them
     * — a wrapper key that is not the configured items path shows up immediately, and so does an
     * array of objects whose keys do not include the configured name field.
     */
    internal fun diagnoseHighlights(body: String, config: TripAdvisorApiConfig): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return "the response was not valid JSON"

        val array = when {
            config.itemsPath.isBlank() -> root as? JsonArray
            else -> resolvePath(root, config.itemsPath) as? JsonArray
        }
        if (array == null) {
            val keys = (root as? JsonObject)?.keys
            return when {
                keys == null -> "the response is neither a list nor an object"
                config.itemsPath.isBlank() ->
                    "the response is an object, not a list; its keys are: ${keys.joinToString(", ")}" +
                        " — set the list path to whichever holds the attractions"
                else -> "no list at \"${config.itemsPath}\"; the keys present are: ${keys.joinToString(", ")}"
            }
        }
        if (array.isEmpty()) return "the list came back empty for this place"
        val firstKeys = (array.first() as? JsonObject)?.keys
            ?: return "the list holds values rather than objects"
        return "${array.size} entries came back, but none has a \"${config.fieldName}\" field; " +
            "the keys present are: ${firstKeys.joinToString(", ")}"
    }

    internal fun mapHighlights(body: String, config: TripAdvisorApiConfig): List<TravelHighlight> {
        val root = json.parseToJsonElement(body)
        val array: JsonArray = when {
            config.itemsPath.isBlank() -> root as? JsonArray ?: return emptyList()
            else -> resolvePath(root, config.itemsPath) as? JsonArray ?: return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.str(config.fieldName) ?: return@mapNotNull null
            TravelHighlight(
                name = name,
                category = categoryOf(obj.str(config.fieldCategory)),
                rating = obj.number(config.fieldRating),
                reviewCount = obj.int(config.fieldReviewCount),
                url = obj.str(config.fieldUrl),
                address = obj.str(config.fieldAddress),
            )
        }
    }

    private fun categoryOf(raw: String?): HighlightCategory {
        val text = raw?.lowercase() ?: return HighlightCategory.OTHER
        return when {
            "restaurant" in text || "food" in text -> HighlightCategory.RESTAURANT
            "hotel" in text || "lodging" in text || "accommodation" in text -> HighlightCategory.HOTEL
            // "thing" alone is deliberately not one of these: it's a substring of "something",
            // "nothing", "everything" — exactly the generic category text this is meant to fall
            // through to OTHER for, not misclassify as an attraction.
            "attraction" in text || "things to do" in text || "activit" in text -> HighlightCategory.ATTRACTION
            else -> HighlightCategory.OTHER
        }
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

    private fun JsonObject.str(key: String): String? =
        (at(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.number(key: String): Double? = (at(key) as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.int(key: String): Int? = (at(key) as? JsonPrimitive)?.intOrNull
}

/**
 * User-editable description of the TripAdvisor (RapidAPI) contract. Defaults are a best-effort
 * reconstruction from public documentation snippets, **not a verified contract** — see the class
 * doc on [TripAdvisorRecommendationSource]. The category field name defaults to blank because no
 * confirmed example was found in the researched snippets at all; fill it in from the real response.
 */
data class TripAdvisorApiConfig(
    val enabled: Boolean = false,
    val apiHost: String = "tripadvisor-scraper.p.rapidapi.com",
    /**
     * Blank by default, and deliberately so: this listing's `…/list` endpoint takes a place name
     * directly, so resolving one to an entity id first is an extra request bought for nothing —
     * and the free plan allows 200 a month. Fill this in only for a host that genuinely requires
     * a two-step lookup; blank means "ask the listing endpoint directly".
     */
    val locationSearchPath: String = "",
    val locationQueryParam: String = "query",
    /** Dotted path to the array of location results in the first response, e.g. `data`. */
    val locationItemsPath: String = "data",
    val locationIdField: String = "documentId",
    /** From the listing's published API reference: attractions for a place, 30 per page. */
    val highlightsPath: String = "tripadvisor/attractions/list",
    /** The listing names this `query`, and accepts a place name, a URL or an entity id in it. */
    val highlightsLocationIdParam: String = "query",
    /**
     * Dotted path to the array of attractions in the listing response.
     *
     * The published reference documents the parameters exactly but shows a sample body only for a
     * *detail* call, which has no wrapper at all — so this is the one value still inferred rather
     * than read off the contract. Blank means "the response is the array itself", which is the
     * shape that reference implies. If it turns out to be wrapped, the failure message now names
     * the keys that actually came back, and this field is where you correct it.
     */
    val itemsPath: String = "",
    val fieldName: String = "name",
    val fieldRating: String = "rating",
    val fieldReviewCount: String = "reviews",
    val fieldUrl: String = "link",
    val fieldAddress: String = "address",
    /** Left blank on purpose — see the class doc. */
    val fieldCategory: String = "",
    val maxResults: Int = 5,
)
