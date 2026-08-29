package com.condorino.weekend.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A cached flight leg. Times are stored as epoch millis (UTC) — never as local strings — so that
 * a cache written in one time zone reads correctly in another.
 */
@Entity(tableName = "cached_flights")
data class CachedFlightEntity(
    @PrimaryKey val id: String,
    val flightNumber: String?,
    val airline: String,
    val airlineCode: String,
    val originIata: String,
    val destinationIata: String,
    val departureEpochMillis: Long,
    val arrivalEpochMillis: Long,
    val isDirect: Boolean,
    val provenance: String,
    val sourceId: String,
    val retrievedAtEpochMillis: Long,
    val cashFareCents: Long?,
    val availabilityNote: String?,
    /** Local departure date at the origin, denormalised so queries can filter by day cheaply. */
    val departureLocalDate: String,
)

@Entity(tableName = "airports")
data class AirportEntity(
    @PrimaryKey val iata: String,
    val name: String,
    val city: String,
    val country: String,
    val countryCode: String,
    val timeZoneId: String,
)

@Entity(tableName = "standby_prices")
data class StandbyPriceEntity(
    @PrimaryKey val destinationIata: String,
    val mode: String,
    val economyOutboundCents: Long?,
    val economyInboundCents: Long?,
    val businessOutboundCents: Long?,
    val businessInboundCents: Long?,
    val taxesCents: Long?,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val destinationIata: String,
    val addedAtEpochMillis: Long,
)

/** One row: the outcome of the most recent refresh, so the UI can show "Zuletzt aktualisiert". */
@Entity(tableName = "refresh_state")
data class RefreshStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastSuccessEpochMillis: Long?,
    val lastAttemptEpochMillis: Long?,
    val sourceId: String?,
    val sourceLabel: String?,
    val provenance: String?,
    val lastErrorMessage: String?,
)
