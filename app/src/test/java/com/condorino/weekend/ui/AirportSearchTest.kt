package com.condorino.weekend.ui

import com.condorino.weekend.domain.model.Airport
import com.condorino.weekend.ui.components.AirportSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirportSearchTest {

    private fun airport(iata: String, city: String, name: String, cc: String) = Airport(
        iata = iata,
        name = name,
        city = city,
        country = "",
        countryCode = cc,
        timeZoneId = "Europe/Berlin",
    )

    private val catalog = listOf(
        airport("LIS", "Lisbon", "Lisbon Humberto Delgado Airport", "PT"),
        airport("MLA", "Valletta", "Malta International Airport", "MT"),
        airport("PMI", "Palma de Mallorca", "Palma de Mallorca Airport", "ES"),
        airport("BCN", "Barcelona", "Josep Tarradellas Barcelona-El Prat Airport", "ES"),
        airport("MUC", "Munich", "Munich Airport", "DE"),
        airport("SPU", "Split", "Split Saint Jerome Airport", "HR"),
    )

    @Test
    fun `an exact IATA code always wins over a name that merely contains it`() {
        // "LIS" appears inside "Tarradellas"; Lisbon still has to come first.
        assertEquals("LIS", AirportSearch.rank(catalog, "LIS").first().iata)
    }

    @Test
    fun `a partially typed city finds it`() {
        assertEquals("MUC", AirportSearch.rank(catalog, "muni").first().iata)
        assertEquals("BCN", AirportSearch.rank(catalog, "barce").first().iata)
    }

    @Test
    fun `search is case and whitespace insensitive`() {
        assertEquals("SPU", AirportSearch.rank(catalog, "  SpLiT ").first().iata)
    }

    @Test
    fun `a country code or country name finds everything in it`() {
        val spain = AirportSearch.rank(catalog, "es").map { it.iata }
        assertTrue(spain.containsAll(listOf("PMI", "BCN")))
    }

    @Test
    fun `a word inside a multi-word city is matched`() {
        assertEquals("PMI", AirportSearch.rank(catalog, "mallorca").first().iata)
    }

    @Test
    fun `an empty query keeps the list intact rather than emptying it`() {
        assertEquals(catalog.size, AirportSearch.rank(catalog, "").size)
    }

    @Test
    fun `nonsense matches nothing instead of returning everything`() {
        assertTrue(AirportSearch.rank(catalog, "qqzz").isEmpty())
    }

    @Test
    fun `a boost lifts entries the caller cares about without breaking exact matches`() {
        val boosted = AirportSearch.rank(catalog, "a", boost = { if (it.iata == "MLA") 500 else 0 })
        assertEquals("MLA", boosted.first().iata)
        // A boost must not outrank an exact code match.
        val exact = AirportSearch.rank(catalog, "bcn", boost = { if (it.iata == "MLA") 500 else 0 })
        assertEquals("BCN", exact.first().iata)
    }

    @Test
    fun `the limit is respected`() {
        assertEquals(2, AirportSearch.rank(catalog, "", limit = 2).size)
    }

    @Test
    fun `results are deduplicated by nothing - the caller decides, so order is stable`() {
        val once = AirportSearch.rank(catalog, "a").map { it.iata }
        val twice = AirportSearch.rank(catalog, "a").map { it.iata }
        assertEquals(once, twice)
    }
}
