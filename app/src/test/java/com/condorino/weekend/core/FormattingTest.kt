package com.condorino.weekend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

class FormattingTest {

    private val de = Locale.GERMANY
    private val us = Locale.US
    private val date = LocalDate.of(2026, 9, 4) // a Friday

    @Test
    fun `English dates are day-month, never MM-DD-YYYY`() {
        val short = Formatting.shortDate(date, us)
        val long = Formatting.longDate(date, us)
        val day = Formatting.dayDate(date, us)

        // The day number must come before the month name in every English date format.
        assertTrue("short was $short", short.startsWith("04"))
        assertTrue("long was $long", long.contains("04 Sep"))
        assertTrue("dayDate was $day", day.contains("04 Sep"))

        // Explicitly assert the US month-first pattern never appears.
        listOf(short, long, day).forEach {
            assertFalse("month-first slipped into '$it'", Regex("""\b09[/.-]04\b""").containsMatchIn(it))
        }
    }

    @Test
    fun `German dates keep the dotted day-month form`() {
        assertEquals("04.09.", Formatting.shortDate(date, de))
        assertTrue(Formatting.longDate(date, de).contains("04.09.2026"))
    }

    @Test
    fun `times are 24-hour in both languages`() {
        val t = java.time.LocalTime.of(18, 15)
        assertEquals("18:15", Formatting.time(t, de))
        assertEquals("18:15", Formatting.time(t, us))
        assertFalse(Formatting.time(t, us).lowercase().contains("pm"))
    }

    @Test
    fun `durations read the same in both languages`() {
        assertEquals("1 h 20 min", Formatting.duration(Duration.ofMinutes(80)))
        assertEquals("45 min", Formatting.duration(Duration.ofMinutes(45)))
        assertEquals("2 h 00 min", Formatting.duration(Duration.ofMinutes(120)))
    }

    @Test
    fun `a negative duration never renders as negative`() {
        assertEquals("0 min", Formatting.duration(Duration.ofMinutes(-30)))
    }
}
