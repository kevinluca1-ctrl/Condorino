package com.condorino.weekend.domain.model

import java.time.Instant

/**
 * A commercial (cash-fare) round-trip quote for one trip's exact route and dates, fetched
 * on demand from an external price-search API (see [com.condorino.weekend.data.source.GoogleFlightsPriceSource]).
 *
 * This is deliberately a *different* concept from [StandbyPrice]: a standby price is what the
 * user, as airline staff, expects to pay flying standby, typed in by hand because no source of
 * that is queried automatically (spec §26 — no MyID Travel credentials, no scraping). This is what
 * a normal paying passenger would be charged today for the same trip, if standby doesn't work out
 * and a real ticket has to be bought instead. Nothing here is airline-staff-specific, so it *can*
 * be fetched automatically.
 */
data class CommercialPriceQuote(
    val destinationIata: String,
    val cabin: Cabin,
    val roundTripPrice: Money,
    /** Null means "the source didn't report this", never "not included". */
    val carryOnIncluded: Boolean?,
    /** Free-text baggage detail from the source, e.g. "1 x 8kg", if it reported one. */
    val carryOnNote: String?,
    /** Which airline the quoted itinerary is with — may differ from Condor. Null if not reported. */
    val airline: String?,
    val retrievedAt: Instant,
)
