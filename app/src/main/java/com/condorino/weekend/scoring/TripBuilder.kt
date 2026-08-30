package com.condorino.weekend.scoring

import com.condorino.weekend.domain.model.DataProvenance
import com.condorino.weekend.domain.model.Destination
import com.condorino.weekend.domain.model.Flight
import com.condorino.weekend.domain.model.StandbyPrice
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.standbyPriceKey
import com.condorino.weekend.domain.model.WeekendPattern
import com.condorino.weekend.domain.model.WeekendTrip
import java.time.LocalDate

/**
 * Reason a candidate pairing was thrown away. Surfaced in the UI so the app can say
 * *why* there is nothing to show instead of rendering an empty list (spec §23).
 */
enum class RejectionReason {
    NO_OUTBOUND,
    NO_INBOUND,
    NEGATIVE_STAY,
    FLIGHT_TOO_LONG,
    NIGHTS_OUT_OF_RANGE,
    NOT_DIRECT,
    OVER_BUDGET,
    BELOW_MIN_SCORE,
    ;

    /**
     * How useful this reason is as *the* explanation shown to the user.
     *
     * "No outbound flight on Thursday" is technically true for every weekend where the data only
     * covers Friday, so it would drown out far more actionable reasons such as "over budget" if we
     * simply counted occurrences. Higher wins.
     */
    val informativeness: Int
        get() = when (this) {
            OVER_BUDGET -> 70
            BELOW_MIN_SCORE -> 65
            FLIGHT_TOO_LONG -> 60
            NIGHTS_OUT_OF_RANGE -> 55
            NOT_DIRECT -> 50
            NEGATIVE_STAY -> 45
            NO_INBOUND -> 20
            NO_OUTBOUND -> 10
        }
}

data class TripBuildResult(
    val trips: List<WeekendTrip>,
    val rejections: Map<RejectionReason, Int>,
) {
    val isEmpty: Boolean get() = trips.isEmpty()

    /**
     * The reason to show the user when nothing came back. Picks the most *informative* reason
     * present rather than the most frequent one — see [RejectionReason.informativeness].
     */
    val dominantRejection: RejectionReason?
        get() = rejections.keys.maxWithOrNull(
            compareBy<RejectionReason> { it.informativeness }.thenBy { rejections[it] ?: 0 },
        )
}

/**
 * Turns a flat list of flights into scored [WeekendTrip]s.
 *
 * The builder is pure: it receives the flights, the destination catalogue and the manually
 * maintained standby prices, and returns candidates. No I/O, no Android — fully unit-testable.
 */
class TripBuilder(
    private val prefs: UserPreferences,
    private val scoringEngine: TripScoringEngine = TripScoringEngine(prefs),
    private val time: TimeCompatibilityCalculator = TimeCompatibilityCalculator(prefs),
) {

    /**
     * @param flights all known legs, in both directions, for the relevant days.
     * @param weekendFriday the Friday that anchors the weekend being searched.
     */
    fun build(
        flights: List<Flight>,
        weekendFriday: LocalDate,
        destinations: Map<String, Destination>,
        prices: Map<String, StandbyPrice>,
        patterns: Set<WeekendPattern> = prefs.enabledPatterns,
        applyHardFilters: Boolean = true,
    ): TripBuildResult {
        val rejections = mutableMapOf<RejectionReason, Int>()
        fun reject(reason: RejectionReason) {
            rejections[reason] = (rejections[reason] ?: 0) + 1
        }

        val outboundsFromHome = flights.filter { it.origin.iata == HOME }
        val inboundsToHome = flights.filter { it.destination.iata == HOME }

        val trips = mutableListOf<WeekendTrip>()

        for (pattern in patterns.sortedBy { it.priority }) {
            val outDate = dateFor(weekendFriday, pattern.outboundDay)
            val backDate = dateFor(weekendFriday, pattern.inboundDay)

            val outCandidates = outboundsFromHome.filter { it.departureDateLocal == outDate }
            if (outCandidates.isEmpty()) {
                reject(RejectionReason.NO_OUTBOUND)
                continue
            }

            for (outbound in outCandidates) {
                val iata = outbound.destination.iata
                val destination = destinations[iata] ?: Destination(airport = outbound.destination)

                val returns = inboundsToHome.filter {
                    it.origin.iata == iata && it.departureLocal.toLocalDate() == backDate
                }
                if (returns.isEmpty()) {
                    reject(RejectionReason.NO_INBOUND)
                    continue
                }

                for (inbound in returns) {
                    if (applyHardFilters && (!outbound.isDirect || !inbound.isDirect)) {
                        reject(RejectionReason.NOT_DIRECT)
                        continue
                    }
                    val longest = maxOf(outbound.duration.toMinutes(), inbound.duration.toMinutes())
                    if (applyHardFilters && longest > prefs.maxFlightMinutes) {
                        reject(RejectionReason.FLIGHT_TOO_LONG)
                        continue
                    }

                    val effective = time.effectiveTime(outbound, inbound, destination)
                    if (effective.isNegative || effective.isZero) {
                        reject(RejectionReason.NEGATIVE_STAY)
                        continue
                    }

                    val nights = time.nights(outbound, inbound)
                    if (applyHardFilters && (nights < prefs.minNights || nights > prefs.maxNights)) {
                        reject(RejectionReason.NIGHTS_OUT_OF_RANGE)
                        continue
                    }

                    // Scoped to the airline actually operating this outbound leg — a Lufthansa
                    // fare and a Condor fare to the same destination are different products, so a
                    // trip only ever picks up the price entered for its own airline (see the class
                    // doc on StandbyPrice), never another one's as a stand-in.
                    val price = prices[standbyPriceKey(iata, outbound.airlineCode)]
                    val cabinPrice = price?.roundTripFor(prefs.preferredCabin)
                    if (applyHardFilters && cabinPrice != null && cabinPrice.cents > prefs.maxBudgetCents) {
                        reject(RejectionReason.OVER_BUDGET)
                        continue
                    }

                    val score = scoringEngine.score(
                        outbound = outbound,
                        inbound = inbound,
                        destination = destination,
                        pattern = pattern,
                        standbyPrice = price,
                        effectiveTime = effective,
                        nights = nights,
                    )
                    if (applyHardFilters && score.total < prefs.minScore) {
                        reject(RejectionReason.BELOW_MIN_SCORE)
                        continue
                    }

                    trips += WeekendTrip(
                        outbound = outbound,
                        inbound = inbound,
                        destination = destination,
                        pattern = pattern,
                        nights = nights,
                        effectiveTime = effective,
                        standbyPrice = price,
                        score = score,
                        provenance = weakest(outbound.provenance, inbound.provenance),
                    )
                }
            }
        }

        // Keep only the single best trip per destination + pattern, then sort by score.
        val best = trips
            .groupBy { it.iata to it.pattern }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.score.total } }
            .sortedWith(
                compareByDescending<WeekendTrip> { it.score.total }
                    .thenBy { it.pattern.priority },
            )

        return TripBuildResult(best, rejections)
    }

    /** The date within the weekend anchored on [friday] that falls on [day]. */
    private fun dateFor(friday: LocalDate, day: java.time.DayOfWeek): LocalDate = when (day) {
        java.time.DayOfWeek.THURSDAY -> friday.minusDays(1)
        java.time.DayOfWeek.FRIDAY -> friday
        java.time.DayOfWeek.SATURDAY -> friday.plusDays(1)
        java.time.DayOfWeek.SUNDAY -> friday.plusDays(2)
        java.time.DayOfWeek.MONDAY -> friday.plusDays(3)
        else -> friday
    }

    private fun weakest(a: DataProvenance, b: DataProvenance): DataProvenance =
        if (RANK.indexOf(a) >= RANK.indexOf(b)) a else b

    companion object {
        private const val HOME = "FRA"

        /** Most trustworthy first — the "weakest" of two provenances wins. */
        private val RANK = listOf(
            DataProvenance.LIVE,
            DataProvenance.RECENTLY_UPDATED,
            DataProvenance.CACHED,
            DataProvenance.SCHEDULE,
            DataProvenance.MANUAL,
            DataProvenance.DEMO,
        )
    }
}
