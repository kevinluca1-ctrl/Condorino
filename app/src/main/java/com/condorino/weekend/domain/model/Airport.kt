package com.condorino.weekend.domain.model

import java.time.ZoneId

/**
 * A physical airport. [timeZoneId] is the IANA zone (e.g. "Europe/London") and is the single
 * source of truth for converting the UTC [java.time.Instant] values on [Flight] into wall-clock
 * times. Never assume a fixed UTC offset — several Condor destinations (UK, PT, ES, GR, TR, EG,
 * Madeira) switch DST on different dates than Germany.
 */
data class Airport(
    val iata: String,
    val name: String,
    val city: String,
    val country: String,
    val countryCode: String,
    val timeZoneId: String,
) {
    val zone: ZoneId get() = runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.of("UTC") }

    /**
     * Country name in the reader's language, derived from the ISO code so it follows the device
     * locale instead of being frozen into the data file. Falls back to whatever [country] holds.
     */
    val displayCountry: String
        get() = runCatching {
            java.util.Locale("", countryCode).getDisplayCountry(java.util.Locale.getDefault())
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.equals(countryCode, ignoreCase = true) }
            ?: country.ifBlank { countryCode }

    /**
     * "London (LHR)" — the city with its airport code, which is the only form that stays unique.
     *
     * A city name on its own is genuinely ambiguous in this dataset: London appears four times
     * (Heathrow, Gatwick, Stansted, City), Milan and Rome twice, and the reference data names some
     * airports after the village they sit in rather than the city they serve, so several entries
     * are unrecognisable without the code. Anywhere a destination is offered as a choice, this is
     * the label to use.
     */
    val cityWithCode: String get() = "${city.ifBlank { name }.trim()} ($iata)"

    /** Unicode regional-indicator flag derived from the ISO-3166 alpha-2 country code. */
    val flag: String
        get() {
            val cc = countryCode.uppercase()
            if (cc.length != 2 || cc.any { it !in 'A'..'Z' }) return "🏳"
            val base = 0x1F1E6 - 'A'.code
            return String(Character.toChars(base + cc[0].code)) +
                String(Character.toChars(base + cc[1].code))
        }

    companion object {
        const val HOME_IATA = "FRA"

        val FRANKFURT = Airport(
            iata = "FRA",
            name = "Frankfurt Airport",
            city = "Frankfurt",
            country = "Germany",
            countryCode = "DE",
            timeZoneId = "Europe/Berlin",
        )
    }
}
