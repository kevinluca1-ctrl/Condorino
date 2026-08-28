package com.condorino.weekend.scoring

import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.Flight
import com.condorino.weekend.domain.model.UserPreferences
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Turns raw departure/arrival instants into the two quantities the whole app revolves around:
 *
 *  1. **Workday penalty** — how much working time a departure costs.
 *  2. **Effective time on site** — the hours actually usable at the destination.
 *
 * Everything here works on wall-clock times in the *correct local zone* of each airport.
 */
class TimeCompatibilityCalculator(private val prefs: UserPreferences) {

    /** Full day of working time, used to normalise the workday penalty to 0..1. */
    private val fullWorkdayMinutes = 8 * 60.0

    /**
     * The earliest FRA departure that costs no working time at all.
     * work end (17:00) + drive to FRA (45 min) + airport buffer (90 min) = 18:15 by default.
     */
    fun earliestReachableDeparture(): LocalTime = prefs.earliestReachableDeparture

    /**
     * 0.0 = the flight leaves late enough that no working time is lost.
     * 1.0 = a full working day is lost.
     *
     * A Friday 18:15 departure (with the defaults) scores 0.0; Friday 13:00 leaves 315 min early
     * and therefore lands around 0.66 — which is exactly the difference the user asked for.
     *
     * Departures on a day the user does not work (e.g. the Monday of a Fr → Mo trip) never incur
     * a penalty here; the vacation day is accounted for in the weekend-compatibility component.
     */
    fun workdayPenalty(departureLocal: ZonedDateTime, isWorkingDay: Boolean = true): Double {
        if (!isWorkingDay) return 0.0
        val earliest = earliestReachableDeparture()
        val depTime = departureLocal.toLocalTime()
        if (!depTime.isBefore(earliest)) return 0.0
        val minutesEarly = Duration.between(depTime, earliest).toMinutes().toDouble()
        return ScoringMath.clamp(minutesEarly / fullWorkdayMinutes)
    }

    /** Working minutes lost by leaving early — shown as "Arbeitszeit verloren" on the detail page. */
    fun workingMinutesLost(departureLocal: ZonedDateTime, isWorkingDay: Boolean = true): Long {
        if (!isWorkingDay) return 0L
        val earliest = earliestReachableDeparture()
        val depTime = departureLocal.toLocalTime()
        if (!depTime.isBefore(earliest)) return 0L
        return Duration.between(depTime, earliest).toMinutes()
    }

    /**
     * Buffer between the earliest reachable departure and the actual departure, capped at 3 h.
     * More buffer = less stress after work, which the user explicitly asked to reward.
     */
    fun departureBufferMinutes(departureLocal: ZonedDateTime): Long {
        val earliest = earliestReachableDeparture()
        val depTime = departureLocal.toLocalTime()
        return Duration.between(earliest, depTime).toMinutes().coerceAtLeast(0L)
    }

    /**
     * Effective usable time at the destination:
     *
     *   arrival + airport→city transfer  …  return departure − airport buffer − city→airport transfer
     *
     * With the spec's London example (arrive Fri 18:35, depart Sun 19:35, 45 min transfer,
     * 90 min buffer) this yields 46 h — matching the figure in the brief.
     */
    fun effectiveTime(outbound: Flight, inbound: Flight, destination: Destination): Duration {
        val transfer = destination.transferMinutes.toLong()
        val usableStart = outbound.arrival.plusSeconds(transfer * 60)
        val usableEnd = inbound.departure
            .minusSeconds(prefs.returnAirportBufferMinutes.toLong() * 60)
            .minusSeconds(transfer * 60)
        return Duration.between(usableStart, usableEnd)
    }

    /**
     * Nights away, counted on the *destination* calendar: the number of local dates on which the
     * traveller actually sleeps at the destination. Handles trips that arrive after midnight.
     */
    fun nights(outbound: Flight, inbound: Flight): Int {
        val arrivalDate = outbound.arrivalLocal.toLocalDate()
        val departureDate = inbound.departureLocal.toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(arrivalDate, departureDate).toInt()
            .coerceAtLeast(0)
    }

    /** Local time the traveller is back at their front door in Heidelberg. */
    fun homeArrivalLocal(inbound: Flight): ZonedDateTime =
        inbound.arrivalLocal.plusMinutes(prefs.airportToHomeMinutes.toLong())

    /**
     * True if the return gets home so late that the next working morning suffers.
     *
     * Every supported pattern is followed by a working day (Monday resp. Tuesday), so this is
     * unconditional. Note that "late" has to cover both sides of midnight: a flight landing at
     * 00:50 gets home at 01:35, which is a time-of-day *before* the configured limit but is
     * obviously the worst case, so anything before 05:00 counts as late as well.
     */
    fun isLateHomeArrival(inbound: Flight): Boolean {
        val arrivalTime = homeArrivalLocal(inbound).toLocalTime()
        return arrivalTime.isAfter(prefs.latestHomeArrival) || arrivalTime.isBefore(EARLY_MORNING)
    }

    companion object {
        /** Getting home before this counts as a night-time arrival, not an early one. */
        private val EARLY_MORNING: LocalTime = LocalTime.of(5, 0)
    }
}
