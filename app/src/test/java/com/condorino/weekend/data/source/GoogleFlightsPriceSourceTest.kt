package com.condorino.weekend.data.source

import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.Cabin
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

/**
 * Covers URL building and the generic [GoogleFlightsPriceSource.mapQuote] extraction — the field
 * names come from [GoogleFlightsApiConfig], which is an unverified best guess (see the class doc),
 * so what matters here is that the *mechanism* (dotted-path resolution, array-vs-object items,
 * flexible boolean/text carry-on parsing) works whatever the real field names turn out to be.
 */
class GoogleFlightsPriceSourceTest {

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

    private fun configFor(itemsPath: String = "data.itineraries.topFlights") = GoogleFlightsApiConfig(
        enabled = true,
        apiHost = server.hostName + ":" + server.port,
        path = "api/v1/searchFlights",
        itemsPath = itemsPath,
        fieldCarryOnIncluded = "carryOnIncluded",
        fieldCarryOnNote = "carryOnNote",
    )

    private fun sourceFor(config: GoogleFlightsApiConfig) = GoogleFlightsPriceSource(
        client = OkHttpClient(),
        configProvider = { config },
        apiKeyProvider = { "rapid-key" },
        strings = GoogleFlightsFakeStrings(),
        now = { fixedNow },
        scheme = "http",
    )

    private val munich = Airport(
        iata = "MUC",
        name = "Munich Airport",
        city = "Munich",
        country = "Germany",
        countryCode = "DE",
        timeZoneId = "Europe/Berlin",
    )

    @Test
    fun `quote sends the RapidAPI headers and the configured query parameters`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"itineraries":{"topFlights":[{"price":199.5,"airlines":"Lufthansa"}]}}}""",
            ),
        )

        val config = configFor()
        sourceFor(config).quote(
            origin = Airport.FRANKFURT,
            destination = munich,
            outboundDate = LocalDate.of(2026, 9, 11),
            returnDate = LocalDate.of(2026, 9, 13),
            cabin = Cabin.ECONOMY,
        )

        val request = server.takeRequest()
        assertEquals("rapid-key", request.getHeader("X-RapidAPI-Key"))
        assertEquals(config.apiHost, request.getHeader("X-RapidAPI-Host"))
        val url = request.requestUrl!!
        assertEquals("FRA", url.queryParameter(config.departureIdParam))
        assertEquals("MUC", url.queryParameter(config.arrivalIdParam))
        assertEquals("2026-09-11", url.queryParameter(config.outboundDateParam))
        assertEquals("2026-09-13", url.queryParameter(config.returnDateParam))
        assertEquals("EUR", url.queryParameter(config.currencyParam))
    }

    @Test
    fun `mapQuote resolves the items path when it points at an array and takes the first entry`() {
        val body = """{"data":{"itineraries":{"topFlights":[
            {"price":250,"airlines":"Condor"},
            {"price":999,"airlines":"SomeoneElse"}
        ]}}}"""
        val quote = sourceFor(configFor()).mapQuote(body, configFor(), "MUC", Cabin.ECONOMY)

        assertEquals(25000L, quote?.roundTripPrice?.cents)
        assertEquals("Condor", quote?.airline)
    }

    @Test
    fun `mapQuote resolves the items path when it points directly at one object`() {
        val config = configFor(itemsPath = "data.cheapest")
        val body = """{"data":{"cheapest":{"price":"180.00","airlines":"Eurowings"}}}"""
        val quote = sourceFor(config).mapQuote(body, config, "MUC", Cabin.ECONOMY)

        assertEquals(18000L, quote?.roundTripPrice?.cents)
        assertEquals("Eurowings", quote?.airline)
    }

    @Test
    fun `mapQuote joins an airline array into one readable string`() {
        val config = configFor(itemsPath = "")
        val body = """{"price":100,"airlines":["British Airways","Iberia"]}"""
        val quote = sourceFor(config).mapQuote(body, config, "MUC", Cabin.ECONOMY)

        assertEquals("British Airways, Iberia", quote?.airline)
    }

    @Test
    fun `mapQuote accepts a real JSON boolean for carry-on`() {
        val config = configFor(itemsPath = "")
        val body = """{"price":100,"carryOnIncluded":true}"""
        val quote = sourceFor(config).mapQuote(body, config, "MUC", Cabin.ECONOMY)

        assertEquals(true, quote?.carryOnIncluded)
    }

    @Test
    fun `mapQuote also accepts a text carry-on value like some APIs report`() {
        val config = configFor(itemsPath = "")
        val body = """{"price":100,"carryOnIncluded":"not included"}"""
        val quote = sourceFor(config).mapQuote(body, config, "MUC", Cabin.ECONOMY)

        assertEquals(false, quote?.carryOnIncluded)
    }

    @Test
    fun `mapQuote reports carry-on as null rather than false when the field is blank in config`() {
        val config = configFor(itemsPath = "").copy(fieldCarryOnIncluded = "")
        val body = """{"price":100}"""
        val quote = sourceFor(config).mapQuote(body, config, "MUC", Cabin.ECONOMY)

        assertNull(quote?.carryOnIncluded)
    }

    @Test
    fun `mapQuote fails the whole quote rather than fabricating a price when none resolves`() {
        val config = configFor(itemsPath = "")
        val body = """{"noPriceHere":true}"""
        val quote = sourceFor(config).mapQuote(body, config, "MUC", Cabin.ECONOMY)

        assertNull(quote)
    }

    @Test
    fun `a 401 is reported as denied, not a generic failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val config = configFor()
        val result = sourceFor(config).quote(
            Airport.FRANKFURT, munich, LocalDate.now(), LocalDate.now().plusDays(2), Cabin.ECONOMY,
        ) as? CommercialPriceResult.Failure ?: error("expected Failure")

        assertEquals(GoogleFlightsFakeStrings().get(com.condorino.weekend.R.string.src_google_flights_denied), result.userMessage)
        assertEquals("HTTP 401", result.technicalDetail)
    }

    @Test
    fun `a 429 is reported as rate limited`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        val config = configFor()
        val result = sourceFor(config).quote(
            Airport.FRANKFURT, munich, LocalDate.now(), LocalDate.now().plusDays(2), Cabin.ECONOMY,
        ) as? CommercialPriceResult.Failure ?: error("expected Failure")

        assertEquals(GoogleFlightsFakeStrings().get(com.condorino.weekend.R.string.src_google_flights_rate_limited), result.userMessage)
    }

    @Test
    fun `not configured is reported before any request is made`() = runBlocking {
        val config = GoogleFlightsApiConfig(enabled = false)
        val result = sourceFor(config).quote(
            Airport.FRANKFURT, munich, LocalDate.now(), LocalDate.now().plusDays(2), Cabin.ECONOMY,
        )
        assertTrue(result is CommercialPriceResult.NotConfigured)
        assertEquals(0, server.requestCount)
    }
}

/** A [SourceStrings] that never touches Android — this test only cares which id/args were chosen. */
private class GoogleFlightsFakeStrings : SourceStrings(null) {
    override fun get(id: Int, vararg args: Any?): String = "id=$id;" + args.joinToString(",")
    override fun plural(id: Int, count: Int, vararg args: Any?): String = "id=$id;count=$count;" + args.joinToString(",")
}
