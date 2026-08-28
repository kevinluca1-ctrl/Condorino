package com.condorino.weekend.scoring

import com.condorino.weekend.Fixtures
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.WeekendPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripBuilderTest {

    private val prefs = UserPreferences.DEFAULT
    private val builder = TripBuilder(prefs)

    private val destinations = mapOf(
        "LGW" to Fixtures.destination(Fixtures.LGW),
        "BUD" to Fixtures.destination(Fixtures.BUD),
    )

    @Test
    fun `detects all four weekend patterns from the flight data`() {
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.THURSDAY, "19:55", 80),
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.MONDAY, "20:05", 80),
        )
        val result = builder.build(flights, Fixtures.FRIDAY, destinations, emptyMap())
        assertEquals(
            WeekendPattern.entries.toSet(),
            result.trips.map { it.pattern }.toSet(),
        )
    }

    @Test
    fun `Fri to Sun ranks first when all four patterns are available at the same times`() {
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.THURSDAY, "19:00", 80),
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "19:00", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:00", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.MONDAY, "19:00", 80),
        )
        val result = builder.build(flights, Fixtures.FRIDAY, destinations, emptyMap())
        assertEquals(WeekendPattern.FRI_SUN, result.trips.first().pattern)
    }

    @Test
    fun `reports why nothing was found instead of returning a silent empty list`() {
        // Outbound only — no way home.
        val flights = listOf(Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80))
        val result = builder.build(flights, Fixtures.FRIDAY, destinations, emptyMap())
        assertTrue(result.isEmpty)
        assertEquals(RejectionReason.NO_INBOUND, result.dominantRejection)
    }

    @Test
    fun `rejects a return that leaves before the traveller could even reach the city`() {
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "22:00", 80),
            // Departs one hour after landing: the usable stay is negative.
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.FRIDAY, "23:20", 80),
        )
        val result = builder.build(flights, Fixtures.FRIDAY, destinations, emptyMap())
        assertTrue(result.isEmpty)
    }

    @Test
    fun `honours the maximum flight duration`() {
        val strict = TripBuilder(prefs.copy(maxFlightMinutes = 60))
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80),
        )
        val result = strict.build(flights, Fixtures.FRIDAY, destinations, emptyMap())
        assertTrue(result.isEmpty)
        assertEquals(RejectionReason.FLIGHT_TOO_LONG, result.dominantRejection)
    }

    @Test
    fun `honours the budget when a standby price is known`() {
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80),
        )
        val prices = mapOf(
            "LGW" to StandbyPrice("LGW", PriceEntryMode.ROUND_TRIP, economyOutboundCents = 90_000),
        )
        val result = builder.build(flights, Fixtures.FRIDAY, destinations, prices)
        assertTrue(result.isEmpty)
        assertEquals(RejectionReason.OVER_BUDGET, result.dominantRejection)
    }

    @Test
    fun `keeps only the best option per destination and pattern`() {
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "14:00", 80, flightNumber = "EARLY"),
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80, flightNumber = "LATE"),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80),
        )
        val result = builder.build(flights, Fixtures.FRIDAY, destinations, emptyMap())
        val friSun = result.trips.filter { it.pattern == WeekendPattern.FRI_SUN }
        assertEquals(1, friSun.size)
        assertEquals("LATE", friSun.single().outbound.flightNumber)
    }

    @Test
    fun `works for a destination with no editorial profile`() {
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.BUD, Fixtures.FRIDAY, "20:10", 90),
            Fixtures.flight(Fixtures.BUD, Fixtures.FRA, Fixtures.SUNDAY, "17:05", 90),
        )
        val bare = mapOf("BUD" to Destination(airport = Fixtures.BUD))
        val result = builder.build(flights, Fixtures.FRIDAY, bare, emptyMap())
        assertEquals(1, result.trips.size)
        assertNotNull(result.trips.single().score)
    }

    @Test
    fun `only searches the patterns the user enabled`() {
        val onlyFriSun = TripBuilder(prefs.copy(enabledPatterns = setOf(WeekendPattern.FRI_SUN)))
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.THURSDAY, "19:00", 80),
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "19:00", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:00", 80),
        )
        val result = onlyFriSun.build(flights, Fixtures.FRIDAY, destinations)
        assertEquals(setOf(WeekendPattern.FRI_SUN), result.trips.map { it.pattern }.toSet())
    }

    @Test
    fun `trips are sorted by descending score`() {
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80),
            Fixtures.flight(Fixtures.FRA, Fixtures.BUD, Fixtures.FRIDAY, "13:30", 90),
            Fixtures.flight(Fixtures.BUD, Fixtures.FRA, Fixtures.SUNDAY, "12:00", 90),
        )
        val result = builder.build(flights, Fixtures.FRIDAY, destinations, emptyMap())
        assertTrue(result.trips.size >= 2)
        result.trips.zipWithNext().forEach { (a, b) ->
            assertTrue(a.score.total >= b.score.total)
        }
        assertEquals("LGW", result.trips.first().iata)
    }

    private fun TripBuilder.build(
        flights: List<com.condorino.weekend.domain.model.Flight>,
        friday: java.time.LocalDate,
        destinations: Map<String, Destination>,
    ) = build(flights, friday, destinations, emptyMap())

    @Test
    fun `a weekend with no flights at all yields no outbound rejections silently`() {
        val result = builder.build(emptyList(), Fixtures.FRIDAY, destinations, emptyMap())
        assertTrue(result.isEmpty)
        assertEquals(RejectionReason.NO_OUTBOUND, result.dominantRejection)
        assertNull(result.trips.firstOrNull())
    }
}
