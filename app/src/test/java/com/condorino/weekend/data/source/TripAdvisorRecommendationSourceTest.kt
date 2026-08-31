package com.condorino.weekend.data.source

import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.HighlightCategory
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

/**
 * Covers the two-step request flow (location search, then highlights for that location) and the
 * generic [TripAdvisorRecommendationSource.mapLocationId] / [mapHighlights] extraction — the field
 * names come from [TripAdvisorApiConfig], an unverified best guess (see the class doc), so what
 * matters here is that the *mechanism* works whatever the real field names turn out to be.
 */
class TripAdvisorRecommendationSourceTest {

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

    private fun configFor(
        locationItemsPath: String = "data",
        itemsPath: String = "data",
    ) = TripAdvisorApiConfig(
        enabled = true,
        apiHost = server.hostName + ":" + server.port,
        // Stated explicitly rather than taken from the defaults, which are deliberately blank:
        // this app does not guess endpoints, so a real install asks the user for these. These
        // tests are about the request mechanism, not about what any particular host calls them.
        locationSearchPath = "locations/search",
        highlightsPath = "attractions/list",
        locationItemsPath = locationItemsPath,
        itemsPath = itemsPath,
        fieldCategory = "category",
    )

    @Test
    fun `with no listing path set the source asks to be configured instead of guessing`() = runBlocking {
        // The listing path is the one setting the source cannot work without; blanking it must
        // read as "not set up" rather than producing a request that cannot succeed.
        val config = TripAdvisorApiConfig(enabled = true, apiHost = "example.invalid", highlightsPath = "")
        val status = sourceFor(config).status()

        assertTrue("expected NotConfigured, got $status", status is SourceStatus.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the shipped defaults follow the published API reference`() {
        val defaults = TripAdvisorApiConfig()
        assertEquals("tripadvisor/attractions/list", defaults.highlightsPath)
        // The listing takes a place name in the same parameter it takes an entity id, so the
        // lookup step is skipped by default — one request per destination instead of two, which
        // matters on a plan of 200 a month.
        assertEquals("query", defaults.highlightsLocationIdParam)
        assertEquals("", defaults.locationSearchPath)
        assertEquals("reviews", defaults.fieldReviewCount)
        assertEquals("link", defaults.fieldUrl)
    }

    private fun sourceFor(config: TripAdvisorApiConfig) = TripAdvisorRecommendationSource(
        client = OkHttpClient(),
        configProvider = { config },
        apiKeyProvider = { "rapid-key" },
        strings = TripAdvisorFakeStrings(),
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

    private val oneLocation = """{"data":[{"documentId":"loc-42"}]}"""
    private val oneHighlight =
        """{"data":[{"name":"Marienplatz","rating":4.6,"reviews":12000,"link":"https://example.com/mp","address":"Munich","category":"attraction"}]}"""

    @Test
    fun `highlights queries location search first, then highlights for the resolved id`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(oneLocation))
        server.enqueue(MockResponse().setResponseCode(200).setBody(oneHighlight))

        val config = configFor()
        val result = sourceFor(config).highlights(munich)

        val first = server.takeRequest()
        assertEquals("rapid-key", first.getHeader("X-RapidAPI-Key"))
        assertEquals(config.apiHost, first.getHeader("X-RapidAPI-Host"))
        assertEquals("Munich", first.requestUrl!!.queryParameter(config.locationQueryParam))
        assertTrue(first.path.orEmpty().startsWith("/${config.locationSearchPath}"))

        val second = server.takeRequest()
        assertEquals("loc-42", second.requestUrl!!.queryParameter(config.highlightsLocationIdParam))
        assertTrue(second.path.orEmpty().startsWith("/${config.highlightsPath}"))

        val success = result as? TravelRecommendationResult.Success ?: error("expected Success, got $result")
        assertEquals(1, success.highlights.highlights.size)
        assertEquals("Marienplatz", success.highlights.highlights[0].name)
        assertEquals(HighlightCategory.ATTRACTION, success.highlights.highlights[0].category)
    }

    @Test
    fun `mapLocationId resolves an array items path and takes the first entry's id field`() {
        val config = configFor()
        val body = """{"data":[{"documentId":"first"},{"documentId":"second"}]}"""
        assertEquals("first", sourceFor(config).mapLocationId(body, config))
    }

    @Test
    fun `mapLocationId resolves a blank items path as the root object itself`() {
        val config = configFor(locationItemsPath = "")
        val body = """{"documentId":"root-id"}"""
        assertEquals("root-id", sourceFor(config).mapLocationId(body, config))
    }

    @Test
    fun `mapLocationId returns null rather than guessing when nothing resolves`() {
        val config = configFor()
        val body = """{"data":[]}"""
        assertNull(sourceFor(config).mapLocationId(body, config))
    }

    @Test
    fun `mapHighlights maps every field and infers category from free text`() {
        val config = configFor()
        val highlights = sourceFor(config).mapHighlights(oneHighlight, config)

        assertEquals(1, highlights.size)
        val h = highlights[0]
        assertEquals("Marienplatz", h.name)
        assertEquals(4.6, h.rating)
        assertEquals(12000, h.reviewCount)
        assertEquals("https://example.com/mp", h.url)
        assertEquals("Munich", h.address)
        assertEquals(HighlightCategory.ATTRACTION, h.category)
    }

    @Test
    fun `mapHighlights defaults to OTHER when the category field is left blank in config`() {
        val config = configFor().copy(fieldCategory = "")
        val highlights = sourceFor(config).mapHighlights(oneHighlight, config)
        assertEquals(HighlightCategory.OTHER, highlights[0].category)
    }

    @Test
    fun `mapHighlights recognises restaurant and hotel category text too`() {
        val config = configFor()
        val body = """{"data":[
            {"name":"A","category":"Restaurant"},
            {"name":"B","category":"Hotel"},
            {"name":"C","category":"something else entirely"}
        ]}"""
        val highlights = sourceFor(config).mapHighlights(body, config)
        assertEquals(HighlightCategory.RESTAURANT, highlights[0].category)
        assertEquals(HighlightCategory.HOTEL, highlights[1].category)
        assertEquals(HighlightCategory.OTHER, highlights[2].category)
    }

    @Test
    fun `mapHighlights drops a row with no name rather than showing one blank`() {
        val config = configFor()
        val body = """{"data":[{"rating":4.0},{"name":"Has a name"}]}"""
        val highlights = sourceFor(config).mapHighlights(body, config)
        assertEquals(1, highlights.size)
        assertEquals("Has a name", highlights[0].name)
    }

    @Test
    fun `highlights truncates to the configured maximum`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(oneLocation))
        val threeItems = """{"data":[{"name":"A"},{"name":"B"},{"name":"C"}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(threeItems))

        val config = configFor().copy(maxResults = 2)
        val result = sourceFor(config).highlights(munich) as? TravelRecommendationResult.Success
            ?: error("expected Success")

        assertEquals(2, result.highlights.highlights.size)
    }

    @Test
    fun `a 401 on the location-search step is reported as denied without a second request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val config = configFor()
        val result = sourceFor(config).highlights(munich) as? TravelRecommendationResult.Failure
            ?: error("expected Failure")

        assertEquals(TripAdvisorFakeStrings().get(com.condorino.weekend.R.string.src_tripadvisor_denied), result.userMessage)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 429 on the highlights step is reported as rate limited`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(oneLocation))
        server.enqueue(MockResponse().setResponseCode(429))
        val config = configFor()
        val result = sourceFor(config).highlights(munich) as? TravelRecommendationResult.Failure
            ?: error("expected Failure")

        assertEquals(TripAdvisorFakeStrings().get(com.condorino.weekend.R.string.src_tripadvisor_rate_limited), result.userMessage)
    }

    @Test
    fun `not configured is reported before any request is made`() = runBlocking {
        val config = TripAdvisorApiConfig(enabled = false)
        val result = sourceFor(config).highlights(munich)
        assertTrue(result is TravelRecommendationResult.NotConfigured)
        assertEquals(0, server.requestCount)
    }
}

/** A [SourceStrings] that never touches Android — this test only cares which id/args were chosen. */
private class TripAdvisorFakeStrings : SourceStrings(null) {
    override fun get(id: Int, vararg args: Any?): String = "id=$id;" + args.joinToString(",")
    override fun plural(id: Int, count: Int, vararg args: Any?): String = "id=$id;count=$count;" + args.joinToString(",")

}
