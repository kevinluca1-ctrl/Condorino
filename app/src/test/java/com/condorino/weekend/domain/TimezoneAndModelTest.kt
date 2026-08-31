package com.condorino.weekend.domain

import com.condorino.weekend.Fixtures
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.Money
import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.WeekendPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimezoneTest {

    @Test
    fun `a Frankfurt departure is rendered in London local time on arrival`() {
        val flight = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80)
        assertEquals(18, flight.departureLocal.hour)
        assertEquals(15, flight.departureLocal.minute)
        // 80 minutes of flying, but London is one hour behind in September.
        assertEquals(18, flight.arrivalLocal.hour)
        assertEquals(35, flight.arrivalLocal.minute)
    }

    @Test
    fun `Athens is two hours ahead of Frankfurt in summer`() {
        val flight = Fixtures.flight(Fixtures.FRA, Fixtures.ATH, Fixtures.FRIDAY, "19:50", 170)
        assertEquals(19, flight.departureLocal.hour)
        // 19:50 + 2 h 50 min = 22:40 CEST = 23:40 EEST.
        assertEquals(23, flight.arrivalLocal.hour)
        assertEquals(40, flight.arrivalLocal.minute)
    }

    @Test
    fun `Madeira is one hour behind Frankfurt in summer`() {
        val flight = Fixtures.flight(Fixtures.FRA, Fixtures.FNC, Fixtures.FRIDAY, "17:10", 260)
        // 17:10 + 4 h 20 min = 21:30 CEST = 20:30 in Funchal.
        assertEquals(20, flight.arrivalLocal.hour)
        assertEquals(30, flight.arrivalLocal.minute)
    }

    @Test
    fun `winter and summer offsets differ for the same wall-clock departure`() {
        val summer = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, LocalDate.of(2026, 7, 3), "18:15", 80)
        val winter = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, LocalDate.of(2026, 12, 4), "18:15", 80)
        // Both leave at 18:15 local Frankfurt time, but they are different UTC instants.
        assertEquals(18, summer.departureLocal.hour)
        assertEquals(18, winter.departureLocal.hour)
        assertNotEquals(
            summer.departure.atZone(java.time.ZoneOffset.UTC).hour,
            winter.departure.atZone(java.time.ZoneOffset.UTC).hour,
        )
    }

    @Test
    fun `an unknown time zone falls back to UTC instead of crashing`() {
        val broken = Airport("XXX", "Broken", "Nowhere", "?", "??", "Not/AZone")
        assertEquals(java.time.ZoneId.of("UTC"), broken.zone)
    }

    @Test
    fun `flags are derived from the ISO country code`() {
        assertEquals("🇬🇧", Fixtures.LGW.flag)
        assertEquals("🇩🇪", Airport.FRANKFURT.flag)
        assertEquals("🏳", Airport("XXX", "x", "x", "x", "!", "UTC").flag)
    }
}

class WeekendPatternTest {

    @Test
    fun `patterns are detected from the two weekdays`() {
        assertEquals(
            WeekendPattern.FRI_SUN,
            WeekendPattern.detect(java.time.DayOfWeek.FRIDAY, java.time.DayOfWeek.SUNDAY),
        )
        assertEquals(
            WeekendPattern.THU_MON,
            WeekendPattern.detect(java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.MONDAY),
        )
        assertNull(WeekendPattern.detect(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY))
    }

    @Test
    fun `only the Monday patterns cost a vacation day`() {
        assertEquals(0, WeekendPattern.FRI_SUN.vacationDaysRequired)
        assertEquals(0, WeekendPattern.THU_SUN.vacationDaysRequired)
        assertEquals(1, WeekendPattern.FRI_MON.vacationDaysRequired)
        assertEquals(1, WeekendPattern.THU_MON.vacationDaysRequired)
    }

    @Test
    fun `priority order matches the brief`() {
        assertEquals(
            listOf(
                WeekendPattern.FRI_SUN,
                WeekendPattern.THU_SUN,
                WeekendPattern.FRI_MON,
                WeekendPattern.THU_MON,
            ),
            WeekendPattern.byPriority,
        )
    }
}

class StandbyPriceTest {

    @Test
    fun `per-segment prices are summed into a round trip`() {
        val price = StandbyPrice(
            "LGW", PriceEntryMode.PER_SEGMENT,
            economyOutboundCents = 4_500, economyInboundCents = 4_500,
            businessOutboundCents = 11_000, businessInboundCents = 11_000,
        )
        assertEquals(Money(9_000), price.economyRoundTrip)
        assertEquals(Money(22_000), price.businessRoundTrip)
    }

    @Test
    fun `a single per-segment price is mirrored onto the return leg`() {
        val price = StandbyPrice("BUD", PriceEntryMode.PER_SEGMENT, economyOutboundCents = 3_800)
        assertEquals(Money(7_600), price.economyRoundTrip)
    }

    @Test
    fun `round-trip mode takes the entered figure as the total`() {
        val price = StandbyPrice("BUD", PriceEntryMode.ROUND_TRIP, economyOutboundCents = 7_600)
        assertEquals(Money(7_600), price.economyRoundTrip)
    }

    @Test
    fun `taxes are added once per round trip`() {
        val price = StandbyPrice(
            "BUD", PriceEntryMode.PER_SEGMENT,
            economyOutboundCents = 3_800, economyInboundCents = 3_800, taxesCents = 2_500,
        )
        assertEquals(Money(10_100), price.economyRoundTrip)
    }

    @Test
    fun `a missing price stays null rather than becoming zero`() {
        val price = StandbyPrice.empty("PRG")
        assertNull(price.economyRoundTrip)
        assertNull(price.businessRoundTrip)
        assertTrue(!price.hasAnyPrice)
    }

    @Test
    fun `money formats per locale`() {
        val de = java.util.Locale.GERMANY
        assertEquals("90 €", Money(9_000).format(de))
        assertEquals("76,50 €", Money(7_650).format(de))

        val us = java.util.Locale.US
        assertEquals("€90", Money(9_000).format(us))
        assertEquals("€76.50", Money(7_650).format(us))
    }

    @Test
    fun `a destination label always carries its airport code`() {
        // Four Londons exist in the reference data; the city alone cannot tell them apart.
        val heathrow = Airport("LHR", "Heathrow", "London", "United Kingdom", "GB", "Europe/London")
        val gatwick = Airport("LGW", "Gatwick", "London", "United Kingdom", "GB", "Europe/London")

        assertEquals("London (LHR)", heathrow.cityWithCode)
        assertEquals("London (LGW)", gatwick.cityWithCode)
        assertNotEquals(heathrow.cityWithCode, gatwick.cityWithCode)
    }

    @Test
    fun `a label falls back to the airport name when the city is missing`() {
        val nameless = Airport("XYZ", "Some Airfield", "  ", "Nowhere", "NO", "UTC")
        assertEquals("Some Airfield (XYZ)", nameless.cityWithCode)
    }
}
