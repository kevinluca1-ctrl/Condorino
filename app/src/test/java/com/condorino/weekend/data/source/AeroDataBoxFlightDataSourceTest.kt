package com.condorino.weekend.data.source

import com.condorino.weekend.R
import com.condorino.weekend.domain.model.Airport
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Covers request chunking, URL building and the generic [AeroDataBoxFlightDataSource.mapFlights]
 * extraction — the field names come from [AeroDataBoxConfig], a considerably more confident but
 * still unverified reconstruction (see the class doc), so what matters here is that the *mechanism*
 * (dotted-path resolution, the departures/arrivals-are-mirror-images mapping, the airline filter,
 * the timestamp quirk normalisation) works whatever the real field names turn out to be.
 */
class AeroDataBoxFlightDataSourceTest {

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

    private val munich = Airport(
        iata = "MUC",
        name = "Munich Airport",
        city = "Munich",
        country = "Germany",
        countryCode = "DE",
        timeZoneId = "Europe/Berlin",
    )

    private val airports = mapOf(
        Airport.HOME_IATA to Airport.FRANKFURT,
        munich.iata to munich,
    )

    private fun configFor(windowHours: Int = 12) = AeroDataBoxConfig(
        enabled = true,
        apiHost = server.hostName + ":" + server.port,
        windowHours = windowHours,
    )

    private fun sourceFor(config: AeroDataBoxConfig, selected: Set<String> = setOf("CFG")) = AeroDataBoxFlightDataSource(
        client = OkHttpClient(),
        configProvider = { config },
        airportCatalog = { airports },
        apiKeyProvider = { "rapid-key" },
        selectedAirlinesProvider = { selected },
        strings = AeroDataBoxFakeStrings(),
        now = { fixedNow },
        scheme = "http",
    )

    private val oneDeparture = """{"departures":[
        {"departure":{"airport":{"iata":"FRA"},"scheduledTimeUtc":"2026-09-11 18:00Z"},
         "arrival":{"airport":{"iata":"MUC"},"scheduledTimeUtc":"2026-09-11 19:05Z"},
         "number":"DE 1234","airline":{"name":"Condor","icao":"CFG"}}
    ],"arrivals":[]}"""

    @Test
    fun `search sends the RapidAPI headers and one request per chunked window`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"departures":[],"arrivals":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"departures":[],"arrivals":[]}"""))

        val config = configFor(windowHours = 24)
        // Friday through Sunday (3 days = 72h) at a 24h window needs exactly 3 chunks.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"departures":[],"arrivals":[]}"""))
        sourceFor(config).search(
            FlightSearchQuery(from = LocalDate.of(2026, 9, 11), to = LocalDate.of(2026, 9, 13)),
        )

        assertEquals(3, server.requestCount)
        val request = server.takeRequest()
        assertEquals("rapid-key", request.getHeader("X-RapidAPI-Key"))
        assertEquals(config.apiHost, request.getHeader("X-RapidAPI-Host"))
        assertTrue(request.path.orEmpty().startsWith("/flights/airports/iata/FRA/"))
    }

    @Test
    fun `chunkWindows splits a multi-day range into windowHours-sized pieces`() {
        val config = configFor(windowHours = 12)
        val windows = sourceFor(config).chunkWindows(
            FlightSearchQuery(from = LocalDate.of(2026, 9, 11), to = LocalDate.of(2026, 9, 12)),
            config,
        )
        // Friday..Saturday inclusive = 48h at a 12h window = exactly 4 chunks.
        assertEquals(4, windows.size)
        assertEquals(LocalDateTime.of(2026, 9, 11, 0, 0), windows.first().first)
        assertEquals(LocalDateTime.of(2026, 9, 13, 0, 0), windows.last().second)
        windows.forEach { (from, to) -> assertEquals(12L, java.time.Duration.between(from, to).toHours()) }
    }

    @Test
    fun `windowUrl encodes the local time window without seconds and the with- query flags`() {
        val config = configFor()
        val url = sourceFor(config).windowUrl(
            config,
            LocalDateTime.of(2026, 9, 11, 0, 0),
            LocalDateTime.of(2026, 9, 11, 12, 0),
        )
        assertEquals(
            "http://${config.apiHost}/flights/airports/iata/FRA/2026-09-11T00:00/2026-09-11T12:00" +
                "?withLeg=true&withCancelled=false&withCodeshared=false&withPrivate=false",
            url,
        )
    }

    @Test
    fun `mapFlights maps a departures entry as an outbound flight from home`() {
        val config = configFor()
        val flights = sourceFor(config).mapFlights(oneDeparture, config, Airport.FRANKFURT, airports, setOf("CFG"))

        assertEquals(1, flights.size)
        val flight = flights[0]
        assertEquals("FRA", flight.origin.iata)
        assertEquals("MUC", flight.destination.iata)
        assertEquals("DE 1234", flight.flightNumber)
        assertEquals("Condor", flight.airline)
        assertEquals("CFG", flight.airlineCode)
        assertEquals(Instant.parse("2026-09-11T18:00:00Z"), flight.departure)
        assertEquals(Instant.parse("2026-09-11T19:05:00Z"), flight.arrival)
    }

    @Test
    fun `mapFlights maps an arrivals entry as an inbound flight to home, mirrored`() {
        val config = configFor()
        val body = """{"departures":[],"arrivals":[
            {"departure":{"airport":{"iata":"MUC"},"scheduledTimeUtc":"2026-09-11 18:00Z"},
             "arrival":{"airport":{"iata":"FRA"},"scheduledTimeUtc":"2026-09-11 19:05Z"},
             "number":"DE 5678","airline":{"name":"Condor","icao":"CFG"}}
        ]}"""
        val flights = sourceFor(config).mapFlights(body, config, Airport.FRANKFURT, airports, setOf("CFG"))

        assertEquals(1, flights.size)
        assertEquals("MUC", flights[0].origin.iata)
        assertEquals("FRA", flights[0].destination.iata)
    }

    @Test
    fun `mapFlights drops a row whose airline is not in the selection`() {
        val config = configFor()
        val body = """{"departures":[
            {"departure":{"airport":{"iata":"FRA"},"scheduledTimeUtc":"2026-09-11 18:00Z"},
             "arrival":{"airport":{"iata":"MUC"},"scheduledTimeUtc":"2026-09-11 19:05Z"},
             "number":"LH 100","airline":{"name":"Lufthansa","icao":"DLH"}}
        ],"arrivals":[]}"""
        assertTrue(sourceFor(config).mapFlights(body, config, Airport.FRANKFURT, airports, setOf("CFG")).isEmpty())
    }

    @Test
    fun `mapFlights keeps a Lufthansa row once Lufthansa is added to the selection`() {
        val config = configFor()
        val body = """{"departures":[
            {"departure":{"airport":{"iata":"FRA"},"scheduledTimeUtc":"2026-09-11 18:00Z"},
             "arrival":{"airport":{"iata":"MUC"},"scheduledTimeUtc":"2026-09-11 19:05Z"},
             "number":"LH 100","airline":{"name":"Lufthansa","icao":"DLH"}}
        ],"arrivals":[]}"""
        val flights = sourceFor(config).mapFlights(body, config, Airport.FRANKFURT, airports, setOf("CFG", "DLH"))
        assertEquals(1, flights.size)
        assertEquals("Lufthansa", flights[0].airline)
        assertEquals("DLH", flights[0].airlineCode)
    }

    @Test
    fun `mapFlights drops a row with no resolvable airline rather than defaulting to Condor`() {
        val config = configFor()
        val body = """{"departures":[
            {"departure":{"airport":{"iata":"FRA"},"scheduledTimeUtc":"2026-09-11 18:00Z"},
             "arrival":{"airport":{"iata":"MUC"},"scheduledTimeUtc":"2026-09-11 19:05Z"},
             "number":"XX 1"}
        ],"arrivals":[]}"""
        assertTrue(sourceFor(config).mapFlights(body, config, Airport.FRANKFURT, airports, setOf("CFG")).isEmpty())
    }

    @Test
    fun `mapFlights drops a row whose other airport is not in the catalog rather than guessing`() {
        val config = configFor()
        val body = """{"departures":[
            {"departure":{"airport":{"iata":"FRA"},"scheduledTimeUtc":"2026-09-11 18:00Z"},
             "arrival":{"airport":{"iata":"ZZZ"},"scheduledTimeUtc":"2026-09-11 19:05Z"},
             "number":"DE 1234","airline":{"name":"Condor","icao":"CFG"}}
        ],"arrivals":[]}"""
        assertTrue(sourceFor(config).mapFlights(body, config, Airport.FRANKFURT, airports, setOf("CFG")).isEmpty())
    }

    @Test
    fun `parseAeroDataBoxTime normalises the space-separated UTC quirk`() {
        val source = sourceFor(configFor())
        assertEquals(Instant.parse("2026-09-11T18:00:00Z"), source.parseAeroDataBoxTime("2026-09-11 18:00Z"))
    }

    @Test
    fun `parseAeroDataBoxTime accepts a real ISO-8601 instant too`() {
        val source = sourceFor(configFor())
        assertEquals(Instant.parse("2026-09-11T18:00:00Z"), source.parseAeroDataBoxTime("2026-09-11T18:00:00Z"))
    }

    @Test
    fun `parseAeroDataBoxTime gives up rather than guessing on garbage input`() {
        val source = sourceFor(configFor())
        assertNull(source.parseAeroDataBoxTime("not a date"))
    }

    @Test
    fun `a 401 is reported as denied, not a generic failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val config = configFor()
        val result = sourceFor(config).search(
            FlightSearchQuery(from = LocalDate.of(2026, 9, 11), to = LocalDate.of(2026, 9, 11)),
        ) as? FlightSearchResult.Failure ?: error("expected Failure")

        assertEquals(AeroDataBoxFakeStrings().get(com.condorino.weekend.R.string.src_aerodatabox_denied), result.userMessage)
        assertEquals("HTTP 401", result.technicalDetail)
    }

    @Test
    fun `a 429 with no Retry-After header uses the plain rate-limit message`() = runBlocking {
        // The bug this covers: this used to read "RapidAPI limit reached", implying the monthly
        // quota was exhausted — reported even at 5% usage, because a Basic plan's own gateway
        // throttles per second, entirely separate from that quota.
        // Two: the first 429 is retried once (a per-second gate usually clears), so it is the
        // second that gets reported.
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        val config = configFor()
        val result = sourceFor(config).search(
            FlightSearchQuery(from = LocalDate.now(), to = LocalDate.now().plusDays(1)),
        ) as? FlightSearchResult.Failure ?: error("expected Failure")

        assertEquals(AeroDataBoxFakeStrings().get(R.string.src_aerodatabox_rate_limited), result.userMessage)
        assertEquals("HTTP 429", result.technicalDetail)
    }

    @Test
    fun `a 429 with a Retry-After header uses the message that names the wait`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        val config = configFor()
        val result = sourceFor(config).search(
            FlightSearchQuery(from = LocalDate.now(), to = LocalDate.now().plusDays(1)),
        ) as? FlightSearchResult.Failure ?: error("expected Failure")

        // The wait is shown the way a person reads it, not as a raw second count.
        assertEquals(
            AeroDataBoxFakeStrings().get(R.string.src_aerodatabox_rate_limited_retry, "7 s"),
            result.userMessage,
        )
    }

    @Test
    fun `chunked requests are paced rather than fired back to back`() = runBlocking {
        // The other half of the same bug: nothing throttled the app's own request rate, which is
        // exactly what trips a Basic plan's per-second gateway limit on a multi-chunk search.
        val config = configFor(windowHours = 6)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"departures":[],"arrivals":[]}""")) }

        val elapsedMs = kotlin.system.measureTimeMillis {
            sourceFor(config).search(
                FlightSearchQuery(from = LocalDate.now(), to = LocalDate.now()),
            )
        }
        // 4 six-hour windows in one day means 3 gaps of pacing between them; loose enough not to
        // be flaky, tight enough to fail if the pacing were ever removed entirely.
        assertTrue("expected some pacing between 4 chunked requests, took ${elapsedMs}ms", elapsedMs >= 200)
    }

    @Test
    fun `a 429 is reported as rate limited`() = runBlocking {
        // The first is retried once; the second is the one reported.
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        val config = configFor()
        val result = sourceFor(config).search(
            FlightSearchQuery(from = LocalDate.of(2026, 9, 11), to = LocalDate.of(2026, 9, 11)),
        ) as? FlightSearchResult.Failure ?: error("expected Failure")

        assertEquals(AeroDataBoxFakeStrings().get(com.condorino.weekend.R.string.src_aerodatabox_rate_limited), result.userMessage)
    }

    @Test
    fun `not configured is reported before any request is made`() = runBlocking {
        val config = AeroDataBoxConfig(enabled = false)
        val result = sourceFor(config).search(
            FlightSearchQuery(from = LocalDate.of(2026, 9, 11), to = LocalDate.of(2026, 9, 11)),
        )
        assertTrue(result is FlightSearchResult.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `no matching flights is reported as a failure naming the airline and airport`() = runBlocking {
        // A 1-day range at the default 12h window needs 2 chunks (00:00-12:00, 12:00-24:00) — see
        // `chunkWindows splits a multi-day range into windowHours-sized pieces` above.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"departures":[],"arrivals":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"departures":[],"arrivals":[]}"""))
        val config = configFor()
        val result = sourceFor(config).search(
            FlightSearchQuery(from = LocalDate.of(2026, 9, 11), to = LocalDate.of(2026, 9, 11)),
        ) as? FlightSearchResult.Failure ?: error("expected Failure")

        assertEquals(
            AeroDataBoxFakeStrings().get(com.condorino.weekend.R.string.src_aerodatabox_no_flights, "Condor", "FRA"),
            result.userMessage,
        )
    }

    @Test
    fun `a row is kept when the response reports the IATA designator instead of the ICAO one`() {
        // The field this reads is user-configurable and may well be pointed at an "iata" field.
        // Comparing designators raw dropped every such row and reported an empty airport.
        val config = configFor()
        val body = """{"departures":[
            {"departure":{"airport":{"iata":"FRA"},"scheduledTimeUtc":"2026-09-11 18:00Z"},
             "arrival":{"airport":{"iata":"MUC"},"scheduledTimeUtc":"2026-09-11 19:05Z"},
             "number":"DE 100","airline":{"name":"Condor","icao":"DE"}}
        ],"arrivals":[]}"""
        val flights = sourceFor(config).mapFlights(body, config, Airport.FRANKFURT, airports, setOf("CFG"))
        assertEquals(1, flights.size)
        // Normalised to the one designator the rest of the app matches on.
        assertEquals("CFG", flights[0].airlineCode)
    }

    @Test
    fun `a selection given as an IATA code still matches a row reporting ICAO`() {
        val config = configFor()
        val body = """{"departures":[
            {"departure":{"airport":{"iata":"FRA"},"scheduledTimeUtc":"2026-09-11 18:00Z"},
             "arrival":{"airport":{"iata":"MUC"},"scheduledTimeUtc":"2026-09-11 19:05Z"},
             "number":"DE 100","airline":{"name":"Condor","icao":"CFG"}}
        ],"arrivals":[]}"""
        val flights = sourceFor(config).mapFlights(body, config, Airport.FRANKFURT, airports, setOf("DE"))
        assertEquals(1, flights.size)
    }

    @Test
    fun `an airline outside the selection is still dropped`() {
        val config = configFor()
        val body = """{"departures":[
            {"departure":{"airport":{"iata":"FRA"},"scheduledTimeUtc":"2026-09-11 18:00Z"},
             "arrival":{"airport":{"iata":"MUC"},"scheduledTimeUtc":"2026-09-11 19:05Z"},
             "number":"LH 100","airline":{"name":"Lufthansa","icao":"DLH"}}
        ],"arrivals":[]}"""
        assertTrue(sourceFor(config).mapFlights(body, config, Airport.FRANKFURT, airports, setOf("CFG")).isEmpty())
    }


    @Test
    fun `a single 429 is retried and the search still succeeds`() = runBlocking {
        // The whole point of the retry: a per-second gate clears in about a second, so the user
        // should get their flights rather than an error telling them to try again themselves.
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(oneDeparture))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"departures":[],"arrivals":[]}"""))

        val config = configFor(windowHours = 24)
        val result = sourceFor(config).search(
            FlightSearchQuery(from = LocalDate.of(2026, 9, 11), to = LocalDate.of(2026, 9, 11)),
        )

        assertTrue("expected a Success after the retry, got $result", result is FlightSearchResult.Success)
    }

}

/** A [SourceStrings] that never touches Android — this test only cares which id/args were chosen. */
private class AeroDataBoxFakeStrings : SourceStrings(null) {
    override fun get(id: Int, vararg args: Any?): String = "id=$id;" + args.joinToString(",")
    override fun plural(id: Int, count: Int, vararg args: Any?): String = "id=$id;count=$count;" + args.joinToString(",")
}