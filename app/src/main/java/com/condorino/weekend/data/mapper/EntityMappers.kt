package com.condorino.weekend.data.mapper

import com.condorino.weekend.data.local.AirportEntity
import com.condorino.weekend.data.local.CachedFlightEntity
import com.condorino.weekend.data.local.StandbyPriceEntity
import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Flight
import com.condorino.weekend.domain.model.PriceEntryMode
import com.condorino.weekend.domain.model.StandbyPrice
import java.time.Instant

fun Airport.toEntity() = AirportEntity(
    iata = iata,
    name = name,
    city = city,
    country = country,
    countryCode = countryCode,
    timeZoneId = timeZoneId,
)

fun AirportEntity.toDomain() = Airport(
    iata = iata,
    name = name,
    city = city,
    country = country,
    countryCode = countryCode,
    timeZoneId = timeZoneId,
)

fun Flight.toEntity(sourceId: String) = CachedFlightEntity(
    id = key,
    flightNumber = flightNumber,
    airline = airline,
    airlineCode = airlineCode,
    originIata = origin.iata,
    destinationIata = destination.iata,
    departureEpochMillis = departure.toEpochMilli(),
    arrivalEpochMillis = arrival.toEpochMilli(),
    isDirect = isDirect,
    provenance = provenance.name,
    sourceId = sourceId,
    retrievedAtEpochMillis = (retrievedAt ?: Instant.now()).toEpochMilli(),
    cashFareCents = cashFareCents,
    availabilityNote = availabilityNote,
    departureLocalDate = departureDateLocal.toString(),
)

/**
 * Rehydrates a cached row. Returns null when an airport referenced by the row is no longer in the
 * catalogue — we would otherwise have to invent a time zone, which would corrupt every time shown.
 */
fun CachedFlightEntity.toDomain(
    airports: Map<String, Airport>,
    downgradeToCached: Boolean,
): Flight? {
    val origin = airports[originIata] ?: return null
    val destination = airports[destinationIata] ?: return null
    val original = runCatching { DataProvenance.valueOf(provenance) }.getOrDefault(DataProvenance.CACHED)
    return Flight(
        flightNumber = flightNumber,
        airline = airline,
        airlineCode = airlineCode,
        origin = origin,
        destination = destination,
        departure = Instant.ofEpochMilli(departureEpochMillis),
        arrival = Instant.ofEpochMilli(arrivalEpochMillis),
        isDirect = isDirect,
        // Demo data stays demo data forever; live data read back from disk becomes CACHED.
        provenance = when {
            original == DataProvenance.DEMO -> DataProvenance.DEMO
            downgradeToCached -> DataProvenance.CACHED
            else -> original
        },
        retrievedAt = Instant.ofEpochMilli(retrievedAtEpochMillis),
        cashFareCents = cashFareCents,
        availabilityNote = availabilityNote,
    )
}

fun StandbyPrice.toEntity() = StandbyPriceEntity(
    destinationIata = destinationIata,
    airlineIcao = airlineIcao,
    mode = mode.name,
    economyOutboundCents = economyOutboundCents,
    economyInboundCents = economyInboundCents,
    businessOutboundCents = businessOutboundCents,
    businessInboundCents = businessInboundCents,
    taxesCents = taxesCents,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

fun StandbyPriceEntity.toDomain() = StandbyPrice(
    destinationIata = destinationIata,
    airlineIcao = airlineIcao,
    mode = runCatching { PriceEntryMode.valueOf(mode) }.getOrDefault(PriceEntryMode.PER_SEGMENT),
    economyOutboundCents = economyOutboundCents,
    economyInboundCents = economyInboundCents,
    businessOutboundCents = businessOutboundCents,
    businessInboundCents = businessInboundCents,
    taxesCents = taxesCents,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
