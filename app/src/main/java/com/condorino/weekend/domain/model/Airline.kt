package com.condorino.weekend.domain.model

/**
 * A selectable airline whose flights this app can search for.
 *
 * [icaoCode] is the primary identifier used throughout the app — it's what OpenSky's ADS-B
 * callsigns and AeroDataBox's operating-carrier field both report, so filtering on it needs no
 * translation at either of those sources (see [Airlines]).
 */
data class Airline(
    val icaoCode: String,
    val iataCode: String,
    val displayName: String,
)

/**
 * Condor plus the Lufthansa Group's own passenger carriers — several of which, like Lufthansa
 * itself, operate substantial capacity out of Frankfurt (Condor's hub, and Lufthansa's largest
 * one) alongside Condor's own network, which is why this app can search for them too.
 *
 * Condor itself has **not** been part of Lufthansa Group since it was sold in 2020 — it is listed
 * here as this app's own always-on baseline, not as a member of the group.
 *
 * This is public airline designator data (ICAO/IATA codes, brand names) — the same kind of fact as
 * the bundled airport reference dataset (see `AirportReferenceCatalog`), not an invented API
 * contract, so it is bundled directly rather than left for the user to fill in. Cross-checked
 * 2026-08-30; the group's own subsidiary lineup does change occasionally (Lufthansa CityLine
 * shut down in April 2026, folded into the newer Lufthansa City Airlines brand, for example), so
 * this list may need the occasional update rather than being permanently fixed.
 */
object Airlines {
    /** This app's own baseline airline — always searched, not part of [LUFTHANSA_GROUP]. */
    val CONDOR = Airline(icaoCode = "CFG", iataCode = "DE", displayName = "Condor")

    val LUFTHANSA = Airline(icaoCode = "DLH", iataCode = "LH", displayName = "Lufthansa")
    val SWISS = Airline(icaoCode = "SWR", iataCode = "LX", displayName = "SWISS")
    val AUSTRIAN = Airline(icaoCode = "AUA", iataCode = "OS", displayName = "Austrian Airlines")
    val BRUSSELS = Airline(icaoCode = "BEL", iataCode = "SN", displayName = "Brussels Airlines")
    val EUROWINGS = Airline(icaoCode = "EWG", iataCode = "EW", displayName = "Eurowings")
    val DISCOVER = Airline(icaoCode = "OCN", iataCode = "4Y", displayName = "Discover Airlines")
    val EDELWEISS = Airline(icaoCode = "EDW", iataCode = "WK", displayName = "Edelweiss Air")
    val AIR_DOLOMITI = Airline(icaoCode = "DLA", iataCode = "EN", displayName = "Air Dolomiti")
    val LUFTHANSA_CITY = Airline(icaoCode = "LHX", iataCode = "VL", displayName = "Lufthansa City Airlines")

    /** Every Lufthansa Group passenger carrier this app knows about — individually selectable in
     *  Settings, each defaulting to off so an existing install's results don't change until the
     *  user opts one in. */
    val LUFTHANSA_GROUP: List<Airline> = listOf(
        LUFTHANSA, SWISS, AUSTRIAN, BRUSSELS, EUROWINGS, DISCOVER, EDELWEISS, AIR_DOLOMITI, LUFTHANSA_CITY,
    )

    /** Every airline the app can offer in the picker, Condor first. */
    val ALL: List<Airline> = listOf(CONDOR) + LUFTHANSA_GROUP

    fun byIcao(icaoCode: String): Airline? = ALL.find { it.icaoCode.equals(icaoCode, ignoreCase = true) }

    /**
     * The airline behind a code that may be **either** ICAO or IATA, because the app's sources
     * genuinely disagree about which they report: the official Condor Developer API reports the
     * IATA code "DE", while OpenSky and AeroDataBox both report the ICAO code "CFG" — all three
     * meaning Condor. A custom feed may use either. Anything the app matches on across sources
     * (standby prices, above all) therefore has to resolve a code before comparing it, or the same
     * airline read from two sources looks like two different airlines.
     *
     * Null when the code belongs to no airline this app knows — an unrecognised carrier from a
     * custom feed, or the demo data's deliberately fake "XX". That is a meaningful answer, not a
     * failure: see [canonicalIcaoOrNull].
     */
    fun resolve(code: String): Airline? {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return null
        return ALL.find { it.icaoCode.equals(trimmed, ignoreCase = true) }
            ?: ALL.find { it.iataCode.equals(trimmed, ignoreCase = true) }
    }

    /**
     * [resolve]d to the one code the app stores and compares by (ICAO), or null if this app does
     * not recognise the airline at all.
     */
    fun canonicalIcaoOrNull(code: String): String? = resolve(code)?.icaoCode

    /**
     * [canonicalIcaoOrNull], falling back to the code as given (trimmed and upper-cased) so an
     * airline this app doesn't know is still stored consistently rather than dropped.
     */
    fun canonicalIcao(code: String): String = canonicalIcaoOrNull(code) ?: code.trim().uppercase()

    /**
     * A short, human-readable list of display names for [icaoCodes] (in [ALL]'s order), for
     * status/failure messages that need to say which airlines a search covered — e.g. "Condor,
     * Lufthansa". A code this app doesn't recognise is shown as-is rather than dropped silently.
     */
    fun describe(icaoCodes: Set<String>): String {
        val known = ALL.filter { airline -> icaoCodes.any { it.equals(airline.icaoCode, ignoreCase = true) } }
        val unknown = icaoCodes.filter { code -> ALL.none { it.icaoCode.equals(code, ignoreCase = true) } }
        return (known.map { it.displayName } + unknown).joinToString()
    }
}
