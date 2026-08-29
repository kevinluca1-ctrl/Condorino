package com.condorino.weekend.domain.model

import java.time.Duration
import java.time.LocalDate

/**
 * A concrete candidate trip: one outbound leg, one inbound leg, and everything the app derived
 * from them. Produced by [com.condorino.weekend.scoring.TripBuilder] and scored by
 * [com.condorino.weekend.scoring.TripScoringEngine].
 */
data class WeekendTrip(
    val outbound: Flight,
    val inbound: Flight,
    val destination: Destination,
    val pattern: WeekendPattern,
    val nights: Int,
    val effectiveTime: Duration,
    val standbyPrice: StandbyPrice?,
    val score: TripScore,
    /** Weakest provenance of the two legs — the trip is only as trustworthy as its worst leg. */
    val provenance: DataProvenance,
) {
    val id: String get() = "${outbound.key}__${inbound.key}"
    val iata: String get() = destination.iata

    val economyPrice: Money? get() = standbyPrice?.economyRoundTrip
    val businessPrice: Money? get() = standbyPrice?.businessRoundTrip

    fun priceFor(cabin: Cabin): Money? = standbyPrice?.roundTripFor(cabin)

    /** Saturday/Sunday of the weekend this trip belongs to — used to group calendar results. */
    val weekendAnchor: LocalDate
        get() {
            val d = outbound.departureDateLocal
            return when (pattern.outboundDay) {
                java.time.DayOfWeek.THURSDAY -> d.plusDays(1)
                else -> d
            }
        }

    val effectiveHoursText: String
        get() {
            val h = effectiveTime.toHours()
            val m = effectiveTime.toMinutes() % 60
            return "${h} h ${m.toString().padStart(2, '0')} min"
        }
}
