package com.condorino.weekend.domain.model

import java.time.Instant

/**
 * One nearby thing worth knowing about at a destination — an attraction, a restaurant, a hotel —
 * fetched on demand from an external travel-recommendation API (see
 * [com.condorino.weekend.data.source.TripAdvisorRecommendationSource]).
 *
 * Deliberately informational only, the same way [com.condorino.weekend.domain.model.CommercialPriceQuote]
 * is: it never feeds into [TripScore] or [Destination]'s own hand-curated [DestinationProfile]
 * factors. Those two are about *which destination to pick*; this is about *what to do once you're
 * there*, which is a question worth answering only for the one destination the reader is already
 * looking at, not something that should silently bias which trip ranks higher.
 */
data class TravelHighlight(
    val name: String,
    val category: HighlightCategory,
    /** Out of 5, matching the source's own scale. Null means "not reported", never zero. */
    val rating: Double?,
    val reviewCount: Int?,
    /** Link to the source's own page for this place, if it reported one. */
    val url: String?,
    val address: String?,
)

/** Kept small on purpose: this drives an icon choice in the UI, not a filter. */
enum class HighlightCategory { ATTRACTION, RESTAURANT, HOTEL, OTHER }

/** One destination's highlights, plus when they were fetched. */
data class DestinationHighlights(
    val destinationIata: String,
    val highlights: List<TravelHighlight>,
    val retrievedAt: Instant,
)
