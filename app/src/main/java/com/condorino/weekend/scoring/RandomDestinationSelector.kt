package com.condorino.weekend.scoring

import com.condorino.weekend.domain.model.DestinationType
import com.condorino.weekend.domain.model.UserPreferences
import com.condorino.weekend.domain.model.WeekendTrip
import kotlin.random.Random

/** Modes offered by the "Surprise me" button (spec §11). */
enum class RandomMode {
    ANY,
    TOP_TEN,
    UNDER_BUDGET,
    SUN,
    CITY_TRIP,
    BEST_SCORE,
}

/**
 * Picks one trip out of the scored candidates. Kept separate from the ViewModel so the selection
 * rules can be unit-tested with a seeded [Random].
 */
class RandomDestinationSelector(private val random: Random = Random.Default) {

    fun candidates(
        trips: List<WeekendTrip>,
        mode: RandomMode,
        prefs: UserPreferences,
    ): List<WeekendTrip> {
        // Only ever suggest one trip per destination, otherwise a destination with many
        // connections would dominate the draw.
        val perDestination = trips
            .groupBy { it.iata }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.score.total } }
            .sortedByDescending { it.score.total }

        return when (mode) {
            RandomMode.ANY -> perDestination
            RandomMode.TOP_TEN -> perDestination.take(10)
            RandomMode.UNDER_BUDGET -> perDestination.filter {
                val p = it.priceFor(prefs.preferredCabin)
                p != null && p.cents <= prefs.maxBudgetCents
            }
            RandomMode.SUN -> perDestination.filter {
                (it.destination.profile?.factorFor(DestinationType.BEACH) ?: 0) >= 7
            }
            RandomMode.CITY_TRIP -> perDestination.filter {
                (it.destination.profile?.factorFor(DestinationType.CITY) ?: 0) >= 7
            }
            RandomMode.BEST_SCORE -> perDestination.take(1)
        }
    }

    /** Returns null when no destination satisfies the mode — the caller must say so explicitly. */
    fun pick(
        trips: List<WeekendTrip>,
        mode: RandomMode,
        prefs: UserPreferences,
    ): WeekendTrip? {
        val pool = candidates(trips, mode, prefs)
        if (pool.isEmpty()) return null
        if (mode == RandomMode.BEST_SCORE) return pool.first()
        return pool[random.nextInt(pool.size)]
    }
}
