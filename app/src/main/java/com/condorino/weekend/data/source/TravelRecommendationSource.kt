package com.condorino.weekend.data.source

import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.DestinationHighlights

/**
 * Looks up a handful of well-rated things to do near one destination — not a timetable question
 * like [FlightDataSource], nor a per-trip pricing question like [CommercialPriceSource]. It answers
 * "now that I've picked this city, what's worth seeing?", which only depends on the destination
 * itself, never on which weekend pattern or which exact flights got you there.
 *
 * Queried on demand, one destination at a time — see [TripAdvisorRecommendationSource] for why.
 */
interface TravelRecommendationSource {

    val id: String
    val displayName: String
    val strings: SourceStrings

    suspend fun status(): SourceStatus

    suspend fun highlights(destination: Airport): TravelRecommendationResult

    suspend fun selfTest(): SourceTestResult
}

sealed interface TravelRecommendationResult {
    data class Success(val highlights: DestinationHighlights) : TravelRecommendationResult
    data class NotConfigured(val reason: String, val howToFix: String) : TravelRecommendationResult
    data class Failure(val userMessage: String, val technicalDetail: String? = null) : TravelRecommendationResult
}
