package com.condorino.weekend.data.source

import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Flight
import java.time.Instant
import java.time.LocalDate

/**
 * Airline-agnostic flight lookup. v1 ships Condor implementations only, but nothing above this
 * interface knows the word "Condor" — adding Lufthansa/Discover/Eurowings later means adding a
 * new implementation and registering it, nothing else (spec §25).
 */
interface FlightDataSource {

    /** Stable identifier persisted with cached rows so we know where a row came from. */
    val id: String

    /** Shown in the UI: "Quelle: …". */
    val displayName: String

    /** What kind of data this source can produce at best. */
    val bestProvenance: DataProvenance

    /**
     * Whether the source can be used right now. A source that needs credentials or a URL the user
     * has not supplied must report [SourceStatus.NotConfigured] rather than pretending to work.
     */
    suspend fun status(): SourceStatus

    suspend fun search(query: FlightSearchQuery): FlightSearchResult
}

data class FlightSearchQuery(
    val originIata: String = Airport.HOME_IATA,
    val from: LocalDate,
    val to: LocalDate,
    /** null = "everything this source knows about", used for the destination discovery pass. */
    val destinationIata: String? = null,
)

sealed interface SourceStatus {
    data object Ready : SourceStatus
    data class NotConfigured(val reason: String, val howToFix: String) : SourceStatus
    data class Unavailable(val reason: String) : SourceStatus
}

sealed interface FlightSearchResult {

    data class Success(
        val flights: List<Flight>,
        val provenance: DataProvenance,
        val retrievedAt: Instant,
        val note: String? = null,
    ) : FlightSearchResult

    /** The source exists but the user has not enabled/configured it. Not an error. */
    data class NotConfigured(val reason: String, val howToFix: String) : FlightSearchResult

    /** Network/parse/quota failure. [userMessage] is safe to show verbatim. */
    data class Failure(
        val userMessage: String,
        val technicalDetail: String? = null,
    ) : FlightSearchResult
}
