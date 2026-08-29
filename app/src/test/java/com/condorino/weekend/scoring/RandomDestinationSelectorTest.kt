package com.condorino.weekend.scoring

import com.condorino.weekend.Fixtures
import com.condorino.weekend.domain.model.DestinationProfile
import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.WeekendPattern
import com.condorino.weekend.domain.model.WeekendTrip
import com.condorino.weekend.domain.model.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RandomDestinationSelectorTest {

    private val prefs = UserPreferences.DEFAULT
    private val time = TimeCompatibilityCalculator(prefs)
    private val engine = TripScoringEngine(prefs, time)

    private fun trip(
        destination: Destination,
        outboundTime: String,
        price: StandbyPrice? = null,
    ): WeekendTrip {
        val out = Fixtures.flight(Fixtures.FRA, destination.airport, Fixtures.FRIDAY, outboundTime, 90)
        val back = Fixtures.flight(destination.airport, Fixtures.FRA, Fixtures.SUNDAY, "19:00", 90)
        val effective = time.effectiveTime(out, back, destination)
        return WeekendTrip(
            outbound = out,
            inbound = back,
            destination = destination,
            pattern = WeekendPattern.FRI_SUN,
            nights = time.nights(out, back),
            effectiveTime = effective,
            standbyPrice = price,
            score = engine.score(out, back, destination, WeekendPattern.FRI_SUN, price, effective, 2),
            provenance = out.provenance,
        )
    }

    private val beach = Destination(
        airport = Fixtures.FNC,
        profile = DestinationProfile(iata = "FNC", beach = 9, cityTrip = 3, transferMinutes = 30),
    )
    private val city = Destination(
        airport = Fixtures.LGW,
        profile = DestinationProfile(iata = "LGW", beach = 1, cityTrip = 10, transferMinutes = 45),
    )
    private val plain = Destination(airport = Fixtures.BUD)

    @Test
    fun `a seeded selector is deterministic`() {
        val trips = listOf(trip(city, "18:15"), trip(beach, "19:00"), trip(plain, "20:00"))
        val a = RandomDestinationSelector(Random(42)).pick(trips, RandomMode.ANY, prefs)
        val b = RandomDestinationSelector(Random(42)).pick(trips, RandomMode.ANY, prefs)
        assertEquals(a?.iata, b?.iata)
    }

    @Test
    fun `best score mode always returns the top-ranked trip`() {
        val trips = listOf(trip(city, "13:00"), trip(beach, "19:30"))
        val best = trips.maxByOrNull { it.score.total }
        val picked = RandomDestinationSelector(Random(1)).pick(trips, RandomMode.BEST_SCORE, prefs)
        assertEquals(best?.iata, picked?.iata)
    }

    @Test
    fun `sun mode only ever returns beach destinations`() {
        val trips = listOf(trip(city, "18:15"), trip(beach, "18:15"), trip(plain, "18:15"))
        repeat(20) { seed ->
            val picked = RandomDestinationSelector(Random(seed)).pick(trips, RandomMode.SUN, prefs)
            assertEquals("FNC", picked?.iata)
        }
    }

    @Test
    fun `city mode only ever returns city destinations`() {
        val trips = listOf(trip(city, "18:15"), trip(beach, "18:15"))
        repeat(20) { seed ->
            assertEquals("LGW", RandomDestinationSelector(Random(seed)).pick(trips, RandomMode.CITY_TRIP, prefs)?.iata)
        }
    }

    @Test
    fun `budget mode excludes trips over budget and those without a price`() {
        val cheap = trip(
            city, "18:15",
            StandbyPrice("LGW", PriceEntryMode.ROUND_TRIP, economyOutboundCents = 9_000),
        )
        val expensive = trip(
            beach, "18:15",
            StandbyPrice("FNC", PriceEntryMode.ROUND_TRIP, economyOutboundCents = 29_000),
        )
        val unpriced = trip(plain, "18:15")

        val tight = prefs.copy(maxBudgetCents = 12_000)
        repeat(15) { seed ->
            val picked = RandomDestinationSelector(Random(seed))
                .pick(listOf(cheap, expensive, unpriced), RandomMode.UNDER_BUDGET, tight)
            assertEquals("LGW", picked?.iata)
        }
    }

    @Test
    fun `returns null rather than a wrong suggestion when nothing matches the mode`() {
        val trips = listOf(trip(city, "18:15"))
        assertNull(RandomDestinationSelector(Random(3)).pick(trips, RandomMode.SUN, prefs))
    }

    @Test
    fun `returns null on an empty candidate list`() {
        assertNull(RandomDestinationSelector(Random(3)).pick(emptyList(), RandomMode.ANY, prefs))
    }

    @Test
    fun `a destination with several connections is not over-represented in the draw`() {
        val trips = listOf(
            trip(city, "18:15"), trip(city, "19:15"), trip(city, "20:15"),
            trip(beach, "18:15"),
        )
        val pool = RandomDestinationSelector().candidates(trips, RandomMode.ANY, prefs)
        assertEquals(2, pool.size)
        assertEquals(setOf("LGW", "FNC"), pool.map { it.iata }.toSet())
    }

    @Test
    fun `top ten mode never returns more than ten candidates`() {
        val many = (1..15).map { trip(plain, "18:%02d".format(it % 60)) }
        assertTrue(RandomDestinationSelector().candidates(many, RandomMode.TOP_TEN, prefs).size <= 10)
    }
}
