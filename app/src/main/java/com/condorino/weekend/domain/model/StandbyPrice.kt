package com.condorino.weekend.domain.model

/**
 * How the user entered a standby price. MyID Travel / staff-travel listings are sometimes quoted
 * per segment and sometimes for the whole round trip, so we make the user tell us which.
 */
enum class PriceEntryMode {
    PER_SEGMENT,
    ROUND_TRIP,
}

/**
 * Manually maintained standby prices for one (destination, airline) pair. The app never fetches
 * MyID Travel data (see §26 of the spec — no credentials, no scraping); everything here is typed
 * in by the user.
 *
 * [airlineIcao] exists because a route Condor and a Lufthansa Group carrier both fly is commonly
 * priced differently per airline on staff travel — a destination can therefore have more than one
 * [StandbyPrice], one per airline actually flying it. A trip is matched against the entry whose
 * [airlineIcao] equals the operating [Flight.airlineCode] of the specific flight being scored (see
 * `TripBuilder.build`); no fallback across airlines, since a Lufthansa fare and a Condor fare are
 * different products and showing one for the other would misrepresent it.
 *
 * All values are in cents. Any of them may be null: a missing price must be surfaced as
 * reported as a missing standby price rather than silently treated as 0 EUR.
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
    // Deliberately last, not right after destinationIata: every positional-arg construction
    // predating multi-airline pricing (StandbyPrice("LGW", PriceEntryMode.ROUND_TRIP, ...), all
    // over the test suite) still compiles unchanged this way, since airlineIcao just adds a new
    // trailing default rather than shifting mode's position.
    /** ICAO code of the airline this price applies to — see the class doc. Defaults to Condor's
     *  for callers that predate multi-airline pricing and never set it explicitly. */
    val airlineIcao: String = Airlines.CONDOR.icaoCode,
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
        fun empty(iata: String, airlineIcao: String = Airlines.CONDOR.icaoCode) =
            StandbyPrice(destinationIata = iata, airlineIcao = airlineIcao)
    }
}

/**
 * The compound key [StandbyPriceRepository][com.condorino.weekend.domain.repository.StandbyPriceRepository]
 * and `TripBuilder` key entries by, since [StandbyPrice.destinationIata] alone no longer uniquely
 * identifies one — see the class doc on why.
 */
fun standbyPriceKey(destinationIata: String, airlineIcao: String): String = "$destinationIata|$airlineIcao"

/** [standbyPriceKey] built from this price's own fields. */
val StandbyPrice.key: String get() = standbyPriceKey(destinationIata, airlineIcao)

/**
 * The standby price to show for a flight to [destinationIata] operated by [flightAirlineCode] —
 * the one place that decides what "this trip's price" means, so the trip list, the detail screen
 * and scoring can never disagree about it.
 *
 * Matching a flight to a price is not string equality, for two reasons found the hard way:
 *
 * 1. **Sources disagree on which code they report.** The official Condor Developer API says "DE"
 *    (IATA); OpenSky and AeroDataBox both say "CFG" (ICAO). Both are Condor. Codes are therefore
 *    resolved through [Airlines.resolve] before being compared, never compared raw.
 * 2. **Not every flight carries an airline this app can identify.** The bundled demo schedule uses
 *    a deliberately fake "XX", and a custom feed may use anything at all. Such a flight is
 *    *unattributed*, not "some other airline": in a Condor-first app it is treated as Condor's, so
 *    a price the user entered still shows against it instead of reading "not set" for no reason
 *    the user can see.
 *
 * What this deliberately does **not** do is substitute one identified airline's fare for another's:
 * if the flight is Lufthansa and only a Condor price exists, the answer is null. A Lufthansa staff
 * fare and a Condor one are different products, and showing one as the other would misstate what
 * the trip costs — the case the whole per-airline split exists for.
 */
fun Map<String, StandbyPrice>.standbyPriceFor(
    destinationIata: String,
    flightAirlineCode: String,
): StandbyPrice? {
    // Fast path: the flight's code is already exactly how the price was stored.
    this[standbyPriceKey(destinationIata, flightAirlineCode)]?.let { return it }

    val flightAirline = Airlines.resolve(flightAirlineCode)
    if (flightAirline != null) {
        // Identified airline: its own price, matched however either side spelled the code.
        this[standbyPriceKey(destinationIata, flightAirline.icaoCode)]?.let { return it }
        return values.firstOrNull {
            it.destinationIata.equals(destinationIata, ignoreCase = true) &&
                Airlines.resolve(it.airlineIcao)?.icaoCode == flightAirline.icaoCode
        }
    }

    // Unattributed flight (demo data, an unrecognised custom feed): treat it as this app's
    // baseline airline rather than as an unknown one, and show Condor's price if there is one.
    this[standbyPriceKey(destinationIata, Airlines.CONDOR.icaoCode)]?.let { return it }
    return values.firstOrNull {
        it.destinationIata.equals(destinationIata, ignoreCase = true) &&
            Airlines.resolve(it.airlineIcao)?.icaoCode == Airlines.CONDOR.icaoCode
    }
}
