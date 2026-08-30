package com.condorino.weekend.data.source

import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.domain.model.Cabin
import com.condorino.weekend.domain.model.CommercialPriceQuote
import java.time.LocalDate

/**
 * Looks up what a commercial (cash-fare) ticket would cost for one exact, already-decided trip —
 * not a timetable search like [FlightDataSource]. Google Flights doesn't know about Condor's
 * standby routes; it answers a different, complementary question: "if standby doesn't work out,
 * what would buying a real ticket for these dates cost, and what's included?"
 *
 * Queried on demand (one trip, one tap), not automatically for every trip on screen — this talks
 * to a metered third-party API, and firing it for every candidate trip on Home/Calendar would burn
 * through a subscription's quota for data most of it will never be looked at.
 */
interface CommercialPriceSource {

    val id: String
    val displayName: String
    val strings: SourceStrings

    suspend fun status(): SourceStatus

    suspend fun quote(
        origin: Airport,
        destination: Airport,
        outboundDate: LocalDate,
        returnDate: LocalDate,
        cabin: Cabin,
    ): CommercialPriceResult

    suspend fun selfTest(): SourceTestResult
}

sealed interface CommercialPriceResult {
    data class Success(val quote: CommercialPriceQuote) : CommercialPriceResult
    data class NotConfigured(val reason: String, val howToFix: String) : CommercialPriceResult
    data class Failure(val userMessage: String, val technicalDetail: String? = null) : CommercialPriceResult
}
