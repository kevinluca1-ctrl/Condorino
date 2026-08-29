package com.condorino.weekend.data.source

import com.condorino.weekend.R
import com.condorino.weekend.data.reference.AirportReferenceCatalog
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the two behaviours added after OpenSky's own docs turned out to require them: a 401 on a
 * data request means "the 30-minute token expired", not "the credentials are wrong" (refresh once
 * and retry), and `/flights/*` bills by a credit system that punishes any request whose window
 * crosses a calendar day — hence many short (<24h) chunk requests instead of a few long ones.
 */
class OpenSkyFlightDataSourceTest {

    private lateinit var server: MockWebServer
    private val fixedNow = Instant.parse("2026-08-29T12:00:00Z")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun configFor(lookbackWeeks: Int = 1) = OpenSkyConfig(
        enabled = true,
        baseUrl = server.url("/").toString().trimEnd('/'),
        tokenUrl = server.url("/token").toString(),
        clientId = "client",
        clientSecret = "secret",
        homeIcao = "EDDF",
        callsignPrefix = "CFG",
        lookbackWeeks = lookbackWeeks,
    )

    private fun sourceFor(config: OpenSkyConfig) = OpenSkyFlightDataSource(
        client = OkHttpClient(),
        configProvider = { config },
        airportCatalog = AirportReferenceCatalog(context = null),
        strings = FakeStrings(),
        now = { fixedNow },
    )

    /** [begin, end] parsed from a recorded request's `begin`/`end` query parameters. */
    private fun window(request: RecordedRequest): Pair<Long, Long> {
        val url = request.requestUrl!!
        return (url.queryParameter("begin")!!.toLong()) to (url.queryParameter("end")!!.toLong())
    }

    private fun freshTokenResponse() =
        MockResponse().setResponseCode(200).setBody("""{"access_token":"fresh-token","expires_in":1800}""")

    private val oneObservation =
        """[{"callsign":"CFG123 ","estDepartureAirport":"EDDF","estArrivalAirport":"LEBL","firstSeen":1700000000,"lastSeen":1700005000}]"""

    @Test
    fun `a 401 on a data request refreshes the token once and retries instead of failing outright`() = runBlocking {
        val dataHits = AtomicInteger(0)
        val tokenHits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/token") -> {
                    tokenHits.incrementAndGet()
                    freshTokenResponse()
                }
                request.path.orEmpty().startsWith("/flights/") -> {
                    val hit = dataHits.incrementAndGet()
                    if (hit == 1) MockResponse().setResponseCode(401) else MockResponse().setResponseCode(200).setBody("[]")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val config = configFor()
        val result = sourceFor(config).fetchObservations(config, initialToken = "stale-token", arrivals = false)

        assertNull(result.lastErrorCode)
        assertEquals(1, tokenHits.get())
        assertTrue("the first (401) chunk must have been retried", dataHits.get() >= 2)
    }

    @Test
    fun `a second 401 right after a fresh token is a real denial, not retried again`() = runBlocking {
        val dataHits = AtomicInteger(0)
        val tokenHits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/token") -> {
                    tokenHits.incrementAndGet()
                    freshTokenResponse()
                }
                request.path.orEmpty().startsWith("/flights/") -> {
                    dataHits.incrementAndGet()
                    MockResponse().setResponseCode(401)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val config = configFor()
        val result = sourceFor(config).fetchObservations(config, initialToken = "stale-token", arrivals = false)

        assertEquals(401, result.lastErrorCode)
        assertEquals(1, tokenHits.get()) // exactly one refresh attempt, not one per remaining chunk
        assertEquals(2, dataHits.get()) // the original request plus its one retry, then it stops
    }

    @Test
    fun `a 429 stops immediately and reports the server's own retry-after`() = runBlocking {
        val dataHits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                dataHits.incrementAndGet()
                return MockResponse().setResponseCode(429)
                    .setHeader("X-Rate-Limit-Retry-After-Seconds", "137")
            }
        }

        val config = configFor()
        val result = sourceFor(config).fetchObservations(config, initialToken = null, arrivals = false)

        assertEquals(429, result.lastErrorCode)
        assertEquals(137L, result.retryAfterSeconds)
        assertEquals("no further chunks should be requested after a 429", 1, dataHits.get())
    }

    @Test
    fun `every chunk request stays under 24 hours to keep OpenSky's cheapest credit bracket`() = runBlocking {
        val windows = mutableListOf<Pair<Long, Long>>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                windows += window(request)
                return MockResponse().setResponseCode(200).setBody("[]")
            }
        }

        val config = configFor(lookbackWeeks = 2)
        sourceFor(config).fetchObservations(config, initialToken = null, arrivals = false)

        assertTrue("a 2-week lookback must be split into more than one request", windows.size > 1)
        windows.forEach { (begin, end) ->
            assertTrue("chunk window was $begin..$end, ${end - begin}s — must stay under 24h", end - begin < 24L * 3600L)
        }
        val earliestBegin = windows.minOf { it.first }
        val fullLookback = 2 * 7L * 24L * 3600L
        assertTrue(
            "the chunks must together reach back close to the full lookback",
            fixedNow.epochSecond - earliestBegin >= fullLookback - 24L * 3600L,
        )
    }

    @Test
    fun `a 400 on the departure endpoint retries once with a wider window before giving up`() = runBlocking {
        val hits = AtomicInteger(0)
        val recorded = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                return when (hits.incrementAndGet()) {
                    1 -> MockResponse().setResponseCode(400)
                    2 -> MockResponse().setResponseCode(200).setBody(oneObservation)
                    else -> MockResponse().setResponseCode(200).setBody("[]")
                }
            }
        }

        val config = configFor(lookbackWeeks = 1)
        val result = sourceFor(config).fetchObservations(config, initialToken = null, arrivals = false)

        assertNull("the wide retry resolved the chunk, so this must not surface as a failure", result.lastErrorCode)
        assertEquals(1, result.observations.size)

        val (firstBegin, firstEnd) = window(recorded[0])
        val (secondBegin, secondEnd) = window(recorded[1])
        assertTrue("the initial attempt should be the normal short window", firstEnd - firstBegin < 24L * 3600L)
        assertTrue("the 400 fallback should widen the window", secondEnd - secondBegin > 24L * 3600L)
    }

    @Test
    fun `selfTest recovers from a 401 the same way a search does`() = runBlocking {
        val dataHits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/token") -> freshTokenResponse()
                request.path.orEmpty().startsWith("/flights/") -> {
                    if (dataHits.incrementAndGet() == 1) MockResponse().setResponseCode(401) else MockResponse().setResponseCode(200).setBody("[]")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val result = sourceFor(configFor()).selfTest()

        assertTrue("expected Ok, got $result", result is SourceTestResult.Ok)
    }

    @Test
    fun `a departure-endpoint interval doc discrepancy does not silently drop data`() = runBlocking {
        // Sanity companion to the fallback test above: confirms the *narrow* window alone, with no
        // 400 involved, still yields the observation OpenSky actually reports.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().startsWith("/flights/departure")) {
                    MockResponse().setResponseCode(200).setBody(oneObservation)
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val config = configFor()
        val result = sourceFor(config).fetchObservations(config, initialToken = null, arrivals = false)

        assertNull(result.lastErrorCode)
        assertEquals(1, result.observations.size)
    }

    @Test
    fun `selfTest reports a 429's retry-after through the dedicated message`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/flights/") ->
                    MockResponse().setResponseCode(429).setHeader("X-Rate-Limit-Retry-After-Seconds", "42")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val result = sourceFor(configFor())
            .selfTest() as? SourceTestResult.Problem
            ?: error("expected Problem")

        assertEquals(FakeStrings().get(R.string.src_opensky_rate_limited_retry, 42L), result.message)
    }
}

/** A [SourceStrings] that never touches Android — proves *which* resource id/args were chosen. */
private class FakeStrings : SourceStrings(null) {
    override fun get(id: Int, vararg args: Any?): String = "id=$id;" + args.joinToString(",")
    override fun plural(id: Int, count: Int, vararg args: Any?): String = "id=$id;count=$count;" + args.joinToString(",")
}
