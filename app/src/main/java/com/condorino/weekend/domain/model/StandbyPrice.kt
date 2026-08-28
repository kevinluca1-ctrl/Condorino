package com.condorino.weekend.domain.model

/**
 * How the user entered a standby price. MyID Travel / staff-travel listings are sometimes quoted
 * per segment and sometimes for the whole round trip, so we make the user tell us which.
 */
enum class PriceEntryMode(val label: String) {
    PER_SEGMENT("Preis pro Segment"),
    ROUND_TRIP("Preis für Hin- & Rückflug"),
}

/**
 * Manually maintained standby prices for one destination. The app never fetches MyID Travel data
 * (see §26 of the spec — no credentials, no scraping); everything here is typed in by the user.
 *
 * All values are in cents. Any of them may be null: a missing price must be surfaced as
 * "Standby-Preis fehlt" rather than silently treated as 0 €.
 */
data class StandbyPrice(
    val destinationIata: String,
    val mode: PriceEntryMode = PriceEntryMode.PER_SEGMENT,
    val economyOutboundCents: Long? = null,
    val economyInboundCents: Long? = null,
    val businessOutboundCents: Long? = null,
    val businessInboundCents: Long? = null,
    /** Taxes & fees the user wants to account for separately; added once per round trip. */
    val taxesCents: Long? = null,
    val updatedAtEpochMillis: Long = 0L,
) {
    private fun roundTrip(outbound: Long?, inbound: Long?): Money? = when (mode) {
        PriceEntryMode.ROUND_TRIP -> outbound?.let { Money(it + (taxesCents ?: 0L)) }
        PriceEntryMode.PER_SEGMENT -> {
            // If only the outbound is filled in we mirror it onto the return leg, which is how
            // staff-travel pricing usually behaves for a symmetric round trip.
            val out = outbound ?: inbound
            val back = inbound ?: outbound
            if (out == null || back == null) null else Money(out + back + (taxesCents ?: 0L))
        }
    }

    val economyRoundTrip: Money? get() = roundTrip(economyOutboundCents, economyInboundCents)
    val businessRoundTrip: Money? get() = roundTrip(businessOutboundCents, businessInboundCents)

    fun roundTripFor(cabin: Cabin): Money? = when (cabin) {
        Cabin.ECONOMY -> economyRoundTrip
        Cabin.BUSINESS -> businessRoundTrip
    }

    val hasAnyPrice: Boolean get() = economyRoundTrip != null || businessRoundTrip != null

    companion object {
        fun empty(iata: String) = StandbyPrice(destinationIata = iata)
    }
}
