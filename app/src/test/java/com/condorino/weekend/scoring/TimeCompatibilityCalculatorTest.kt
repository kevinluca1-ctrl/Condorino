package com.condorino.weekend.scoring

import com.condorino.weekend.Fixtures
import com.condorino.weekend.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class TimeCompatibilityCalculatorTest {

    private val prefs = UserPreferences.DEFAULT
    private val calc = TimeCompatibilityCalculator(prefs)

    @Test
    fun `earliest reachable departure is work end plus travel plus buffer`() {
        // 17:00 + 45 min drive to FRA + 90 min airport buffer = 19:15
        assertEquals(LocalTime.of(19, 15), calc.earliestReachableDeparture())
    }

    @Test
    fun `no workday penalty for a departure at or after the earliest reachable time`() {
        val late = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "19:15", 80)
        assertEquals(0.0, calc.workdayPenalty(late.departureLocal), 1e-9)
        assertEquals(0L, calc.workingMinutesLost(late.departureLocal))
    }

    @Test
    fun `early afternoon departure costs working time`() {
        val early = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "13:00", 80)
        // 19:15 - 13:00 = 375 minutes of working time lost.
        assertEquals(375L, calc.workingMinutesLost(early.departureLocal))
        assertEquals(375.0 / 480.0, calc.workdayPenalty(early.departureLocal), 1e-9)
    }

    @Test
    fun `workday penalty is capped at one full working day`() {
        val veryEarly = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "05:00", 80)
        assertEquals(1.0, calc.workdayPenalty(veryEarly.departureLocal), 1e-9)
    }

    @Test
    fun `no penalty on a day the user does not work`() {
        val early = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "09:00", 80)
        assertEquals(0.0, calc.workdayPenalty(early.departureLocal, isWorkingDay = false), 1e-9)
    }

    @Test
    fun `effective time reproduces the London example from the brief`() {
        // FRA 18:15 -> LGW 18:35 local, back Sunday 19:35. Brief says roughly 46 h on site.
        val out = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80)
        val back = Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80)
        val effective = calc.effectiveTime(out, back, Fixtures.destination(Fixtures.LGW))
        assertEquals(46L, effective.toHours())
    }

    @Test
    fun `effective time subtracts transfer and airport buffer at both ends`() {
        val out = Fixtures.flight(Fixtures.FRA, Fixtures.BUD, Fixtures.FRIDAY, "20:10", 90)
        val back = Fixtures.flight(Fixtures.BUD, Fixtures.FRA, Fixtures.SUNDAY, "17:05", 90)
        val destination = Fixtures.destination(Fixtures.BUD, transferMinutes = 40)
        val effective = calc.effectiveTime(out, back, destination)

        // Raw gap 21:40 Fri -> 17:05 Sun = 43 h 25 min.
        // Minus 40 min transfer in, minus 90 min buffer + 40 min transfer out = 40 h 35 min.
        assertEquals(40L, effective.toHours())
        assertEquals(35L, effective.toMinutes() % 60)
    }

    @Test
    fun `nights are counted on the destination calendar`() {
        val out = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80)
        val back = Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "19:35", 80)
        assertEquals(2, calc.nights(out, back))

        val thursdayOut = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.THURSDAY, "19:55", 80)
        assertEquals(3, calc.nights(thursdayOut, back))

        val mondayBack = Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.MONDAY, "20:05", 80)
        assertEquals(4, calc.nights(thursdayOut, mondayBack))
    }

    @Test
    fun `overnight arrival past midnight still counts the nights correctly`() {
        // Departs Friday 23:30, lands Saturday 01:00 local in Athens.
        val out = Fixtures.flight(Fixtures.FRA, Fixtures.ATH, Fixtures.FRIDAY, "23:30", 170)
        val back = Fixtures.flight(Fixtures.ATH, Fixtures.FRA, Fixtures.SUNDAY, "19:00", 190)
        assertEquals("Ankunft am Samstag", 1, calc.nights(out, back))
        assertTrue(calc.effectiveTime(out, back, Fixtures.destination(Fixtures.ATH)).toHours() in 30..45)
    }

    @Test
    fun `late home arrival is detected`() {
        // Athens 22:40 EEST + 3 h 10 min = 00:50 CEST at FRA, home at 01:35 on Monday morning.
        val lateBack = Fixtures.flight(Fixtures.ATH, Fixtures.FRA, Fixtures.SUNDAY, "22:40", 190)
        assertTrue(calc.isLateHomeArrival(lateBack))

        val earlyBack = Fixtures.flight(Fixtures.LGW, Fixtures.FRA, Fixtures.SUNDAY, "17:00", 80)
        assertTrue(!calc.isLateHomeArrival(earlyBack))
    }

    @Test
    fun `departure buffer grows with a later departure`() {
        val atEarliest = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "19:15", 80)
        val twoHoursLater = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "21:15", 80)
        val beforeEarliest = Fixtures.flight(Fixtures.FRA, Fixtures.LGW, Fixtures.FRIDAY, "18:15", 80)
        assertEquals(0L, calc.departureBufferMinutes(atEarliest.departureLocal))
        assertEquals(120L, calc.departureBufferMinutes(twoHoursLater.departureLocal))
        // A departure before the earliest reachable time has no buffer at all, never a negative one.
        assertEquals(0L, calc.departureBufferMinutes(beforeEarliest.departureLocal))
    }
}
