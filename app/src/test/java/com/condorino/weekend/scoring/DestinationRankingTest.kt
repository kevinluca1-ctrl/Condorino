package com.condorino.weekend.scoring

import com.condorino.weekend.Fixtures
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.repository.DataStatus
import com.condorino.weekend.domain.repository.WeekendSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Ranking by destination instead of by weekend.
 *
 * The case that motivated this: a repeating weekly timetable makes every weekend in a range
 * identical, so a "best weekends" list showed the same destination and the same score four times.
 * These pin down that the destination view stays varied exactly where the weekend view collapses.
 */
class DestinationRankingTest {

    private val builder = TripBuilder(UserPreferences.DEFAULT)
    private val destinations = mapOf(
        "LGW" to Fixtures.destination(Fixtures.LGW),
        "BUD" to Fixtures.destination(Fixtures.BUD),
    )

    /** One weekend offering both destinations, exactly as a weekly timetable would repeat it. */
    private fun weekend(friday: LocalDate, includeBudapest: Boolean = true): WeekendSearchResult {
        val flights = buildList {
            add(Fixtures.flight(Fixtures.FRA, Fixtures.LGW, friday, "18:15", 80))
            add(Fixtures.flight(Fixtures.LGW, Fixtures.FRA, friday.plusDays(2), "19:35", 80))
            if (includeBudapest) {
                add(Fixtures.flight(Fixtures.FRA, Fixtures.BUD, friday, "20:10", 90))
                add(Fixtures.flight(Fixtures.BUD, Fixtures.FRA, friday.plusDays(2), "17:05", 90))
            }
        }
        val result = builder.build(flights, friday, destinations, emptyMap())
        return WeekendSearchResult(friday, result.trips, result.rejections, DataStatus.EMPTY)
    }

    private val fourIdenticalWeekends = (0L..3L).map { weekend(Fixtures.FRIDAY.plusWeeks(it)) }

    @Test
    fun `each destination appears once, however many weekends repeat it`() {
        // The weekend view showed four rows of the same city; this shows one row per city.
        val picks = DestinationRanking.bestDestinations(fourIdenticalWeekends)

        assertEquals(2, picks.size)
        assertEquals(setOf("LGW", "BUD"), picks.map { it.trip.iata }.toSet())
    }

    @Test
    fun `a destination reports how many weekends offer it`() {
        val picks = DestinationRanking.bestDestinations(fourIdenticalWeekends)
        assertTrue("every destination flies on all four weekends", picks.all { it.weekendCount == 4 })
    }

    @Test
    fun `a destination that only flies some weekends is counted accordingly`() {
        val weekends = listOf(
            weekend(Fixtures.FRIDAY, includeBudapest = true),
            weekend(Fixtures.FRIDAY.plusWeeks(1), includeBudapest = false),
            weekend(Fixtures.FRIDAY.plusWeeks(2), includeBudapest = false),
        )
        val picks = DestinationRanking.bestDestinations(weekends).associateBy { it.trip.iata }

        assertEquals(3, picks.getValue("LGW").weekendCount)
        assertEquals(1, picks.getValue("BUD").weekendCount)
    }

    @Test
    fun `picks are ordered by score, best first`() {
        val picks = DestinationRanking.bestDestinations(fourIdenticalWeekends)
        picks.zipWithNext().forEach { (a, b) ->
            assertTrue(a.trip.score.total >= b.trip.score.total)
        }
    }

    @Test
    fun `a pick points at the weekend its best trip belongs to`() {
        val picks = DestinationRanking.bestDestinations(fourIdenticalWeekends)
        // All four weekends are equivalent here, so the soonest is the one worth opening.
        assertTrue(picks.all { it.friday == Fixtures.FRIDAY })
    }

    @Test
    fun `the limit is honoured, and a nonsensical one yields nothing`() {
        assertEquals(1, DestinationRanking.bestDestinations(fourIdenticalWeekends, limit = 1).size)
        assertTrue(DestinationRanking.bestDestinations(fourIdenticalWeekends, limit = 0).isEmpty())
    }

    @Test
    fun `an empty range ranks nothing rather than failing`() {
        assertTrue(DestinationRanking.bestDestinations(emptyList()).isEmpty())
    }

    @Test
    fun `identical weekends are recognised as interchangeable`() {
        // This is what makes a weekend ranking meaningless, so the app can say so.
        assertTrue(DestinationRanking.weekendsAreInterchangeable(fourIdenticalWeekends))
    }

    @Test
    fun `weekends that genuinely differ are not called interchangeable`() {
        val weekends = listOf(
            weekend(Fixtures.FRIDAY, includeBudapest = true),
            weekend(Fixtures.FRIDAY.plusWeeks(1), includeBudapest = false),
        )
        assertFalse(DestinationRanking.weekendsAreInterchangeable(weekends))
    }

    @Test
    fun `a single weekend is never called interchangeable`() {
        assertFalse(DestinationRanking.weekendsAreInterchangeable(listOf(weekend(Fixtures.FRIDAY))))
        assertFalse(DestinationRanking.weekendsAreInterchangeable(emptyList()))
    }

    @Test
    fun `several patterns for one city on one weekend count as that city once`() {
        // A weekend can hold Fri-Sun and Thu-Sun for the same place; that is one weekend, not two.
        val friday = Fixtures.FRIDAY
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, friday.minusDays(1), "19:00", 80),
            Fixtures.flight(Fixtures.FRA, Fixtures.LGW, friday, "18:15", 80),
            Fixtures.flight(Fixtures.LGW, Fixtures.FRA, friday.plusDays(2), "19:35", 80),
        )
        val built = builder.build(flights, friday, destinations, emptyMap())
        assertTrue("fixture should produce more than one pattern", built.trips.size > 1)

        val picks = DestinationRanking.bestDestinations(
            listOf(WeekendSearchResult(friday, built.trips, built.rejections, DataStatus.EMPTY)),
        )
        assertEquals(1, picks.size)
        assertEquals(1, picks.single().weekendCount)
    }

    @Test
    fun `a destination with no editorial profile still ranks`() {
        val bare = mapOf("BUD" to Destination(airport = Fixtures.BUD))
        val flights = listOf(
            Fixtures.flight(Fixtures.FRA, Fixtures.BUD, Fixtures.FRIDAY, "20:10", 90),
            Fixtures.flight(Fixtures.BUD, Fixtures.FRA, Fixtures.FRIDAY.plusDays(2), "17:05", 90),
        )
        val built = builder.build(flights, Fixtures.FRIDAY, bare, emptyMap())
        val picks = DestinationRanking.bestDestinations(
            listOf(WeekendSearchResult(Fixtures.FRIDAY, built.trips, built.rejections, DataStatus.EMPTY)),
        )
        assertEquals(1, picks.size)
    }
}
