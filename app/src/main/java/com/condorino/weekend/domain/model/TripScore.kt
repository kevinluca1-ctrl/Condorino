package com.condorino.weekend.domain.model

enum class ScoreComponent(val defaultWeight: Double) {
    FLIGHT_TIME_COMFORT(0.25),
    STAY_QUALITY(0.20),
    WEEKEND_COMPATIBILITY(0.20),
    LOGISTICS(0.10),
    COST(0.15),
    DESTINATION_QUALITY(0.10),
}

/** One component of the score: raw 0..100 value plus the weight it was applied with. */
data class ComponentScore(
    val component: ScoreComponent,
    val value: Double,
    val weight: Double,
    /** Numeric backing of the explanation; the UI turns it into a sentence in the user's language. */
    val detail: ComponentDetail,
) {
    val weighted: Double get() = value * weight
}

/**
 * The result of [com.condorino.weekend.scoring.TripScoringEngine]. Always reproducible from
 * [components] — the UI shows the breakdown so the number is never a black box.
 */
data class TripScore(
    val total: Double,
    val components: List<ComponentScore>,
    /** Why this trip scored as it did, as structured findings. Rendered by the UI. */
    val reasons: List<TripInsight>,
    val warnings: List<TripInsight> = emptyList(),
) {
    /** 0..10 headline figure used on the trip cards ("9.4/10"). */
    val outOfTen: Double get() = total / 10.0

    fun component(c: ScoreComponent): ComponentScore? = components.firstOrNull { it.component == c }

    companion object {
        val ZERO = TripScore(0.0, emptyList(), emptyList())
    }
}
