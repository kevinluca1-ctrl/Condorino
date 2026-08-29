package com.condorino.weekend.scoring

import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.ComponentDetail
import com.condorino.weekend.domain.model.ComponentScore
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.DestinationType
import com.condorino.weekend.domain.model.Flight
import com.condorino.weekend.domain.model.ScoreComponent
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.TripInsight
import com.condorino.weekend.domain.model.TripScore
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.WeekendPattern
import java.time.Duration
import kotlin.math.roundToInt

/**
 * Scores a candidate trip from 0 to 100.
 *
 * The engine is deliberately pure and free of Android dependencies so that every rule can be
 * unit-tested (see `app/src/test/.../scoring`). Each component returns a 0..100 sub-score plus a
 * human-readable explanation; the total is the weight-normalised sum.
 */
class TripScoringEngine(
    private val prefs: UserPreferences,
    private val time: TimeCompatibilityCalculator = TimeCompatibilityCalculator(prefs),
) {

    fun score(
        outbound: Flight,
        inbound: Flight,
        destination: Destination,
        pattern: WeekendPattern,
        standbyPrice: StandbyPrice?,
        effectiveTime: Duration,
        nights: Int,
    ): TripScore {
        val reasons = mutableListOf<TripInsight>()
        val warnings = mutableListOf<TripInsight>()

        val comfort = flightTimeComfort(outbound, inbound, pattern, reasons)
        val stay = stayQuality(effectiveTime, nights, reasons, warnings)
        val outboundPenalty = time.workdayPenalty(outbound.departureLocal)
        val weekend = weekendCompatibility(pattern, outboundPenalty, reasons)
        val logistics = logistics(outbound, inbound, destination, reasons, warnings)
        val cost = cost(standbyPrice, warnings)
        val quality = destinationQuality(destination)

        val components = listOf(
            ComponentScore(ScoreComponent.FLIGHT_TIME_COMFORT, comfort.first, prefs.weights.flightTimeComfort, comfort.second),
            ComponentScore(ScoreComponent.STAY_QUALITY, stay.first, prefs.weights.stayQuality, stay.second),
            ComponentScore(ScoreComponent.WEEKEND_COMPATIBILITY, weekend.first, prefs.weights.weekendCompatibility, weekend.second),
            ComponentScore(ScoreComponent.LOGISTICS, logistics.first, prefs.weights.logistics, logistics.second),
            ComponentScore(ScoreComponent.COST, cost.first, prefs.weights.cost, cost.second),
            ComponentScore(ScoreComponent.DESTINATION_QUALITY, quality.first, prefs.weights.destinationQuality, quality.second),
        )

        val weightSum = components.sumOf { it.weight }
        val total = if (weightSum <= 0.0) 0.0
        else ScoringMath.clamp100(components.sumOf { it.weighted } / weightSum)

        return TripScore(
            total = total,
            components = components,
            reasons = reasons.take(6),
            warnings = warnings,
        )
    }

    // ---------------------------------------------------------------- 25 % flight-time comfort

    /**
     * Rewards late outbound departures (no working time lost, plenty of buffer after work) and
     * late inbound departures (the weekend is used to the last hour).
     *
     * This is the component that makes `Fr 18:15 → So 19:35` beat `Fr 13:00 → So 15:00`.
     */
    internal fun flightTimeComfort(
        outbound: Flight,
        inbound: Flight,
        pattern: WeekendPattern,
        reasons: MutableList<TripInsight>,
    ): Pair<Double, ComponentDetail> {
        val outScore = outboundTimeScore(outbound, pattern, reasons)
        val inScore = inboundTimeScore(inbound, pattern, reasons)
        val value = ScoringMath.clamp100(0.5 * outScore + 0.5 * inScore)
        return value to ComponentDetail.FlightTimeComfort(outScore.roundToInt(), inScore.roundToInt())
    }

    /** 0..100 for the outbound leg. */
    internal fun outboundTimeScore(
        outbound: Flight,
        pattern: WeekendPattern,
        reasons: MutableList<TripInsight> = mutableListOf(),
    ): Double {
        val depLocal = outbound.departureLocal
        // Thursday and Friday are both working days for the traveller.
        val penalty = time.workdayPenalty(depLocal, isWorkingDay = true)
        val base = 100.0 * (1.0 - penalty)

        // Reward buffer after work, saturating at 2 h — this is worth up to 20 % of the sub-score.
        val buffer = time.departureBufferMinutes(depLocal).toDouble()
        val bufferFactor = 0.80 + 0.20 * ScoringMath.clamp(buffer / 120.0)

        // Arriving in the middle of the night costs comfort even though it costs no work time.
        val arrHour = outbound.arrivalLocal.toLocalTime().toSecondOfDay() / 3600.0
        val civility = when {
            arrHour in 5.0..23.99 -> 1.0
            arrHour < 1.0 -> 0.92
            arrHour < 2.5 -> 0.80
            else -> 0.68
        }

        val value = ScoringMath.clamp100(base * bufferFactor * civility)

        when {
            penalty <= 0.0 && buffer >= 60 ->
                reasons += TripInsight.RelaxedDeparture(pattern.outboundDay, depLocal.toLocalTime())
            penalty <= 0.0 ->
                reasons += TripInsight.DepartureCostsNoWork(pattern.outboundDay, depLocal.toLocalTime())
            penalty < 0.25 ->
                reasons += TripInsight.SlightWorkTimeLost(time.workingMinutesLost(depLocal))
            else ->
                reasons += TripInsight.EarlyDepartureCostsWork(depLocal.toLocalTime())
        }
        if (civility < 1.0) {
            reasons += TripInsight.LateArrival(outbound.arrivalLocal.toLocalTime())
        }
        return value
    }

    /** 0..100 for the inbound leg — later is better, capped by a civilised arrival at home. */
    internal fun inboundTimeScore(
        inbound: Flight,
        pattern: WeekendPattern,
        reasons: MutableList<TripInsight> = mutableListOf(),
    ): Double {
        val depLocal = inbound.departureLocal
        val hour = depLocal.toLocalTime().toSecondOfDay() / 3600.0

        // 08:00 → 0, 21:00 → 100. A 15:00 return therefore lands at ~54, a 19:35 return at ~89.
        val base = ScoringMath.piecewise(
            hour,
            listOf(6.0 to 0.0, 8.0 to 5.0, 12.0 to 35.0, 15.0 to 55.0, 18.0 to 82.0, 21.0 to 100.0, 23.5 to 100.0),
        )

        var value = base
        if (time.isLateHomeArrival(inbound)) {
            // Home after midnight before a working day: still better than losing the afternoon,
            // but no longer a perfect return.
            value *= 0.88
            reasons += TripInsight.HomeLate(time.homeArrivalLocal(inbound).toLocalTime())
        }

        when {
            hour >= 18.0 -> reasons += TripInsight.VeryLateReturn(pattern.inboundDay, depLocal.toLocalTime())
            hour >= 15.0 -> reasons += TripInsight.Return(pattern.inboundDay, depLocal.toLocalTime())
            else -> reasons += TripInsight.EarlyReturnCostsWeekend(depLocal.toLocalTime())
        }
        return ScoringMath.clamp100(value)
    }

    // ---------------------------------------------------------------- 20 % stay quality

    internal fun stayQuality(
        effectiveTime: Duration,
        nights: Int,
        reasons: MutableList<TripInsight>,
        warnings: MutableList<TripInsight>,
    ): Pair<Double, ComponentDetail> {
        val hours = effectiveTime.toMinutes() / 60.0
        if (hours <= 0) {
            warnings += TripInsight.NoUsableStay
            return 0.0 to ComponentDetail.NoStay
        }

        // A weekend trip is at its best between ~40 h and ~60 h on site.
        val base = ScoringMath.piecewise(
            hours,
            listOf(
                0.0 to 0.0, 8.0 to 15.0, 16.0 to 40.0, 24.0 to 62.0, 32.0 to 80.0,
                40.0 to 96.0, 46.0 to 100.0, 60.0 to 100.0, 72.0 to 92.0, 96.0 to 78.0,
            ),
        )

        var value = base
        if (nights < prefs.minNights) {
            value *= 0.6
            warnings += TripInsight.NightsBelowMinimum(nights, prefs.minNights)
        }
        if (nights > prefs.maxNights) {
            value *= 0.7
            warnings += TripInsight.NightsAboveMaximum(nights, prefs.maxNights)
        }

        if (hours >= 40) reasons += TripInsight.GoodStayLength(hours.roundToInt())
        else if (hours < 20) reasons += TripInsight.ShortStay(hours.roundToInt())

        return ScoringMath.clamp100(value) to
            ComponentDetail.StayQuality(hours.roundToInt(), nights)
    }

    // ---------------------------------------------------------------- 20 % weekend compatibility

    /**
     * How well the trip fits a weekend *without spending time off*.
     *
     * Two things cost time off, and both belong here:
     *  1. the pattern itself — returning on Monday means Monday is not a working day;
     *  2. leaving before the earliest reachable departure, which eats into the Thursday or
     *     Friday working day. A 13:00 departure is effectively a half day of leave, and the
     *     score has to say so, not just shrug it off as "less comfortable".
     */
    internal fun weekendCompatibility(
        pattern: WeekendPattern,
        outboundWorkdayPenalty: Double,
        reasons: MutableList<TripInsight>,
    ): Pair<Double, ComponentDetail> {
        // Priority order from the spec, expressed through the cost in vacation days.
        val base = when (pattern) {
            WeekendPattern.FRI_SUN -> 100.0
            WeekendPattern.THU_SUN -> 88.0
            WeekendPattern.FRI_MON -> 72.0
            WeekendPattern.THU_MON -> 62.0
        }
        val preferred = pattern in prefs.enabledPatterns

        // A full lost working day removes 45 % of this component.
        val leaveFactor = 1.0 - 0.45 * ScoringMath.clamp(outboundWorkdayPenalty)
        val value = base * leaveFactor * (if (preferred) 1.0 else 0.5)

        val extraLeaveDays = pattern.vacationDaysRequired + outboundWorkdayPenalty
        reasons += if (extraLeaveDays <= 0.05) {
            TripInsight.NoLeaveNeeded(pattern)
        } else {
            TripInsight.LeaveNeeded(pattern, extraLeaveDays)
        }

        return ScoringMath.clamp100(value) to
            ComponentDetail.WeekendFit(pattern, extraLeaveDays, preferred)
    }

    // ---------------------------------------------------------------- 10 % logistics

    internal fun logistics(
        outbound: Flight,
        inbound: Flight,
        destination: Destination,
        reasons: MutableList<TripInsight>,
        warnings: MutableList<TripInsight>,
    ): Pair<Double, ComponentDetail> {
        val avgMinutes = (outbound.duration.toMinutes() + inbound.duration.toMinutes()) / 2.0

        // 60 min → 100, at the configured maximum → 25, beyond → 0.
        val durationScore = ScoringMath.piecewise(
            avgMinutes,
            listOf(
                45.0 to 100.0,
                60.0 to 100.0,
                prefs.maxFlightMinutes * 0.6 to 78.0,
                prefs.maxFlightMinutes.toDouble() to 30.0,
                prefs.maxFlightMinutes * 1.4 to 0.0,
            ),
        )

        val transferScore = ScoringMath.piecewise(
            destination.transferMinutes.toDouble(),
            listOf(10.0 to 100.0, 30.0 to 92.0, 45.0 to 80.0, 60.0 to 65.0, 90.0 to 35.0, 120.0 to 10.0),
        )

        var value = 0.6 * durationScore + 0.4 * transferScore

        if (!outbound.isDirect || !inbound.isDirect) {
            value *= 0.45
            warnings += TripInsight.NotNonstop
        } else {
            reasons += TripInsight.Nonstop(avgMinutes.roundToInt().toLong())
        }

        if (time.isLateHomeArrival(inbound)) value *= 0.92

        return ScoringMath.clamp100(value) to
            ComponentDetail.Logistics(avgMinutes.roundToInt().toLong(), destination.transferMinutes)
    }

    // ---------------------------------------------------------------- 15 % cost

    internal fun cost(
        standbyPrice: StandbyPrice?,
        warnings: MutableList<TripInsight>,
    ): Pair<Double, ComponentDetail> {
        val price = standbyPrice?.roundTripFor(prefs.preferredCabin)
        if (price == null) {
            warnings += TripInsight.MissingStandbyPrice(prefs.preferredCabin)
            return NEUTRAL_SCORE to ComponentDetail.Cost(null, prefs.preferredCabin)
        }
        val budget = prefs.maxBudgetCents.toDouble().coerceAtLeast(1.0)
        val ratio = price.cents / budget
        // Free → 100, half the budget → ~62, exactly the budget → 25, over budget → 0.
        val value = ScoringMath.piecewise(
            ratio,
            listOf(0.0 to 100.0, 0.25 to 88.0, 0.5 to 62.0, 0.75 to 44.0, 1.0 to 25.0, 1.3 to 0.0),
        )
        if (ratio > 1.0) warnings += TripInsight.OverBudget(price)
        return ScoringMath.clamp100(value) to ComponentDetail.Cost(price, prefs.preferredCabin)
    }

    // ---------------------------------------------------------------- 10 % destination quality

    internal fun destinationQuality(destination: Destination): Pair<Double, ComponentDetail> {
        val profile = destination.profile
            ?: return NEUTRAL_SCORE to ComponentDetail.NoDestinationProfile

        val selected = prefs.enabledDestinationTypes.ifEmpty { DestinationType.entries.toSet() }
        val factors = selected.map { profile.factorFor(it) }
        val avg = factors.average()
        val best = factors.maxOrNull() ?: 5

        // Two thirds average fit, one third "is it outstanding at anything I care about".
        val value = ScoringMath.clamp100((avg * 10.0) * 0.65 + (best * 10.0) * 0.35)
        val topType = selected.maxByOrNull { profile.factorFor(it) }
        return value to ComponentDetail.DestinationQuality(topType, best)
    }

    companion object {
        /** Used when a factor genuinely cannot be evaluated. Never silently favourable. */
        const val NEUTRAL_SCORE = 50.0
    }
}
