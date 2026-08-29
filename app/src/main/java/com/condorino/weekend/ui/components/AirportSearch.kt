package com.condorino.weekend.ui.components

import com.condorino.weekend.domain.model.Airport

/**
 * Ranked airport lookup used by the price list and the compare picker.
 *
 * Matching is deliberately forgiving — people type "muni" for Munich, "lgw" for Gatwick, or a
 * country name — but the *ranking* is strict, so an exact IATA code always wins over a city that
 * merely contains those letters. Without that, typing "LIS" would bury Lisbon under every airport
 * whose name happens to contain "lis".
 */
object AirportSearch {

    /** Higher scores rank first; null means "no match at all". */
    fun score(airport: Airport, query: String): Int? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0

        val iata = airport.iata.lowercase()
        val city = airport.city.lowercase()
        val name = airport.name.lowercase()
        val country = airport.displayCountry.lowercase()
        val code = airport.countryCode.lowercase()

        return when {
            iata == q -> 1000
            city == q -> 900
            iata.startsWith(q) -> 800
            city.startsWith(q) -> 700
            name.startsWith(q) -> 600
            city.split(' ', '-', '/').any { it.startsWith(q) } -> 550
            country == q || code == q -> 500
            city.contains(q) -> 400
            name.contains(q) -> 300
            country.contains(q) -> 200
            else -> null
        }
    }

    /**
     * Filters and ranks [airports]. [boost] lets a caller lift entries that matter more in its
     * context — destinations actually reachable this weekend, or ones that already have a price.
     */
    fun rank(
        airports: List<Airport>,
        query: String,
        limit: Int = Int.MAX_VALUE,
        boost: (Airport) -> Int = { 0 },
    ): List<Airport> = airports
        .mapNotNull { airport -> score(airport, query)?.let { airport to it + boost(airport) } }
        .sortedWith(
            compareByDescending<Pair<Airport, Int>> { it.second }
                .thenBy { it.first.city }
                .thenBy { it.first.iata },
        )
        .take(limit)
        .map { it.first }
}
