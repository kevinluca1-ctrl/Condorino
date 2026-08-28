package com.condorino.weekend.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class WeekendCalendarTest {

    @Test
    fun `a weekday maps to the coming Friday`() {
        // 2026-09-01 is a Tuesday.
        assertEquals(LocalDate.of(2026, 9, 4), WeekendCalendar.anchorFriday(LocalDate.of(2026, 9, 1)))
    }

    @Test
    fun `Friday maps to itself`() {
        val friday = LocalDate.of(2026, 9, 4)
        assertEquals(friday, WeekendCalendar.anchorFriday(friday))
    }

    @Test
    fun `Saturday and Sunday map back to the weekend already in progress`() {
        val friday = LocalDate.of(2026, 9, 4)
        assertEquals(friday, WeekendCalendar.anchorFriday(friday.plusDays(1)))
        assertEquals(friday, WeekendCalendar.anchorFriday(friday.plusDays(2)))
    }

    @Test
    fun `every anchor is a Friday`() {
        var date = LocalDate.of(2026, 1, 1)
        repeat(400) {
            assertEquals(DayOfWeek.FRIDAY, WeekendCalendar.anchorFriday(date).dayOfWeek)
            date = date.plusDays(1)
        }
    }

    @Test
    fun `fridaysBetween returns every Friday in the range inclusive`() {
        val fridays = WeekendCalendar.fridaysBetween(
            LocalDate.of(2026, 9, 4),
            LocalDate.of(2026, 9, 30),
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 18),
                LocalDate.of(2026, 9, 25),
            ),
            fridays,
        )
    }

    @Test
    fun `an inverted range yields nothing`() {
        assertTrue(
            WeekendCalendar.fridaysBetween(
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 9, 1),
            ).isEmpty(),
        )
    }

    @Test
    fun `the search window spans Thursday to Monday`() {
        val window = WeekendCalendar.searchWindow(LocalDate.of(2026, 9, 4))
        assertEquals(DayOfWeek.THURSDAY, window.start.dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, window.endInclusive.dayOfWeek)
        assertEquals(LocalDate.of(2026, 9, 3), window.start)
        assertEquals(LocalDate.of(2026, 9, 7), window.endInclusive)
    }
}
