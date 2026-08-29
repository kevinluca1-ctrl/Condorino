package com.condorino.weekend.domain.model

import java.time.LocalTime

/**
 * Relative weights of the six score components. Stored as doubles that need not sum to 1 — the
 * scoring engine normalises them, so the user can drag any single slider without the others
 * silently changing meaning.
 */
data class ScoreWeights(
    val flightTimeComfort: Double = 0.25,
    val stayQuality: Double = 0.20,
    val weekendCompatibility: Double = 0.20,
    val logistics: Double = 0.10,
    val cost: Double = 0.15,
    val destinationQuality: Double = 0.10,
) {
    fun forComponent(component: ScoreComponent): Double = when (component) {
        ScoreComponent.FLIGHT_TIME_COMFORT -> flightTimeComfort
        ScoreComponent.STAY_QUALITY -> stayQuality
        ScoreComponent.WEEKEND_COMPATIBILITY -> weekendCompatibility
        ScoreComponent.LOGISTICS -> logistics
        ScoreComponent.COST -> cost
        ScoreComponent.DESTINATION_QUALITY -> destinationQuality
    }

    fun withComponent(component: ScoreComponent, value: Double): ScoreWeights = when (component) {
        ScoreComponent.FLIGHT_TIME_COMFORT -> copy(flightTimeComfort = value)
        ScoreComponent.STAY_QUALITY -> copy(stayQuality = value)
        ScoreComponent.WEEKEND_COMPATIBILITY -> copy(weekendCompatibility = value)
        ScoreComponent.LOGISTICS -> copy(logistics = value)
        ScoreComponent.COST -> copy(cost = value)
        ScoreComponent.DESTINATION_QUALITY -> copy(destinationQuality = value)
    }

    val total: Double
        get() = flightTimeComfort + stayQuality + weekendCompatibility +
            logistics + cost + destinationQuality

    companion object {
        val DEFAULT = ScoreWeights()
    }
}

/**
 * Everything the user can configure. Defaults match the spec: work ends at 17:00, home is
 * Heidelberg (≈45 min to FRA), 90 min buffer at the airport.
 */
data class UserPreferences(
    val homeCity: String = "Heidelberg",
    val workEndTime: LocalTime = LocalTime.of(17, 0),
    val homeToAirportMinutes: Int = 45,
    val airportBufferMinutes: Int = 90,
    /** Buffer to be at the *destination* airport before the return flight. */
    val returnAirportBufferMinutes: Int = 90,
    val airportToHomeMinutes: Int = 45,
    val maxFlightMinutes: Int = 240,
    val minNights: Int = 1,
    val maxNights: Int = 4,
    val maxBudgetCents: Long = 30_000L,
    val preferredCabin: Cabin = Cabin.ECONOMY,
    val enabledPatterns: Set<WeekendPattern> = WeekendPattern.entries.toSet(),
    val enabledDestinationTypes: Set<DestinationType> = DestinationType.entries.toSet(),
    val minScore: Int = 0,
    val weights: ScoreWeights = ScoreWeights.DEFAULT,
    /** Latest acceptable arrival back home on a night before a working day. */
    val latestHomeArrival: LocalTime = LocalTime.of(23, 59),
) {
    /**
     * The earliest departure from FRA that does not cost any working time:
     * end of work + travel to the airport + the desired airport buffer.
     */
    val earliestReachableDeparture: LocalTime
        get() = workEndTime
            .plusMinutes(homeToAirportMinutes.toLong())
            .plusMinutes(airportBufferMinutes.toLong())

    /** When the traveller has to leave the office / home to reach a given departure time. */
    fun latestDepartureFromHomeFor(flightDeparture: LocalTime): LocalTime =
        flightDeparture.minusMinutes((homeToAirportMinutes + airportBufferMinutes).toLong())

    companion object {
        val DEFAULT = UserPreferences()
    }
}
