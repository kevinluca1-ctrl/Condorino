package com.condorino.weekend.domain.model

/** Coarse categories used by the "Zieltyp" filter and by the surprise-me modes. */
enum class DestinationType {
    CITY,
    BEACH,
    NIGHTLIFE,
    CULTURE,
    FOOD,
    NATURE,
}

/**
 * Editorial metadata for a destination. These factors are *opinion*, not flight data: they are
 * maintained in `assets/destination_profiles.json` and can be overridden by the user.
 * Every factor is 0..10.
 */
data class DestinationProfile(
    val iata: String,
    val cityTrip: Int = 5,
    val nightlife: Int = 5,
    val culture: Int = 5,
    val beach: Int = 5,
    val food: Int = 5,
    val nature: Int = 5,
    /** Typical airport → city-centre transfer in minutes. Drives effective-time-on-site. */
    val transferMinutes: Int = 45,
    val distanceToCenterKm: Double? = null,
    val note: String? = null,
) {
    fun factorFor(type: DestinationType): Int = when (type) {
        DestinationType.CITY -> cityTrip
        DestinationType.BEACH -> beach
        DestinationType.NIGHTLIFE -> nightlife
        DestinationType.CULTURE -> culture
        DestinationType.FOOD -> food
        DestinationType.NATURE -> nature
    }

    /** Types this destination is genuinely good for (used by the "Zieltyp" filter). */
    val types: Set<DestinationType>
        get() = DestinationType.entries.filter { factorFor(it) >= 7 }.toSet()

    companion object {
        fun neutral(iata: String) = DestinationProfile(iata = iata)
    }
}

/**
 * A destination as the app knows it: the airport (which always comes from flight data) plus the
 * optional editorial profile. [servedDays] is derived from the flight data, never hard-coded.
 */
data class Destination(
    val airport: Airport,
    val profile: DestinationProfile? = null,
    val servedDays: Set<java.time.DayOfWeek> = emptySet(),
    val isFavorite: Boolean = false,
) {
    val iata: String get() = airport.iata
    val transferMinutes: Int get() = profile?.transferMinutes ?: DEFAULT_TRANSFER_MINUTES

    companion object {
        const val DEFAULT_TRANSFER_MINUTES = 45
    }
}
