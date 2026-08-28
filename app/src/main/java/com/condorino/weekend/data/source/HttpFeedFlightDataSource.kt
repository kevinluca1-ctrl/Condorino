package com.condorino.weekend.data.source

import com.condorino.weekend.R
import com.condorino.weekend.data.reference.AirportReferenceCatalog
import com.condorino.weekend.domain.model.DataProvenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant

/**
 * Fetches a [FlightFeed] document over HTTPS from a URL the user configures in Settings.
 *
 * This is the *supported* way to get real data into the app today. Because Condor's own search
 * backend is not a documented public API (docs/CONDOR_DATA_SOURCES.md), the app does not pretend
 * to speak it; instead anyone can publish the small JSON contract in [FlightFeed] — from a GDS
 * export, an OAG/Cirium schedule subscription, a Condor partner API contract, or a self-hosted
 * bridge — and point the app at it.
 *
 * The feed itself declares whether it is live (`"is_live": true`) or a published timetable, and
 * the app propagates that all the way into the UI badge. A feed that lies about being live is the
 * feed author's problem; the app never upgrades a provenance on its own.
 */
class HttpFeedFlightDataSource(
    private val client: OkHttpClient,
    private val configProvider: suspend () -> FeedConfig,
    private val airportCatalog: AirportReferenceCatalog,
    override val strings: SourceStrings,
    private val parser: FeedParser = FeedParser(),
) : FlightDataSource {

    override val id: String = "http-feed"
    override val displayName: String get() = strings.get(R.string.src_feed_name)
    override val bestProvenance: DataProvenance = DataProvenance.LIVE

    override suspend fun status(): SourceStatus {
        val config = configProvider()
        return when {
            !config.enabled -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_feed_disabled),
                howToFix = strings.get(R.string.src_feed_disabled_fix),
            )
            config.url.isBlank() -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_feed_no_url),
                howToFix = strings.get(R.string.src_feed_no_url_fix),
            )
            !config.url.startsWith("https://") -> SourceStatus.NotConfigured(
                reason = strings.get(R.string.src_https_required),
                howToFix = strings.get(R.string.src_https_required_fix),
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

            val url = buildUrl(config.url, query)
            val requestBuilder = Request.Builder().url(url).get()
            if (config.headerName.isNotBlank() && config.headerValue.isNotBlank()) {
                requestBuilder.addHeader(config.headerName.trim(), config.headerValue.trim())
            }

            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.code == 429) {
                        return@withContext FlightSearchResult.Failure(
                            strings.get(R.string.src_feed_rate_limited),
                            "HTTP 429",
                        )
                    }
                    if (!response.isSuccessful) {
                        return@withContext FlightSearchResult.Failure(
                            strings.get(R.string.src_feed_http, response.code),
                            response.message,
                        )
                    }
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        return@withContext FlightSearchResult.Failure(strings.get(R.string.src_feed_empty))
                    }

                    val parsed = parser.parse(body, referenceAirports = airportCatalog.airports())
                    val filtered = parsed.flights.filter { f ->
                        val date = f.departureLocal.toLocalDate()
                        !date.isBefore(query.from) && !date.isAfter(query.to)
                    }
                    FlightSearchResult.Success(
                        flights = filtered,
                        provenance = parsed.provenance,
                        retrievedAt = Instant.now(),
                        note = buildString {
                            append(strings.get(R.string.src_feed_note, parsed.source))
                            if (parsed.skipped.isNotEmpty()) {
                                append(strings.get(R.string.src_feed_note_skipped, parsed.skipped.size))
                            }
                        },
                    )
                }
            } catch (e: IOException) {
                FlightSearchResult.Failure(strings.get(R.string.src_feed_offline), e.message)
            } catch (e: Exception) {
                FlightSearchResult.Failure(strings.get(R.string.src_feed_parse_failed), e.message)
            }
        }

    /**
     * Appends `origin`, `from` and `to` as query parameters if the URL does not already carry a
     * query string. A static file (e.g. on GitHub Pages) simply ignores them.
     */
    private fun buildUrl(base: String, query: FlightSearchQuery): String {
        if (base.contains("?")) return base
        return buildString {
            append(base)
            append("?origin=").append(query.originIata)
            append("&from=").append(query.from)
            append("&to=").append(query.to)
            query.destinationIata?.let { append("&destination=").append(it) }
        }
    }
}

/** User-supplied configuration for [HttpFeedFlightDataSource]. */
data class FeedConfig(
    val enabled: Boolean = false,
    val url: String = "",
    /** Optional auth header, e.g. `X-API-Key`. Stored locally only, never transmitted elsewhere. */
    val headerName: String = "",
    val headerValue: String = "",
)
