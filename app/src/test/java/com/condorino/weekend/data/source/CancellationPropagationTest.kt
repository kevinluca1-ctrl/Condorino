package com.condorino.weekend.data.source

import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.Cabin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A cancelled search must stay cancelled: it has to come out of a source as a cancellation, never
 * as a result the caller would then paint onto a screen the user has already moved on from.
 * `PlannerViewModel.load` cancels the previous search on every weekend change, so this is the
 * ordinary case, not an edge one.
 *
 * These pin the property at the source boundary. They do not exercise the broad `catch (e:
 * Exception)` clauses inside the sources — no suspension point currently sits inside those try
 * blocks, so cancellation cannot reach them today. Those clauses still rethrow
 * `CancellationException` first, because `CancellationException` *is* an `Exception`: the day a
 * suspending call moves inside one of those blocks, catching it would turn a cancellation into a
 * reported failure, and nothing else would notice.
 */
class CancellationPropagationTest {

    private val strings = object : SourceStrings(null) {
        override fun get(id: Int, vararg args: Any?): String = "id=$id"
        override fun plural(id: Int, count: Int, vararg args: Any?): String = "id=$id"
    }

    private val munich = Airport(
        iata = "MUC", name = "Munich", city = "Munich",
        country = "Germany", countryCode = "DE", timeZoneId = "Europe/Berlin",
    )

    /**
     * Cancels the coroutine from inside the source's own config lookup — the first suspension point
     * it reaches, and one that sits inside the try block that used to swallow it.
     */
    private fun assertCancellationPropagates(run: suspend () -> Unit) = runBlocking {
        val reachedConfig = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            try {
                run()
                fail("the source returned a result instead of staying cancelled")
            } catch (e: CancellationException) {
                throw e
            }
        }
        withTimeout(5_000) { reachedConfig.complete(Unit) }
        job.cancel()
        try {
            job.await()
            fail("await() should not complete normally after cancellation")
        } catch (e: CancellationException) {
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun `Google Flights stays cancelled instead of reporting a failure`() {
        val source = GoogleFlightsPriceSource(
            client = OkHttpClient(),
            // Suspends forever, standing in for a request in flight when the user moves on.
            configProvider = { awaitCancellation() },
            apiKeyProvider = { "key" },
            strings = strings,
        )
        assertCancellationPropagates {
            source.quote(
                Airport.FRANKFURT, munich,
                java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(2),
                Cabin.ECONOMY,
            )
        }
    }

    @Test
    fun `TripAdvisor stays cancelled instead of reporting a failure`() {
        val source = TripAdvisorRecommendationSource(
            client = OkHttpClient(),
            configProvider = { awaitCancellation() },
            apiKeyProvider = { "key" },
            strings = strings,
        )
        assertCancellationPropagates { source.highlights(munich) }
    }
}
