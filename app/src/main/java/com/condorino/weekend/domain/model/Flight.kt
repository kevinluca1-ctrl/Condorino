package com.condorino.weekend.domain.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * One flight leg. [departure] and [arrival] are absolute instants (UTC) — all wall-clock rendering
 * goes through [departureLocal] / [arrivalLocal], which apply the respective airport time zone.
 */
data class Flight(
    val flightNumber: String?,
    val airline: String,
    val airlineCode: String,
    val origin: Airport,
    val destination: Airport,
    val departure: Instant,
    val arrival: Instant,
    val isDirect: Boolean,
    val provenance: DataProvenance,
    val retrievedAt: Instant? = null,
    /** Only present when the data source reports it; null means "unknown", never "free". */
    val cashFareCents: Long? = null,
    val availabilityNote: String? = null,
) {
    val duration: Duration get() = Duration.between(departure, arrival)

    val departureLocal: ZonedDateTime get() = departure.atZone(origin.zone)
    val arrivalLocal: ZonedDateTime get() = arrival.atZone(destination.zone)

    /** Calendar date of departure *at the origin airport* — the day the traveller leaves home. */
    val departureDateLocal: LocalDate get() = departureLocal.toLocalDate()

    val key: String
        get() = "${airlineCode}_${flightNumber.orEmpty()}_${origin.iata}_${destination.iata}_$departure"
}
