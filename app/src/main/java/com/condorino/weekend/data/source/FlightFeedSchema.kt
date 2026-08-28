package com.condorino.weekend.data.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **The Condorino Flight Feed** — the one JSON contract this app defines itself.
 *
 * Because Condor does not publish a free, openly documented flight-search API that this app could
 * call directly (see docs/CONDOR_DATA_SOURCES.md), the app accepts flight data in this shape from
 * three interchangeable places:
 *
 *  * a URL the user configures in Settings ([HttpFeedFlightDataSource]),
 *  * a file the user imports on the device,
 *  * the bundled demo file in `assets/` — always flagged as DEMO in the UI.
 *
 * Times are ISO-8601 **with offset** (e.g. `2026-09-04T18:15:00+02:00`) or plain UTC instants.
 * The airport time zone is what the app uses for display, so a feed only has to get the instant
 * right, not the offset formatting.
 */
@Serializable
data class FlightFeed(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    /** Free text describing where the data came from. Shown to the user under "Quelle". */
    val source: String,
    /** Set to true only for genuinely live/bookable data. Anything else is treated as schedule. */
    @SerialName("is_live") val isLive: Boolean = false,
    @SerialName("generated_at") val generatedAt: String? = null,
    val airports: List<FeedAirport> = emptyList(),
    val flights: List<FeedFlight> = emptyList(),
)

@Serializable
data class FeedAirport(
    val iata: String,
    val name: String,
    val city: String,
    /**
     * Optional: the display name is derived from [countryCode] in the reader's language, so a
     * feed only has to supply it for the rare code Java has no name for.
     */
    val country: String = "",
    @SerialName("country_code") val countryCode: String,
    /** IANA zone id, e.g. "Europe/Lisbon". Required — the app will not guess. */
    @SerialName("time_zone") val timeZone: String,
)

@Serializable
data class FeedFlight(
    @SerialName("flight_number") val flightNumber: String? = null,
    val airline: String = "Condor",
    @SerialName("airline_code") val airlineCode: String = "DE",
    val origin: String,
    val destination: String,
    /** ISO-8601 date-time with offset, or an instant ending in `Z`. */
    val departure: String,
    val arrival: String,
    @SerialName("is_direct") val isDirect: Boolean = true,
    /** Cash fare in euro cents, if the feed knows one. Omit when unknown — never send 0. */
    @SerialName("fare_cents") val fareCents: Long? = null,
    @SerialName("availability_note") val availabilityNote: String? = null,
)
