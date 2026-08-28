package com.condorino.weekend.scoring

import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.ComponentScore
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.DestinationType
import com.condorino.weekend.domain.model.Flight
import com.condorino.weekend.domain.model.ScoreComponent
import com.condorino.weekend.domain.model.StandbyPrice
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
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()

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

    // ---------------------------------------------------------------- 25 % Flugzeit-Komfort

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
        reasons: MutableList<String>,
    ): Pair<Double, String> {
        val outScore = outboundTimeScore(outbound, pattern, reasons)
        val inScore = inboundTimeScore(inbound, pattern, reasons)
        val value = ScoringMath.clamp100(0.5 * outScore + 0.5 * inScore)
        return value to "Hinflug ${outScore.roundToInt()}/100, Rückflug ${inScore.roundToInt()}/100"
    }

    /** 0..100 for the outbound leg. */
    internal fun outboundTimeScore(
        outbound: Flight,
        pattern: WeekendPattern,
        reasons: MutableList<String> = mutableListOf(),
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
                reasons += "sehr entspannter ${dayLabel(pattern.outboundDay)}-Abflug um ${fmt(depLocal)}"
            penalty <= 0.0 ->
                reasons += "${dayLabel(pattern.outboundDay)}-Abflug ${fmt(depLocal)} ohne Verlust von Arbeitszeit"
            penalty < 0.25 ->
                reasons += "leicht früher Abflug (${time.workingMinutesLost(depLocal)} min Arbeitszeit)"
            else ->
                reasons += "früher Abflug um ${fmt(depLocal)} – kostet Arbeitszeit"
        }
        if (civility < 1.0) reasons += "späte Ankunft um ${fmt(outbound.arrivalLocal)} Ortszeit"
        return value
    }

    /** 0..100 for the inbound leg — later is better, capped by a civilised arrival at home. */
    internal fun inboundTimeScore(
        inbound: Flight,
        pattern: WeekendPattern,
        reasons: MutableList<String> = mutableListOf(),
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
            reasons += "Rückkehr erst ${fmt(time.homeArrivalLocal(inbound))} zu Hause"
        }

        when {
            hour >= 18.0 -> reasons += "sehr später ${dayLabel(pattern.inboundDay)}-Rückflug um ${fmt(depLocal)}"
            hour >= 15.0 -> reasons += "${dayLabel(pattern.inboundDay)}-Rückflug um ${fmt(depLocal)}"
            else -> reasons += "früher Rückflug um ${fmt(depLocal)} – kostet Wochenende"
        }
        return ScoringMath.clamp100(value)
    }

    // ---------------------------------------------------------------- 20 % Aufenthaltsqualität

    internal fun stayQuality(
        effectiveTime: Duration,
        nights: Int,
        reasons: MutableList<String>,
        warnings: MutableList<String>,
    ): Pair<Double, String> {
        val hours = effectiveTime.toMinutes() / 60.0
        if (hours <= 0) {
            warnings += "Kein nutzbarer Aufenthalt – Rückflug zu früh."
            return 0.0 to "kein nutzbarer Aufenthalt"
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
            warnings += "Nur $nights Nächte – unter deinem Minimum von ${prefs.minNights}."
        }
        if (nights > prefs.maxNights) {
            value *= 0.7
            warnings += "$nights Nächte – über deinem Maximum von ${prefs.maxNights}."
        }

        if (hours >= 40) reasons += "gute Aufenthaltsdauer (${hours.roundToInt()} h vor Ort)"
        else if (hours < 20) reasons += "kurzer Aufenthalt (nur ${hours.roundToInt()} h vor Ort)"

        return ScoringMath.clamp100(value) to "${hours.roundToInt()} h effektiv, $nights Nächte"
    }

    // ---------------------------------------------------------------- 20 % Wochenend-Kompatibilität

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
        reasons: MutableList<String>,
    ): Pair<Double, String> {
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
        when {
            extraLeaveDays <= 0.05 -> reasons += "kein Urlaubstag nötig (${pattern.label})"
            extraLeaveDays < 1.0 -> reasons += "ca. ${formatLeaveDays(extraLeaveDays)} Urlaub nötig (${pattern.label})"
            else -> reasons += "${formatLeaveDays(extraLeaveDays)} Urlaub nötig (${pattern.label})"
        }

        val note = buildString {
            append(pattern.label)
            append(" · ")
            append(
                if (extraLeaveDays <= 0.05) "0 Urlaubstage"
                else "${formatLeaveDays(extraLeaveDays)} Urlaub",
            )
            if (!preferred) append(" · nicht in deinen Wunschmustern")
        }
        return ScoringMath.clamp100(value) to note
    }

    private fun formatLeaveDays(days: Double): String =
        if (days >= 0.95) "%.0f Tag%s".format(days, if (days >= 1.95) "e" else "")
        else "%.1f Tage".format(days).replace('.', ',')

    // ---------------------------------------------------------------- 10 % Logistik

    internal fun logistics(
        outbound: Flight,
        inbound: Flight,
        destination: Destination,
        reasons: MutableList<String>,
        warnings: MutableList<String>,
    ): Pair<Double, String> {
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
            warnings += "Kein Nonstop-Flug – Umsteigeverbindung."
        } else {
            reasons += "Nonstop in ${formatMinutes(avgMinutes.roundToInt().toLong())}"
        }

        if (time.isLateHomeArrival(inbound)) value *= 0.92

        return ScoringMath.clamp100(value) to
            "⌀ Flugzeit ${formatMinutes(avgMinutes.roundToInt().toLong())}, Transfer ${destination.transferMinutes} min"
    }

    // ---------------------------------------------------------------- 15 % Kosten

    internal fun cost(
        standbyPrice: StandbyPrice?,
        warnings: MutableList<String>,
    ): Pair<Double, String> {
        val price = standbyPrice?.roundTripFor(prefs.preferredCabin)
        if (price == null) {
            warnings += "Standby-Preis für ${prefs.preferredCabin.label} fehlt – Kosten neutral bewertet."
            return NEUTRAL_SCORE to "kein Standby-Preis hinterlegt"
        }
        val budget = prefs.maxBudgetCents.toDouble().coerceAtLeast(1.0)
        val ratio = price.cents / budget
        // Free → 100, half the budget → ~62, exactly the budget → 25, over budget → 0.
        val value = ScoringMath.piecewise(
            ratio,
            listOf(0.0 to 100.0, 0.25 to 88.0, 0.5 to 62.0, 0.75 to 44.0, 1.0 to 25.0, 1.3 to 0.0),
        )
        if (ratio > 1.0) warnings += "Über deinem Budget (${price.format()})."
        return ScoringMath.clamp100(value) to "${price.format()} Roundtrip ${prefs.preferredCabin.label}"
    }

    // ---------------------------------------------------------------- 10 % Destination Quality

    internal fun destinationQuality(destination: Destination): Pair<Double, String> {
        val profile = destination.profile
            ?: return NEUTRAL_SCORE to "keine Zielbewertung hinterlegt"

        val selected = prefs.enabledDestinationTypes.ifEmpty { DestinationType.entries.toSet() }
        val factors = selected.map { profile.factorFor(it) }
        val avg = factors.average()
        val best = factors.maxOrNull() ?: 5

        // Two thirds average fit, one third "is it outstanding at anything I care about".
        val value = ScoringMath.clamp100((avg * 10.0) * 0.65 + (best * 10.0) * 0.35)
        val topType = selected.maxByOrNull { profile.factorFor(it) }
        return value to "stark für ${topType?.label ?: "–"} (${best}/10)"
    }

    // ---------------------------------------------------------------- helpers

    private fun fmt(t: java.time.ZonedDateTime): String =
        "%02d:%02d".format(t.hour, t.minute)

    private fun dayLabel(day: java.time.DayOfWeek): String = when (day) {
        java.time.DayOfWeek.MONDAY -> "Montag"
        java.time.DayOfWeek.TUESDAY -> "Dienstag"
        java.time.DayOfWeek.WEDNESDAY -> "Mittwoch"
        java.time.DayOfWeek.THURSDAY -> "Donnerstag"
        java.time.DayOfWeek.FRIDAY -> "Freitag"
        java.time.DayOfWeek.SATURDAY -> "Samstag"
        java.time.DayOfWeek.SUNDAY -> "Sonntag"
    }

    private fun formatMinutes(minutes: Long): String =
        if (minutes < 60) "${minutes} min" else "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"

    companion object {
        /** Used when a factor genuinely cannot be evaluated. Never silently favourable. */
        const val NEUTRAL_SCORE = 50.0
    }
}
