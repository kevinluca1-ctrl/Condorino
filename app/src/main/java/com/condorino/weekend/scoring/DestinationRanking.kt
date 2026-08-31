package com.condorino.weekend.scoring

import com.condorino.weekend.domain.model.WeekendTrip
import com.condorino.weekend.domain.repository.WeekendSearchResult
import java.time.LocalDate

/**
 * One destination's best showing across a range of weekends.
 *
 * @param trip the best-scoring trip found for this destination anywhere in the range.
 * @param friday the weekend that trip belongs to — the one to open.
 * @param weekendCount how many weekends in the range offer this destination at all. With a
 *   timetable-derived source this is usually every one of them, which is itself the useful fact.
 */
data class DestinationPick(
    val trip: WeekendTrip,
    val friday: LocalDate,
    val weekendCount: Int,
)

/**
 * Ranking a range of weekends by *destination* rather than by weekend.
 *
 * Ranking the weekends themselves looks reasonable and is nearly useless in practice. Two of this
 * app's three real sources describe a *repeating weekly timetable* — OpenSky reconstructs one from
 * observations, and a schedule feed states one outright — so every Friday in the range offers the
 * same flights at the same times and therefore scores identically. A "best weekends" list then
 * reads as the same destination and the same score four times over, which answers nothing: the
 * weekends genuinely are equivalent, and the ordering between them is noise.
 *
 * The question that does have an answer is the other one: *where can I go, and when is it best?*
 * Each destination appears once, at its own best weekend, so the list is as long and as varied as
 * the network actually is.
 */
object DestinationRanking {

    fun bestDestinations(weekends: List<WeekendSearchResult>, limit: Int = 8): List<DestinationPick> {
        if (limit <= 0) return emptyList()

        val bestByIata = LinkedHashMap<String, DestinationPick>()
        for (weekend in weekends) {
            // One entry per destination per weekend: a weekend can hold several patterns for the
            // same city, and only its best should count towards that weekend's tally.
            for (trip in weekend.trips.groupBy { it.iata }.values.mapNotNull { group -> group.maxByOrNull { it.score.total } }) {
                val existing = bestByIata[trip.iata]
                bestByIata[trip.iata] = when {
                    existing == null -> DestinationPick(trip, weekend.friday, weekendCount = 1)
                    trip.score.total > existing.trip.score.total ->
                        DestinationPick(trip, weekend.friday, existing.weekendCount + 1)
                    else -> existing.copy(weekendCount = existing.weekendCount + 1)
                }
            }
        }

        return bestByIata.values
            .sortedWith(
                compareByDescending<DestinationPick> { it.trip.score.total }
                    // Ties are common and have to break on something a reader can follow: the
                    // sooner weekend first, then alphabetically.
                    .thenBy { it.friday }
                    .thenBy { it.trip.destination.airport.cityWithCode },
            )
            .take(limit)
    }

    /**
     * Whether every weekend in the range is effectively the same trip — the case that makes a
     * weekend ranking meaningless. Used to tell the user that outright instead of presenting an
     * order that carries no information.
     */
    fun weekendsAreInterchangeable(weekends: List<WeekendSearchResult>): Boolean {
        val scored = weekends.filter { it.trips.isNotEmpty() }
        if (scored.size < 2) return false
        val signature = scored.map { it.best?.iata to it.topScore }.distinct()
        return signature.size == 1
    }
}
