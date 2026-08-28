package com.condorino.weekend.domain.model

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A finding the scoring engine wants to tell the user about, expressed as **data rather than a
 * sentence**.
 *
 * The engine is deliberately free of Android and of any language: it reports *what* it found and
 * the UI decides how to say it. That is what lets the same score explain itself in German or in
 * English without the engine knowing either language exists.
 */
sealed interface TripInsight {

    // ---- outbound ---------------------------------------------------------
    data class RelaxedDeparture(val day: DayOfWeek, val time: LocalTime) : TripInsight
    data class DepartureCostsNoWork(val day: DayOfWeek, val time: LocalTime) : TripInsight
    data class SlightWorkTimeLost(val minutes: Long) : TripInsight
    data class EarlyDepartureCostsWork(val time: LocalTime) : TripInsight
    data class LateArrival(val time: LocalTime) : TripInsight

    // ---- inbound ----------------------------------------------------------
    data class VeryLateReturn(val day: DayOfWeek, val time: LocalTime) : TripInsight
    data class Return(val day: DayOfWeek, val time: LocalTime) : TripInsight
    data class EarlyReturnCostsWeekend(val time: LocalTime) : TripInsight
    data class HomeLate(val time: LocalTime) : TripInsight

    // ---- stay -------------------------------------------------------------
    data class GoodStayLength(val hours: Int) : TripInsight
    data class ShortStay(val hours: Int) : TripInsight

    // ---- pattern ----------------------------------------------------------
    data class NoLeaveNeeded(val pattern: WeekendPattern) : TripInsight
    data class LeaveNeeded(val pattern: WeekendPattern, val days: Double) : TripInsight

    // ---- logistics --------------------------------------------------------
    data class Nonstop(val minutes: Long) : TripInsight

    // ---- warnings ---------------------------------------------------------
    data object NoUsableStay : TripInsight
    data class NightsBelowMinimum(val nights: Int, val minimum: Int) : TripInsight
    data class NightsAboveMaximum(val nights: Int, val maximum: Int) : TripInsight
    data class MissingStandbyPrice(val cabin: Cabin) : TripInsight
    data class OverBudget(val price: Money) : TripInsight
    data object NotNonstop : TripInsight
}

/**
 * The numeric backing of a score component's one-line explanation. Same idea as [TripInsight]:
 * numbers here, words in the UI.
 */
sealed interface ComponentDetail {
    data class FlightTimeComfort(val outboundScore: Int, val inboundScore: Int) : ComponentDetail
    data class StayQuality(val hours: Int, val nights: Int) : ComponentDetail
    data class WeekendFit(
        val pattern: WeekendPattern,
        val leaveDays: Double,
        val preferred: Boolean,
    ) : ComponentDetail
    data class Logistics(val averageFlightMinutes: Long, val transferMinutes: Int) : ComponentDetail
    data class Cost(val price: Money?, val cabin: Cabin) : ComponentDetail
    data class DestinationQuality(val topType: DestinationType?, val best: Int) : ComponentDetail
    data object NoStay : ComponentDetail
    data object NoDestinationProfile : ComponentDetail
}
