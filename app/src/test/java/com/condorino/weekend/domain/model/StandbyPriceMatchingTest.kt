package com.condorino.weekend.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Matching a flight to the right standby price. These pin down the two failures that made prices
 * read "not set" for every trip in alpha-08: sources reporting IATA where prices stored ICAO, and
 * flights whose airline this app cannot identify at all.
 */
class StandbyPriceMatchingTest {

    private fun priceMap(vararg prices: StandbyPrice) = prices.associateBy { it.key }

    private fun condorPrice(iata: String = "BUD") =
        StandbyPrice.empty(iata, Airlines.CONDOR.icaoCode).copy(economyOutboundCents = 4_500)

    private fun lufthansaPrice(iata: String = "BUD") =
        StandbyPrice.empty(iata, Airlines.LUFTHANSA.icaoCode).copy(economyOutboundCents = 9_900)

    @Test
    fun `a Condor flight reported by its IATA code still finds a price stored under ICAO`() {
        // The official Condor Developer API reports "DE"; prices are stored as "CFG".
        val found = priceMap(condorPrice()).standbyPriceFor("BUD", "DE")
        assertEquals(4_500L, found?.economyOutboundCents)
    }

    @Test
    fun `a Condor flight reported by its ICAO code finds the same price`() {
        val found = priceMap(condorPrice()).standbyPriceFor("BUD", "CFG")
        assertEquals(4_500L, found?.economyOutboundCents)
    }

    @Test
    fun `a price stored under IATA is still found for a flight reported by ICAO`() {
        // The reverse of the above: whichever designator either side used, they must meet.
        val stored = StandbyPrice.empty("BUD", "DE").copy(economyOutboundCents = 4_500)
        val found = mapOf(stored.key to stored).standbyPriceFor("BUD", "CFG")
        assertEquals(4_500L, found?.economyOutboundCents)
    }

    @Test
    fun `an unidentifiable airline falls back to the Condor price rather than showing nothing`() {
        // The bundled demo schedule uses "XX" on purpose; a custom feed may use anything.
        val found = priceMap(condorPrice()).standbyPriceFor("BUD", "XX")
        assertEquals(4_500L, found?.economyOutboundCents)
    }

    @Test
    fun `an identified airline never borrows another airline's fare`() {
        // A Lufthansa staff fare and a Condor one are different products.
        val found = priceMap(condorPrice()).standbyPriceFor("BUD", "LH")
        assertNull(found)
    }

    @Test
    fun `each airline gets its own price when a destination has several`() {
        val prices = priceMap(condorPrice(), lufthansaPrice())
        assertEquals(4_500L, prices.standbyPriceFor("BUD", "DE")?.economyOutboundCents)
        assertEquals(9_900L, prices.standbyPriceFor("BUD", "LH")?.economyOutboundCents)
    }

    @Test
    fun `an unidentifiable airline takes Condor's price, not whichever price happens to exist`() {
        val prices = priceMap(condorPrice(), lufthansaPrice())
        assertEquals(4_500L, prices.standbyPriceFor("BUD", "XX")?.economyOutboundCents)
    }

    @Test
    fun `an unidentifiable airline finds nothing when only another airline has a price`() {
        // Nothing to fall back to: inventing the Lufthansa fare here would misstate the cost.
        assertNull(priceMap(lufthansaPrice()).standbyPriceFor("BUD", "XX"))
    }

    @Test
    fun `a destination with no price at all is still null`() {
        assertNull(priceMap(condorPrice("BUD")).standbyPriceFor("LGW", "DE"))
    }

    @Test
    fun `airline codes match regardless of case or padding`() {
        val found = priceMap(condorPrice()).standbyPriceFor("BUD", " de ")
        assertEquals(4_500L, found?.economyOutboundCents)
    }
}
