package com.condorino.weekend.scoring

import com.condorino.weekend.Fixtures
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.key
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
        // airlineIcao = "TT" to match Fixtures.flight's own fixed airlineCode — the map is keyed
        // by (destination, airline), and TripBuilder only picks up a price whose airline matches
        // the specific flight actually being scored.
        val price = StandbyPrice("LGW", PriceEntryMode.ROUND_TRIP, economyOutboundCents = 90_000, airlineIcao = "TT")
        val prices = mapOf(price.key to price)
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
    fun `the most informative rejection wins over the most frequent one`() {
        // Only Friday flights exist, so the two Thursday patterns each add a NO_OUTBOUND. Counting
        // occurrences would surface "no Thursday flight" and bury the reason that actually matters.
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80),
        )
        // airlineIcao = "TT" to match Fixtures.flight's own fixed airlineCode — the map is keyed
        // by (destination, airline), and TripBuilder only picks up a price whose airline matches
        // the specific flight actually being scored.
        val price = StandbyPrice("LGW", PriceEntryMode.ROUND_TRIP, economyOutboundCents = 90_000, airlineIcao = "TT")
        val prices = mapOf(price.key to price)
        val result = builder.build(flights, Fixtures.FRIDAY, destinations, prices)
        assertTrue(result.rejections.getOrDefault(RejectionReason.NO_OUTBOUND, 0) >= 2)
        assertEquals(1, result.rejections[RejectionReason.OVER_BUDGET])
        assertEquals(RejectionReason.OVER_BUDGET, result.dominantRejection)
    }

    @Test
    fun `a weekend with no flights at all yields no outbound rejections silently`() {
        val result = builder.build(emptyList(), Fixtures.FRIDAY, destinations, emptyMap())
        assertTrue(result.isEmpty)
        assertEquals(RejectionReason.NO_OUTBOUND, result.dominantRejection)
        assertNull(result.trips.firstOrNull())
    }

    @Test
    fun `a Condor price applies to a flight whose source reported the IATA code`() {
        // The official Condor Developer API reports "DE" while prices are stored as "CFG": before
        // these were resolved rather than string-compared, every trip from it read "not set".
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80, airlineCode = "DE"),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80, airlineCode = "DE"),
        )
        val price = StandbyPrice("LGW", PriceEntryMode.ROUND_TRIP, economyOutboundCents = 9_000, airlineIcao = "CFG")
        val result = builder.build(flights, Fixtures.FRIDAY, destinations, mapOf(price.key to price))

        assertEquals(9_000L, result.trips.first().standbyPrice?.economyOutboundCents)
    }

    @Test
    fun `a price still applies to a flight whose airline the app cannot identify`() {
        // The bundled demo schedule uses "XX"; such a flight is unattributed, not another airline,
        // so the user's own Condor price is what belongs against it.
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80, airlineCode = "XX"),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80, airlineCode = "XX"),
        )
        val price = StandbyPrice("LGW", PriceEntryMode.ROUND_TRIP, economyOutboundCents = 9_000, airlineIcao = "CFG")
        val result = builder.build(flights, Fixtures.FRIDAY, destinations, mapOf(price.key to price))

        assertEquals(9_000L, result.trips.first().standbyPrice?.economyOutboundCents)
    }

    @Test
    fun `a Lufthansa flight never picks up the Condor price`() {
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80, airlineCode = "LH"),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80, airlineCode = "LH"),
        )
        val price = StandbyPrice("LGW", PriceEntryMode.ROUND_TRIP, economyOutboundCents = 9_000, airlineIcao = "CFG")
        val result = builder.build(flights, Fixtures.FRIDAY, destinations, mapOf(price.key to price))

        assertNull(result.trips.first().standbyPrice)
    }
}
