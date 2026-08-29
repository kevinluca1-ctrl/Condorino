package com.condorino.weekend

import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.DestinationProfile
import com.condorino.weekend.domain.model.Flight
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Shared builders so the scoring tests read like the scenarios in the brief. */
object Fixtures {

    val FRA = Airport.FRANKFURT

    val LGW = Airport("LGW", "London Gatwick", "London", "Vereinigtes Königreich", "GB", "Europe/London")
    val BUD = Airport("BUD", "Budapest", "Budapest", "Ungarn", "HU", "Europe/Budapest")
    val FNC = Airport("FNC", "Funchal", "Funchal", "Portugal", "PT", "Atlantic/Madeira")
    val ATH = Airport("ATH", "Athen", "Athen", "Griechenland", "GR", "Europe/Athens")

    /** Friday of the reference weekend used across the tests. */
    val FRIDAY: LocalDate = LocalDate.of(2026, 9, 4)
    val THURSDAY: LocalDate = FRIDAY.minusDays(1)
    val SUNDAY: LocalDate = FRIDAY.plusDays(2)
    val MONDAY: LocalDate = FRIDAY.plusDays(3)

    fun destination(airport: Airport, transferMinutes: Int = 45) = Destination(
        airport = airport,
        profile = DestinationProfile(iata = airport.iata, transferMinutes = transferMinutes),
    )

    /**
     * Builds a leg from a *local* departure time at the origin plus a block time, which is how
     * timetables are actually published.
     */
    fun flight(
        origin: Airport,
        destination: Airport,
        date: LocalDate,
        departure: String,
        blockMinutes: Long,
        flightNumber: String = "TEST 1",
        isDirect: Boolean = true,
        provenance: DataProvenance = DataProvenance.SCHEDULE,
    ): Flight {
        val time = LocalTime.parse(departure)
        val dep: ZonedDateTime = ZonedDateTime.of(date, time, ZoneId.of(origin.timeZoneId))
        return Flight(
            flightNumber = flightNumber,
            airline = "Test",
            airlineCode = "TT",
            origin = origin,
            destination = destination,
            departure = dep.toInstant(),
            arrival = dep.toInstant().plus(Duration.ofMinutes(blockMinutes)),
            isDirect = isDirect,
            provenance = provenance,
        )
    }
}
